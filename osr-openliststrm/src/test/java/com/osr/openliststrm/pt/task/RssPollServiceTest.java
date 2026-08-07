package com.osr.openliststrm.pt.task;

import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.pt.indexer.IndexerBackpressureException;
import com.osr.openliststrm.pt.indexer.IndexerHttpException;
import com.osr.openliststrm.pt.indexer.IndexerRateLimiter;
import com.osr.openliststrm.pt.indexer.TorznabClient;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.mockito.stubbing.Answer;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RssPollServiceTest {

    @Mock private IPtIndexerPlusService indexerService;
    @Mock private TorznabClient torznabClient;
    @Mock private SubscriptionEngine subscriptionEngine;

    private IndexerRateLimiter rateLimiter;

    private RssPollService service() {
        // 零间隔限流器：本类验证轮询编排与退避账目，不测节流本身
        rateLimiter = new IndexerRateLimiter(0L, 5000L, 8);
        return new RssPollService(indexerService, torznabClient, subscriptionEngine, rateLimiter, 2, 4);
    }

    private PtIndexerPlus indexer(int id, Integer pollInterval, java.util.Date lastPoll, int failCount) {
        PtIndexerPlus i = new PtIndexerPlus();
        i.setId(id);
        i.setName("idx-" + id);
        i.setPollInterval(pollInterval);
        i.setLastPollTime(lastPoll);
        i.setFailCount(failCount);
        i.setEnabled("1");
        return i;
    }

    private TorrentInfo torrent(String title) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        return t;
    }

    private TorrentInfo torrent(String title, String guid) {
        TorrentInfo t = torrent(title);
        t.setGuid(guid);
        return t;
    }

    private TorrentInfo torrent(String title, String guid, String pubDate) {
        TorrentInfo t = torrent(title, guid);
        t.setPubDate(pubDate);
        return t;
    }

    @Test
    void 从未轮询过的索引器_视为到期_会拉取() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 0)));
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1")));

        service().poll();

        verify(torznabClient).fetch(any());
        verify(subscriptionEngine).process(anyList());
    }

    @Test
    void 未到轮询周期的索引器_跳过不拉取() throws Exception {
        // 刚轮询过（1 秒前），周期 600 秒
        when(indexerService.listEnabled()).thenReturn(
                List.of(indexer(1, 600, new java.util.Date(System.currentTimeMillis() - 1000), 0)));

        service().poll();

        verify(torznabClient, never()).fetch(any());
        verify(subscriptionEngine, never()).process(anyList());
    }

    @Test
    void 已过轮询周期_到期拉取() throws Exception {
        // 上次 700 秒前，周期 600 秒 → 到期
        when(indexerService.listEnabled()).thenReturn(
                List.of(indexer(1, 600, new java.util.Date(System.currentTimeMillis() - 700_000), 0)));
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1")));

        service().poll();

        verify(torznabClient).fetch(any());
    }

    @Test
    void 拉取成功_更新索引器状态为OK并清零失败计数() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 2)));
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1")));

        service().poll();

        ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
        verify(indexerService).updateById(captor.capture());
        assertEquals("OK", captor.getValue().getLastStatus());
        assertEquals(0, captor.getValue().getFailCount());
    }

    @Test
    void 拉取失败_累加失败计数并记录错误() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 0)));
        when(torznabClient.fetch(any())).thenThrow(new IOException("connection refused"));

        service().poll();

        ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
        verify(indexerService).updateById(captor.capture());
        assertEquals(1, captor.getValue().getFailCount());
        // 失败时不调用引擎
        verify(subscriptionEngine, never()).process(anyList());
    }

    @Test
    void 多个索引器的种子被汇总后一次性交给引擎() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(
                indexer(1, 600, null, 0), indexer(2, 600, null, 0)));
        // 用 thenAnswer 而非 thenReturn 顺序打桩：并发下调用顺序不确定
        when(torznabClient.fetch(any())).thenAnswer((Answer<List<TorrentInfo>>) invocation -> {
            PtIndexerPlus idx = invocation.getArgument(0);
            return List.of(torrent("t-" + idx.getId()));
        });

        service().poll();

        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).process(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void 无到期索引器_不调用引擎() throws Exception {
        when(indexerService.listEnabled()).thenReturn(
                List.of(indexer(1, 600, new java.util.Date(), 0)));

        service().poll();

        verify(subscriptionEngine, never()).process(anyList());
    }

    // ---------- 拉取窗口覆盖度校验 ----------

    @Test
    void 首次拉取_记录游标不告警() throws Exception {
        PtIndexerPlus idx = indexer(1, 600, null, 0);
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1", "guid-1")));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            assertNotNull(idx.getLastSeenGuidHash());
            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
        }
    }

    @Test
    void 上轮游标仍在本轮结果中_不告警() throws Exception {
        PtIndexerPlus idx = indexer(1, 600, null, 0);
        idx.setLastSeenGuidHash(com.osr.openliststrm.pt.indexer.GuidHasher.hash("guid-old"));
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1", "guid-new"), torrent("t0", "guid-old")));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
        }
    }

    @Test
    void 上轮游标未出现在本轮结果中_告警提示可能漏拉() throws Exception {
        PtIndexerPlus idx = indexer(1, 600, null, 0);
        idx.setLastSeenGuidHash(com.osr.openliststrm.pt.indexer.GuidHasher.hash("guid-old"));
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1", "guid-new")));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("覆盖不全"))));
        }
    }

    @Test
    void 覆盖度校验只认哈希后的游标_不受pubDate缺失影响() throws Exception {
        // 诊断日志会去解析 pubDate，全部缺失时不能影响覆盖判定本身，也不能抛
        PtIndexerPlus idx = indexer(1, 600, null, 0);
        idx.setLastSeenGuidHash(com.osr.openliststrm.pt.indexer.GuidHasher.hash("guid-old"));
        when(torznabClient.fetch(any())).thenReturn(List.of(
                torrent("t1", "guid-new", null), torrent("t0", "guid-old", "不是个日期")));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
            assertEquals(com.osr.openliststrm.pt.indexer.GuidHasher.hash("guid-new"), idx.getLastSeenGuidHash());
        }
    }

    @Test
    void 漏拉告警不再建议缩短周期_改为指向失败次数与诊断行() throws Exception {
        // 回归用例：旧文案"建议缩短轮询间隔"会把用户往反方向引——请求更密更容易撞限流冷却，
        // 冷却期的快速失败又累加 fail_count 触发退避，实际间隔反而被放大
        PtIndexerPlus idx = indexer(1, 300, null, 0);
        idx.setLastSeenGuidHash(com.osr.openliststrm.pt.indexer.GuidHasher.hash("guid-old"));
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1", "guid-new")));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("覆盖不全") && !m.contains("建议缩短轮询间隔"))));
        }
    }

    @Test
    void 无启用索引器_不做任何事() {
        when(indexerService.listEnabled()).thenReturn(List.of());

        service().poll();

        verify(subscriptionEngine, never()).process(anyList());
    }

    @Test
    void 连续失败第3次_发一次告警() throws Exception {
        // 直接调用 pollOne（同步，测试线程），避免 poll() 内部虚拟线程池导致 MockedStatic 跨线程失效
        PtIndexerPlus idx = indexer(1, 600, null, 2);
        when(torznabClient.fetch(any())).thenThrow(new IOException("connection refused"));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("已连续失败 3 次"))));
        }
    }

    @Test
    void 失败但未达第3次_不发告警() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 0)));
        when(torznabClient.fetch(any())).thenThrow(new IOException("connection refused"));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().poll();

            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
        }
    }

    // ---------- 自动降级 ----------

    @Test
    void 连续失败达到第10次_自动停用并告警一次() throws Exception {
        PtIndexerPlus idx = indexer(1, 600, null, 9);
        when(torznabClient.fetch(any())).thenThrow(new IOException("connection refused"));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
            verify(indexerService).updateById(captor.capture());
            assertEquals("0", captor.getValue().getEnabled());
            assertEquals(10, captor.getValue().getFailCount());
            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("已自动停用"))));
        }
    }

    @Test
    void 连续失败未达第10次_仍启用不停用() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 5)));
        when(torznabClient.fetch(any())).thenThrow(new IOException("connection refused"));

        service().poll();

        ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
        verify(indexerService).updateById(captor.capture());
        assertEquals("1", captor.getValue().getEnabled());
    }

    @Test
    void 达到第3次告警阈值时不会同时触发停用() throws Exception {
        PtIndexerPlus idx = indexer(1, 600, null, 2);
        when(torznabClient.fetch(any())).thenThrow(new IOException("connection refused"));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
            verify(indexerService).updateById(captor.capture());
            assertEquals("1", captor.getValue().getEnabled());
            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("已连续失败 3 次"))));
        }
    }

    // ---------- 自愈 ----------

    private PtIndexerPlus disabledIndexer(int id, java.util.Date disabledAt) {
        PtIndexerPlus i = indexer(id, 600, null, 10);
        i.setEnabled("0");
        i.setDisabledAt(disabledAt);
        return i;
    }

    @Test
    void 自愈_冷却期已过且探测成功_自动重新启用并通知() throws Exception {
        when(indexerService.listDisabled()).thenReturn(
                List.of(disabledIndexer(1, new java.util.Date(System.currentTimeMillis() - 3 * 3600_000L))));
        when(torznabClient.testConnection(any())).thenReturn(true);

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().poll();

            ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
            verify(indexerService).updateById(captor.capture());
            assertEquals("1", captor.getValue().getEnabled());
            assertEquals(0, captor.getValue().getFailCount());
            assertNull(captor.getValue().getDisabledAt());
            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("自动重新启用"))));
        }
    }

    @Test
    void 自愈_冷却期已过但探测仍失败_重置冷却计时不通知() throws Exception {
        when(indexerService.listDisabled()).thenReturn(
                List.of(disabledIndexer(1, new java.util.Date(System.currentTimeMillis() - 3 * 3600_000L))));
        when(torznabClient.testConnection(any())).thenReturn(false);

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().poll();

            ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
            verify(indexerService).updateById(captor.capture());
            assertEquals("0", captor.getValue().getEnabled());
            assertNotNull(captor.getValue().getDisabledAt());
            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
        }
    }

    @Test
    void 自愈_冷却期未过_跳过探测() throws Exception {
        when(indexerService.listDisabled()).thenReturn(
                List.of(disabledIndexer(1, new java.util.Date(System.currentTimeMillis() - 3600_000L))));

        service().poll();

        verify(torznabClient, never()).testConnection(any());
        verify(indexerService, never()).updateById(any());
    }

    @Test
    void 自愈_人工停用disabledAt为空_不做探测() throws Exception {
        when(indexerService.listDisabled()).thenReturn(List.of(disabledIndexer(1, null)));

        service().poll();

        verify(torznabClient, never()).testConnection(any());
        verify(indexerService, never()).updateById(any());
    }

    // ---------- 失败退避：防止失败索引器被 60 秒心跳每轮重打 ----------

    @Test
    void 拉取失败_同样推进lastPollTime() throws Exception {
        // 回归用例：早先 lastPollTime 只在成功分支写，失败的索引器会立刻又"到期"，
        // 被心跳每轮重打，把 10 分钟的轮询周期塌缩成 1 分钟
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 0)));
        when(torznabClient.fetch(any())).thenThrow(new IOException("connection refused"));

        service().poll();

        ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
        verify(indexerService).updateById(captor.capture());
        assertNotNull(captor.getValue().getLastPollTime());
    }

    @Test
    void 连续失败2次的索引器_轮询间隔退避到4倍_未到期不拉取() throws Exception {
        // 周期 600 秒、已失败 2 次 → 有效间隔 600×4=2400 秒；距上次仅 700 秒，不该重试
        when(indexerService.listEnabled()).thenReturn(
                List.of(indexer(1, 600, new java.util.Date(System.currentTimeMillis() - 700_000), 2)));

        service().poll();

        verify(torznabClient, never()).fetch(any());
    }

    @Test
    void 连续失败2次但已过退避间隔_恢复拉取() throws Exception {
        when(indexerService.listEnabled()).thenReturn(
                List.of(indexer(1, 600, new java.util.Date(System.currentTimeMillis() - 2_500_000), 2)));
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1")));

        service().poll();

        verify(torznabClient).fetch(any());
    }

    // ---------- 429/503：只冷却，不计失败 ----------

    @Test
    void 命中429_不计入失败次数_并让该索引器进入RetryAfter指定的冷却() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 0)));
        when(torznabClient.fetch(any())).thenThrow(new IndexerHttpException(429, 120));

        RssPollService svc = service();
        svc.poll();

        ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
        verify(indexerService).updateById(captor.capture());
        // 限流不是"索引器坏了"，累加失败次数最终会把配置完好的索引器误停用
        assertEquals(0, captor.getValue().getFailCount());
        long remaining = rateLimiter.remainingCooldownMillis(1);
        assertTrue(remaining > 100_000 && remaining <= 120_000,
                "应按 Retry-After 冷却约 120 秒，实际剩余 " + remaining + "ms");
    }

    @Test
    void 命中503且无RetryAfter_使用默认冷却() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 0)));
        when(torznabClient.fetch(any())).thenThrow(new IndexerHttpException(503, null));

        RssPollService svc = service();
        svc.poll();

        long remaining = rateLimiter.remainingCooldownMillis(1);
        assertTrue(remaining > 250_000 && remaining <= 300_000,
                "无 Retry-After 时应落到 300 秒默认冷却，实际剩余 " + remaining + "ms");
    }

    @Test
    void 命中500_仍按普通失败处理_计入失败次数() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 0)));
        when(torznabClient.fetch(any())).thenThrow(new IndexerHttpException(500, null));

        service().poll();

        ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
        verify(indexerService).updateById(captor.capture());
        assertEquals(1, captor.getValue().getFailCount());
        assertEquals(0, rateLimiter.remainingCooldownMillis(1));
    }

    // ---------- 本地背压：请求没发出去，不记在索引器账上 ----------

    @Test
    void 限流冷却期内的快速失败_不计入失败次数() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 300, null, 0)));
        when(torznabClient.fetch(any())).thenThrow(
                new IndexerBackpressureException("索引器处于限流冷却中，还需等待 280 秒，本次请求跳过"));

        service().poll();

        ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
        verify(indexerService).updateById(captor.capture());
        assertEquals(0, captor.getValue().getFailCount());
        assertTrue(captor.getValue().getLastStatus().contains("本轮跳过"));
        // 轮询时钟照样推进：下一次心跳不该立刻重打一个正在冷却的索引器
        assertNotNull(captor.getValue().getLastPollTime());
    }

    @Test
    void 等待全局并发许可超时_同样不计入失败次数() throws Exception {
        // 别的索引器把名额占满，与本索引器是否健康无关
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 300, null, 0)));
        when(torznabClient.fetch(any())).thenThrow(
                new IndexerBackpressureException("等待全局并发许可（其它索引器正占满名额）超过 30000ms，本次请求跳过"));

        service().poll();

        ArgumentCaptor<PtIndexerPlus> captor = ArgumentCaptor.forClass(PtIndexerPlus.class);
        verify(indexerService).updateById(captor.capture());
        assertEquals(0, captor.getValue().getFailCount());
    }

    @Test
    void 背压跳过_不清零此前累计的失败次数() throws Exception {
        // 本轮没跟索引器说上话，拿不到"它已经好了"的证据，抹掉退避等于凭空恢复高频轮询
        PtIndexerPlus idx = indexer(1, 300, null, 3);
        when(torznabClient.fetch(any())).thenThrow(new IndexerBackpressureException("冷却中"));

        service().pollOne(idx, new java.util.ArrayList<>());

        assertEquals(3, idx.getFailCount());
    }

    @Test
    void 命中429后紧接着的冷却快速失败_失败次数仍为0() throws Exception {
        // 回归用例：冷却期的快速失败若按普通失败处理，429 分支"不计失败"的设计会被原样绕开——
        // fail_count 照涨，退避把 5 分钟的周期放大到几十分钟，两次成功拉取之间的窗口随之拉长，
        // 最后报"拉取窗口覆盖不全"，而用户再缩短周期只会撞得更频繁
        PtIndexerPlus idx = indexer(1, 300, null, 0);
        when(torznabClient.fetch(any()))
                .thenThrow(new IndexerHttpException(429, 300))
                .thenThrow(new IndexerBackpressureException("索引器处于限流冷却中，还需等待 295 秒，本次请求跳过"))
                .thenThrow(new IndexerBackpressureException("索引器处于限流冷却中，还需等待 290 秒，本次请求跳过"));

        RssPollService svc = service();
        svc.pollOne(idx, new java.util.ArrayList<>());
        svc.pollOne(idx, new java.util.ArrayList<>());
        svc.pollOne(idx, new java.util.ArrayList<>());

        assertEquals(0, idx.getFailCount());
    }
}
