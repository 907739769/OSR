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
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private ch.qos.logback.classic.Logger coverageLogger;
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs;

    @org.junit.jupiter.api.BeforeEach
    void attachLogAppender() {
        coverageLogger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(RssPollService.class);
        logs = new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        coverageLogger.addAppender(logs);
        coverageLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
    }

    @org.junit.jupiter.api.AfterEach
    void detachLogAppender() {
        coverageLogger.detachAppender(logs);
        logs.stop();
    }

    private long countLogContaining(String fragment) {
        return logs.list.stream().filter(e -> e.getFormattedMessage().contains(fragment)).count();
    }

    private RssPollService service() {
        // 零间隔限流器：本类验证轮询编排与退避账目，不测节流本身
        rateLimiter = new IndexerRateLimiter(0L, 5000L, 8);
        return new RssPollService(indexerService, torznabClient, subscriptionEngine, rateLimiter, 2, 4, 24);
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

    /** 相对当前时间 minutesAgo 分钟前发布的种子，pubDate 用 Torznab 惯用的 RFC 1123 格式 */
    private TorrentInfo aged(String title, long minutesAgo) {
        TorrentInfo t = torrent(title, "guid-" + title);
        t.setPubDate(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(minutesAgo)));
        return t;
    }

    private java.util.Date minutesAgo(long minutes) {
        return java.util.Date.from(java.time.Instant.now().minusSeconds(minutes * 60));
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

    /**
     * {@code RssPollTask} 每 60 秒触发一次而索引器周期常见 10 分钟，所以绝大多数触发都是空转。
     * 空转必须报出 {@code ranAnything()==false}，否则它会去喂 {@code RoundHeartbeat}：
     * 一边把计时器一直按回去、压掉真正拉回几百条种子的那一轮，一边让 30 分钟的心跳有
     * 约 10/11 的概率落在空转那一刻，打出「0 个索引器拉回 0 条种子」——读起来像索引器全没了。
     */
    @Test
    void 一个索引器都没到期时_本轮不算跑过() throws Exception {
        when(indexerService.listEnabled()).thenReturn(
                List.of(indexer(1, 600, new java.util.Date(System.currentTimeMillis() - 1000), 0)));

        RssPollService.PollOutcome outcome = service().poll();

        assertFalse(outcome.ranAnything(), "空转不算跑过一轮，不能拿去喂心跳");
        assertFalse(outcome.changed());
        assertEquals(0, outcome.dueIndexers());
    }

    @Test
    void 有索引器到期时_本轮算跑过() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 0)));
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1")));

        RssPollService.PollOutcome outcome = service().poll();

        assertTrue(outcome.ranAnything());
        assertEquals(1, outcome.dueIndexers());
        assertEquals(1, outcome.torrents());
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

    /**
     * 推送成功不再发「本轮推送了 N 个种子」的汇总。它与逐条「订阅命中」完全重复
     * （推 3 个 → 3 条详情 + 1 条只有数字的汇总），而且是广播：多用户环境下 B 会收到
     * 一个自己无从追查的数字，那 3 条详情只发给了 A。
     */
    @Test
    void 推送成功_不再发本轮汇总通知() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1, 600, null, 0)));
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1")));
        when(subscriptionEngine.process(anyList())).thenReturn(3);

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().poll();

            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
        }
    }

    @Test
    void 无到期索引器_不调用引擎() throws Exception {
        when(indexerService.listEnabled()).thenReturn(
                List.of(indexer(1, 600, new java.util.Date(), 0)));

        service().poll();

        verify(subscriptionEngine, never()).process(anyList());
    }

    // ---------- 拉取窗口覆盖度校验：时间游标 ----------

    @Test
    void 首次拉取_记录时间游标不告警() throws Exception {
        PtIndexerPlus idx = indexer(1, 600, null, 0);
        when(torznabClient.fetch(any())).thenReturn(List.of(aged("t1", 1)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            assertNotNull(idx.getLastSeenPubTime());
            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
        }
    }

    @Test
    void 窗口下沿早于上轮游标_两轮首尾相接_不告警() throws Exception {
        PtIndexerPlus idx = indexer(1, 300, null, 0);
        idx.setLastSeenPubTime(minutesAgo(5));
        // 本页覆盖到 30 分钟前，远早于上轮游标（5 分钟前）→ 中间没有断档
        when(torznabClient.fetch(any())).thenReturn(List.of(aged("新", 1), aged("中", 10), aged("老", 30)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
        }
    }

    /**
     * 「拉取窗口正常」原先是每索引器每轮无条件打的一句「一切正常」——7 个索引器按 11 分钟
     * 一轮算就是 917 行/天，而生产实测余量是 4.2~23.6 小时、轮询周期只有 10 分钟，
     * 25 倍以上的富余，那个数字不构成任何信息。现在只在余量收窄到 3 轮以内时才说。
     */
    @Test
    void 余量充裕时不记日志() throws Exception {
        PtIndexerPlus idx = indexer(1, 300, null, 0);   // 阈值 = 3 × 300 秒 = 15 分钟
        idx.setLastSeenPubTime(minutesAgo(5));
        // 下沿 30 分钟前、游标 5 分钟前 → 余量 25 分钟，远超阈值
        when(torznabClient.fetch(any())).thenReturn(List.of(aged("新", 1), aged("老", 30)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());
        }

        assertEquals(0, countLogContaining("拉取窗口余量偏紧"));
        assertEquals(0, countLogContaining("拉取窗口正常"), "这句「报平安」已经删掉，不要复活");
    }

    @Test
    void 余量不足三轮时记一行() throws Exception {
        PtIndexerPlus idx = indexer(1, 300, null, 0);   // 阈值 15 分钟
        idx.setLastSeenPubTime(minutesAgo(5));
        // 下沿 12 分钟前、游标 5 分钟前 → 余量 7 分钟，低于阈值
        when(torznabClient.fetch(any())).thenReturn(List.of(aged("新", 1), aged("老", 12)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());
            tg.verify(() -> TgHelper.sendMsg(anyString()), never());   // 还没到缺口，不发通知
        }

        assertEquals(1, countLogContaining("拉取窗口余量偏紧"));
    }

    /**
     * 阈值必须跟着退避走：fail_count 把实际间隔放大 8 倍之后，原本充裕的余量就不再充裕了。
     * 这条同时钉住 effectiveIntervalSeconds 确实被判据用上了——写死 300 秒的话它会红。
     */
    @Test
    void 退避把实际间隔放大后_同样的余量变成偏紧() throws Exception {
        PtIndexerPlus idx = indexer(1, 300, null, 3);   // 退避 8 倍 → 实际 2400 秒，阈值 2 小时
        idx.setLastSeenPubTime(minutesAgo(5));
        // 与「余量充裕」那条完全相同的数据：余量 25 分钟
        when(torznabClient.fetch(any())).thenReturn(List.of(aged("新", 1), aged("老", 30)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());
        }

        assertEquals(1, countLogContaining("拉取窗口余量偏紧"));
    }

    @Test
    void 窗口下沿晚于上轮游标_中间那段没被看到_告警() throws Exception {
        PtIndexerPlus idx = indexer(1, 300, null, 0);
        idx.setLastSeenPubTime(minutesAgo(30));
        // 本页最老才到 10 分钟前，30~10 分钟前发布的种子一条都没看到
        when(torznabClient.fetch(any())).thenReturn(List.of(aged("新", 1), aged("次新", 10)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("覆盖不全"))));
        }
    }

    @Test
    void 置顶的远古种子不参与窗口下沿计算_不再恒定压低下沿() throws Exception {
        // mteam 现场用例：整页最老是一条 2015 年的置顶种子。若拿它当窗口下沿，
        // 判据恒成立、真漏拉也一起被静音
        PtIndexerPlus idx = indexer(1, 300, null, 0);
        idx.setLastSeenPubTime(minutesAgo(30));
        when(torznabClient.fetch(any())).thenReturn(List.of(
                aged("新", 1), aged("次新", 10),
                torrent("置顶", "guid-sticky", "Sat, 07 Nov 2015 15:40:42 +0000")));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("覆盖不全"))));
        }
    }

    @Test
    void 游标取pubDate最新的那条_而不是列表首条() throws Exception {
        // 置顶种子排在首位时，按下标取游标会把游标记到一条老种子上
        PtIndexerPlus idx = indexer(1, 300, null, 0);
        when(torznabClient.fetch(any())).thenReturn(List.of(aged("置顶但不新", 120), aged("真正最新", 2)));

        service().pollOne(idx, new java.util.ArrayList<>());

        long ageSeconds = (System.currentTimeMillis() - idx.getLastSeenPubTime().getTime()) / 1000;
        assertTrue(ageSeconds < 600, "游标应记在 2 分钟前那条上，实际记的是 " + ageSeconds + " 秒前");
        // 兜底 guid 游标也应指向同一条，而不是首条
        assertEquals(com.osr.openliststrm.pt.indexer.GuidHasher.hash("guid-真正最新"), idx.getLastSeenGuidHash());
    }

    @Test
    void 未来时间的pubDate被剔除_不会把游标推到未来() throws Exception {
        // 站点时钟不同步时若让未来时间成为游标，之后每轮判定都恒成立，覆盖度校验静默失效
        PtIndexerPlus idx = indexer(1, 300, null, 0);
        when(torznabClient.fetch(any())).thenReturn(List.of(
                aged("来自未来", -3 * 24 * 60), aged("正常最新", 2)));

        service().pollOne(idx, new java.util.ArrayList<>());

        assertTrue(idx.getLastSeenPubTime().getTime() <= System.currentTimeMillis(),
                "游标不该被推到未来：" + idx.getLastSeenPubTime());
    }

    @Test
    void 整页都在截断线以外_窗口远大于轮询周期_跳过判定不告警() throws Exception {
        // 发布极慢的站点：一页里连一条 24 小时内的都没有，说明窗口至少覆盖一天，不可能漏
        PtIndexerPlus idx = indexer(1, 300, null, 0);
        idx.setLastSeenPubTime(minutesAgo(10));
        when(torznabClient.fetch(any())).thenReturn(List.of(
                aged("三天前", 3 * 24 * 60), aged("五天前", 5 * 24 * 60)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
        }
    }

    // ---------- 覆盖度校验：pubDate 不可用时的 guid 兜底 ----------

    @Test
    void pubDate全部不可解析_退回guid游标_未命中时写明是兜底判据() throws Exception {
        PtIndexerPlus idx = indexer(1, 600, null, 0);
        idx.setLastSeenGuidHash(com.osr.openliststrm.pt.indexer.GuidHasher.hash("guid-old"));
        when(torznabClient.fetch(any())).thenReturn(List.of(torrent("t1", "guid-new", null)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("覆盖不全") && m.contains("兜底判据"))));
            assertNull(idx.getLastSeenPubTime(), "没有可用 pubDate 时不该写时间游标");
        }
    }

    @Test
    void pubDate全部不可解析_退回guid游标_命中不告警() throws Exception {
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
        idx.setLastSeenPubTime(minutesAgo(30));
        when(torznabClient.fetch(any())).thenReturn(List.of(aged("新", 1), aged("次新", 10)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().pollOne(idx, new java.util.ArrayList<>());

            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("覆盖不全") && m.contains("失败次数"))));
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
