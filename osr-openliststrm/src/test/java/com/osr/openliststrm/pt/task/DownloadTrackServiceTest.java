package com.osr.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrentFile;
import com.osr.openliststrm.pt.ws.PtStatusWebSocket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DownloadTrackServiceTest {

    @Mock private IPtDownloadRecordPlusService recordService;
    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    @Mock private DownloadCompletionSyncTrigger completionSyncTrigger;
    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private DownloaderClientFactory downloaderClientFactory;
    @Mock private IDownloaderClient downloaderClient;
    @Mock private IPtIndexerPlusService indexerService;

    private DownloadTrackService service() {
        // 默认桩：查不到任何订阅（对应"订阅已删除"分支），使全部现有用例回退全局默认值 24 小时，
        // 与改造前的行为保持一致，不用逐个用例改断言。
        when(subscriptionService.listByIds(any())).thenReturn(List.of());
        return new DownloadTrackService(recordService, episodeService, completionSyncTrigger, subscriptionService,
                downloaderClientFactory, indexerService, 3, 24);
    }

    private PtDownloaderPlus downloader() {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(1);
        d.setTag("osr-pt");
        return d;
    }

    private PtDownloadRecordPlus record(int id, int episode, String tag, String state, long pushedAgoMs) {
        PtDownloadRecordPlus r = new PtDownloadRecordPlus();
        r.setId(id);
        r.setSubId(10);
        r.setEpisode(episode);
        r.setTrackingTag(tag);
        r.setState(state);
        r.setTitle("Some.Show.S01E0" + episode);
        r.setPushedTime(new Date(System.currentTimeMillis() - pushedAgoMs));
        return r;
    }

    private DownloaderTorrent torrent(String tags, double progress) {
        DownloaderTorrent t = new DownloaderTorrent();
        t.setHash("h");
        t.setName("n");
        t.setProgress(progress);
        t.setTags(tags);
        return t;
    }

    // ---------- 洗版 ----------

    private PtSubscriptionEpisodePlus upgradingEpisode(int id) {
        PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
        ep.setId(id);
        ep.setState("UPGRADING");
        ep.setQuality("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}");
        return ep;
    }

    @Test
    void 洗版下载完成_集转回入库并刷新质量基线() {
        // 这个转换只能由下载完成驱动：Emby 分不出同一集的新旧版本，对账那边恒命中
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 1, "osr-pt-aaa", "DOWNLOADING", 60_000);
        r.setTitle("Show.Name.S01E01.2160p.WEB-DL.HDR10-CHDWEB");
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(upgradingEpisode(501)));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 1.0)));

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor =
                ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService, atLeastOnce()).update(captor.capture(), any(Wrapper.class));
        PtSubscriptionEpisodePlus updated = captor.getAllValues().stream()
                .filter(e -> "IN_LIBRARY".equals(e.getState())).findFirst().orElseThrow();
        // 基线不刷新的话，下一轮扫描仍按旧画像判断，会把刚下好的版本再当成"可升级"，反复洗同一集
        assertTrue(updated.getQuality().contains("2160p"));
        assertEquals("PENDING", updated.getUpgradeState());
    }

    @Test
    void 洗版下载失败_集退回入库而不是缺失() {
        // 退成 MISSING 会让这一集显示成缺失并被 RSS 从头重下一遍，比不洗版还糟
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 1, "osr-pt-aaa", "DOWNLOADING", 20 * 60 * 1000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(upgradingEpisode(501)));

        // 种子在下载器里找不到且已过宽限期 → fail
        service().track(downloader(), List.of());

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor =
                ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService, atLeastOnce()).update(captor.capture(), any(Wrapper.class));
        assertTrue(captor.getAllValues().stream().anyMatch(e -> "IN_LIBRARY".equals(e.getState())));
        assertTrue(captor.getAllValues().stream().noneMatch(e -> "MISSING".equals(e.getState())));
        assertTrue(captor.getAllValues().stream().noneMatch(e -> "BLOCKED".equals(e.getState())));
    }

    @Test
    void 洗版失败不累加失败计数_避免已入库的集被熔断() {
        // fail_count 是"这一集补不到货"的熔断依据，洗版失败并不代表这一集有问题
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 1, "osr-pt-aaa", "DOWNLOADING", 20 * 60 * 1000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(upgradingEpisode(501)));

        service().track(downloader(), List.of());

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor =
                ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService, atLeastOnce()).update(captor.capture(), any(Wrapper.class));
        PtSubscriptionEpisodePlus reverted = captor.getAllValues().stream()
                .filter(e -> "IN_LIBRARY".equals(e.getState())).findFirst().orElseThrow();
        assertNull(reverted.getFailCount());
    }

    @Test
    void 补缺集下载完成_不走洗版收尾_集状态不动() {
        // IN_FLIGHT 的集要等 Emby 对账确认入库，完成这一刻不该动它
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 1, "osr-pt-aaa", "DOWNLOADING", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501)));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 1.0)));

        verify(episodeService, never()).update(any(), any(Wrapper.class));
    }

    // ---------- H&R 保种防护 ----------

    private PtIndexerPlus hrIndexer(int id, Integer seedHours, Double ratio) {
        PtIndexerPlus i = new PtIndexerPlus();
        i.setId(id);
        i.setName("hr-site-" + id);
        i.setHrEnabled("1");
        i.setHrSeedHours(seedHours);
        i.setHrRatio(ratio);
        return i;
    }

    /** 保种中的记录：已 COMPLETED，hr_state=PENDING */
    private PtDownloadRecordPlus seedingRecord(int id, String tag, long sampledSeedSeconds) {
        PtDownloadRecordPlus r = record(id, 1, tag, "COMPLETED", 60_000);
        r.setIndexerId(7);
        r.setHrState("PENDING");
        r.setHrSeedSeconds(sampledSeedSeconds);
        return r;
    }

    private DownloaderTorrent seedingTorrent(String tags, long seedingSeconds, double ratio) {
        DownloaderTorrent t = torrent(tags, 1.0);
        t.setSeedingSeconds(seedingSeconds);
        t.setRatio(ratio);
        return t;
    }

    @Test
    void 完成时来源站点有HR考核_进入保种追踪并提示勿删() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        r.setIndexerId(7);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(indexerService.getById(7)).thenReturn(hrIndexer(7, 72, 1.0));

        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-aaa", 0, 0.0)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService, atLeastOnce()).update(captor.capture(), any(Wrapper.class));
        PtDownloadRecordPlus completed = captor.getAllValues().stream()
                .filter(v -> "COMPLETED".equals(v.getState())).findFirst().orElseThrow();
        assertEquals("PENDING", completed.getHrState());
    }

    @Test
    void 完成时来源站点无HR考核_保种状态保持为空() {
        // hr_state 为 null 表示"不适用"，这批记录不该被 trackSeeding 捞起来空转
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        r.setIndexerId(7);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        PtIndexerPlus noHr = hrIndexer(7, 72, 1.0);
        noHr.setHrEnabled("0");
        when(indexerService.getById(7)).thenReturn(noHr);

        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-aaa", 0, 0.0)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService, atLeastOnce()).update(captor.capture(), any(Wrapper.class));
        PtDownloadRecordPlus completed = captor.getAllValues().stream()
                .filter(v -> "COMPLETED".equals(v.getState())).findFirst().orElseThrow();
        assertNull(completed.getHrState());
    }

    @Test
    void 只开HR开关不填阈值_按未启用处理() {
        // 无从判断"做到什么程度才算达标"，若按启用处理会让种子永远停在保种中并反复提醒
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        r.setIndexerId(7);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(indexerService.getById(7)).thenReturn(hrIndexer(7, 0, 0.0));

        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-aaa", 0, 0.0)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService, atLeastOnce()).update(captor.capture(), any(Wrapper.class));
        PtDownloadRecordPlus completed = captor.getAllValues().stream()
                .filter(v -> "COMPLETED".equals(v.getState())).findFirst().orElseThrow();
        assertNull(completed.getHrState());
    }

    @Test
    void 保种达到时长要求_转达标() {
        when(recordService.listSeedingPending(1)).thenReturn(List.of(seedingRecord(200, "osr-pt-bbb", 100_000)));
        when(indexerService.listByIds(any())).thenReturn(List.of(hrIndexer(7, 72, 0.0)));
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);

        // 72 小时 = 259200 秒
        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-bbb", 260_000, 0.1)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("SATISFIED", captor.getValue().getHrState());
        assertNotNull(captor.getValue().getHrSatisfiedTime());
    }

    @Test
    void 保种达到分享率要求_转达标_时长未满也算() {
        // 站点的通行表述是"做满 N 小时 或 分享率达到 R"，任一满足即解除考核
        when(recordService.listSeedingPending(1)).thenReturn(List.of(seedingRecord(200, "osr-pt-bbb", 100)));
        when(indexerService.listByIds(any())).thenReturn(List.of(hrIndexer(7, 72, 1.0)));
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);

        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-bbb", 100, 1.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("SATISFIED", captor.getValue().getHrState());
    }

    @Test
    void 保种未达标_只落采样值不改状态() {
        when(recordService.listSeedingPending(1)).thenReturn(List.of(seedingRecord(200, "osr-pt-bbb", 100)));
        when(indexerService.listByIds(any())).thenReturn(List.of(hrIndexer(7, 72, 2.0)));
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);

        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-bbb", 3_600, 0.3)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertNull(captor.getValue().getHrState());
        assertEquals(3_600L, captor.getValue().getHrSeedSeconds());
        assertEquals(0.3, captor.getValue().getHrRatio());
    }

    @Test
    void 保种中的种子从下载器消失_转违规() {
        // OSR 自己从不删种，走到这里说明是用户手删或下载器的自动管理清掉了
        when(recordService.listSeedingPending(1)).thenReturn(List.of(seedingRecord(200, "osr-pt-bbb", 100)));
        when(indexerService.listByIds(any())).thenReturn(List.of(hrIndexer(7, 72, 0.0)));
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);

        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-其它", 999_999, 9.9)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("VIOLATED", captor.getValue().getHrState());
    }

    @Test
    void 保种记录的索引器已被删除_按达标收尾而不是永远空转() {
        when(recordService.listSeedingPending(1)).thenReturn(List.of(seedingRecord(200, "osr-pt-bbb", 100)));
        when(indexerService.listByIds(any())).thenReturn(List.of());
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);

        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-bbb", 100, 0.1)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("SATISFIED", captor.getValue().getHrState());
    }

    @Test
    void HR站点的种子被追踪到时_按站点规则下发分享限额() throws Exception {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        r.setIndexerId(7);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(indexerService.getById(7)).thenReturn(hrIndexer(7, 72, 1.5));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);

        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-aaa", 0, 0.0)));

        // 72 小时 = 4320 分钟
        verify(downloaderClient).setShareLimits(any(), eq("h"), eq(1.5), eq(4320L));
    }

    @Test
    void 非HR站点_不擅自改动下载器里的种子设置() throws Exception {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        r.setIndexerId(7);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        PtIndexerPlus noHr = hrIndexer(7, 72, 1.0);
        noHr.setHrEnabled("0");
        when(indexerService.getById(7)).thenReturn(noHr);
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);

        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-aaa", 0, 0.0)));

        verify(downloaderClient, never()).setShareLimits(any(), anyString(), anyDouble(), anyLong());
    }

    @Test
    void 已下发过限额的记录_不重复下发() throws Exception {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        r.setIndexerId(7);
        r.setHrLimitsApplied(true);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(indexerService.getById(7)).thenReturn(hrIndexer(7, 72, 1.0));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);

        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-aaa", 0, 0.0)));

        verify(downloaderClient, never()).setShareLimits(any(), anyString(), anyDouble(), anyLong());
    }

    @Test
    void 下发限额失败_不标记已下发_留待下轮重试() throws Exception {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        r.setIndexerId(7);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(indexerService.getById(7)).thenReturn(hrIndexer(7, 72, 1.0));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        doThrow(new java.io.IOException("boom")).when(downloaderClient)
                .setShareLimits(any(), anyString(), anyDouble(), anyLong());

        // 异常不该冒出来打断整轮追踪
        service().track(downloader(), List.of(seedingTorrent("osr-pt,osr-pt-aaa", 0, 0.0)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService, atLeastOnce()).update(captor.capture(), any(Wrapper.class));
        assertTrue(captor.getAllValues().stream().noneMatch(v -> Boolean.TRUE.equals(v.getHrLimitsApplied())));
    }

    @Test
    void 完成的种子_记录置完成_集状态不动() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        PtDownloaderPlus dl = downloader();

        service().track(dl, List.of(torrent("osr-pt,osr-pt-aaa", 1.0)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("COMPLETED", captor.getValue().getState());
        assertEquals(1.0, captor.getValue().getProgress());
        assertNotNull(captor.getValue().getCompletedTime());
        // 集状态不该被改（等 Emby 对账）
        verify(episodeService, never()).update(any(), any(Wrapper.class));
        // 完成后异步触发一次 STRM 联动同步，用同一个 downloader 引用
        verify(completionSyncTrigger).triggerAsync(eq(r), same(dl));
    }

    @Test
    void 未完成的种子_记录置下载中并同步进度() {
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.35)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).updateById(captor.capture());
        assertEquals("DOWNLOADING", captor.getValue().getState());
        assertEquals(0.35, captor.getValue().getProgress());
    }

    @Test
    void 已在下载中_进度变化仍持续写入() {
        // 早前 markDownloading 命中"状态已相同则跳过"的分支会导致进度停在首次写入的值，
        // 这里的记录已经是 DOWNLOADING，验证进度更新到最新值而不是被短路。
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 120_000);
        r.setProgress(0.1);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.8)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).updateById(captor.capture());
        assertEquals(0.8, captor.getValue().getProgress());
    }

    @Test
    void 找不到种子且推送已超宽限期_记录置失败且集回退缺失() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        // 宽限期 10 分钟，这条推送了 20 分钟还找不到
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("FAILED", captor.getValue().getState());
        assertEquals("TORRENT_NOT_FOUND", captor.getValue().getFailReasonCode());
        // 关联集回退 MISSING
        verify(episodeService).update(any(), any(Wrapper.class));
    }

    @Test
    void 找不到种子但推送未超宽限期_本轮跳过() {
        // 刚推送 1 分钟，qB 可能还在解析元数据
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

        verify(recordService, never()).update(any(), any(Wrapper.class));
        verify(recordService, never()).updateById(any());
        verify(episodeService, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void 季包失败_关联的多个集全部回退() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501), episodeRow(502)));

        service().track(downloader(), List.of(torrent("osr-pt", 0.5)));

        // 两个集都回退
        verify(episodeService, times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    void 已完成的记录不重复处理() {
        // list 只查 PUSHED/DOWNLOADING，COMPLETED 的不在结果里——用空列表模拟
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of());

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-done", 1.0)));

        verify(recordService, never()).updateById(any());
    }

    @Test
    void 无在途记录_不做任何事() {
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of());

        service().track(downloader(), List.of());

        verify(recordService, never()).updateById(any());
    }

    @Test
    void 种子仍在下载器但超僵尸超时_判失败并回退集() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 25L * 3600_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("FAILED", captor.getValue().getState());
        assertEquals("ZOMBIE_TIMEOUT", captor.getValue().getFailReasonCode());
        verify(episodeService).update(any(), any(Wrapper.class));
    }

    @Test
    void 种子仍在下载器且未超僵尸超时_保持下载中() {
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "PUSHED", 3600_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).updateById(captor.capture());
        assertEquals("DOWNLOADING", captor.getValue().getState());
        verify(episodeService, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void 完成但记录已被并发置终态_不重复通知() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(false);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 1.0)));
            tg.verify(() -> TgHelper.sendMsg(any(), anyString(), any()), never());
        }
        // 已被并发轮次处理过，不该重复触发 STRM 联动同步
        verify(completionSyncTrigger, never()).triggerAsync(any(), any());
    }

    @Test
    void 失败但记录已被并发置终态_不重复通知() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(false);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));
            tg.verify(() -> TgHelper.sendMsg(any(), anyString(), any()), never());
        }
    }

    private PtSubscriptionEpisodePlus episodeRow(int id) {
        return episodeRow(id, 0);
    }

    private PtSubscriptionEpisodePlus episodeRow(int id, int failCount) {
        PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
        ep.setId(id);
        ep.setState("IN_FLIGHT");
        ep.setFailCount(failCount);
        return ep;
    }

    private PtSubscriptionEpisodePlus episodeRow(int id, int failCount, int episode) {
        PtSubscriptionEpisodePlus ep = episodeRow(id, failCount);
        ep.setEpisode(episode);
        return ep;
    }

    private DownloaderTorrentFile file(int index, String name) {
        DownloaderTorrentFile f = new DownloaderTorrentFile();
        f.setIndex(index);
        f.setName(name);
        return f;
    }

    // ---------- 按目标集数过滤季包文件 ----------

    @Test
    void 季包命中_只排除非目标集数的文件() throws Exception {
        // 订阅只缺 2、3 集，种子是 S01E01-04 的季包
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(
                List.of(episodeRow(501, 0, 2), episodeRow(502, 0, 3)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of(
                file(0, "Show.Name.S01E01.1080p.mkv"),
                file(1, "Show.Name.S01E02.1080p.mkv"),
                file(2, "Show.Name.S01E03.1080p.mkv"),
                file(3, "Show.Name.S01E04.1080p.mkv"),
                file(4, "Show.Name.S01.nfo")));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.1)));

        ArgumentCaptor<Set<Integer>> captor = ArgumentCaptor.forClass(Set.class);
        verify(downloaderClient).excludeFiles(any(), eq("h"), captor.capture());
        assertEquals(Set.of(0, 3), captor.getValue());
        ArgumentCaptor<PtDownloadRecordPlus> recordCaptor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService, times(2)).updateById(recordCaptor.capture());
        assertEquals(true, recordCaptor.getAllValues().get(0).getFilesSelected());
    }

    @Test
    void 种子元数据未就绪_不排除文件也不标记selected() throws Exception {
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501, 0, 2)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of());

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.1)));

        verify(downloaderClient, never()).excludeFiles(any(), any(), any());
        ArgumentCaptor<PtDownloadRecordPlus> recordCaptor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService, times(1)).updateById(recordCaptor.capture());
        assertNull(recordCaptor.getValue().getFilesSelected());
    }

    @Test
    void 已完成文件过滤的记录_不重复调用下载器() {
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 60_000);
        r.setFilesSelected(true);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.1)));

        verify(downloaderClientFactory, never()).get(any());
        verify(episodeService, never()).list(any(Wrapper.class));
    }

    // ---------- 季包认领对账（多占的集退回缺失） ----------

    private PtSubscriptionPlus tvSub(int id) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setMediaType("TV");
        sub.setTitle("Some Show");
        sub.setSeason(1);
        sub.setTotalEpisodes(50);
        return sub;
    }

    @Test
    void 季包实际只含部分集_多占的集退回缺失且不累加失败次数() throws Exception {
        // 用户实测场景：50 集的番分成上/中/下发布，先来的"S01 季包"只含前 3 集，
        // 但推送时占位了当时全部缺失集。多占的那些集下完也不会入库，Emby 对账只升不降，
        // 补搜与 RSS 又只认 MISSING —— 不在这里退回去就永久卡在在途
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(
                episodeRow(501, 0, 1), episodeRow(502, 0, 2), episodeRow(503, 0, 3),
                episodeRow(504, 0, 4), episodeRow(505, 0, 5)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of(
                file(0, "Show.Name.S01E01.1080p.mkv"),
                file(1, "Show.Name.S01E02.1080p.mkv"),
                file(2, "Show.Name.S01E03.1080p.mkv")));
        when(episodeService.update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class))).thenReturn(true);

        DownloadTrackService svc = service();
        when(subscriptionService.listByIds(any())).thenReturn(List.of(tvSub(10)));
        svc.track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.1)));

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        // 3 次写：1 次批量给包内确实存在的 1-3 集打确认标记，2 次把多占的 4、5 集退回缺失
        verify(episodeService, times(3)).update(captor.capture(), any(Wrapper.class));
        List<PtSubscriptionEpisodePlus> released = captor.getAllValues().stream()
                .filter(ep -> ep.getState() != null).toList();
        assertEquals(2, released.size());
        // 包里没有的第 4、5 集退回缺失；1-3 集是真在下载，不动
        assertTrue(released.stream().allMatch(ep -> "MISSING".equals(ep.getState())));
        // 不是"补不到货"而是占位范围估错了，累加 fail_count 会把正常的集熔断成 BLOCKED
        assertTrue(released.stream().allMatch(ep -> ep.getFailCount() == null));
        // 包内真有的集必须留下确认标记，否则上传慢时会被 12 小时后的清扫误判成卡死重下
        assertTrue(captor.getAllValues().stream().anyMatch(ep -> "1".equals(ep.getFileConfirmed())));
    }

    @Test
    void 秒下的种子_完成前补跑一次对账把集标成已确认() throws Exception {
        // trySelectFiles 只在"还在下载"分支跑。秒下、或本地已做种被重新加回的种子，
        // 第一次轮询看见它就已经 completed，文件列表一次都没读过 —— 那样的集一旦上传慢，
        // 12 小时后会被清扫误判成卡死重下，正是 file_confirmed 要防的事
        PtDownloadRecordPlus r = record(100, 1, "osr-pt-fast", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501, 0, 1)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of(
                file(0, "Show.Name.S01E01.1080p.mkv")));

        DownloadTrackService svc = service();
        when(subscriptionService.listByIds(any())).thenReturn(List.of(tvSub(10)));
        // progress=1.0 → 第一次轮询就是完成态，走不到 trySelectFiles
        svc.track(downloader(), List.of(torrent("osr-pt,osr-pt-fast", 1.0)));

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService, atLeastOnce()).update(captor.capture(), any(Wrapper.class));
        assertTrue(captor.getAllValues().stream().anyMatch(ep -> "1".equals(ep.getFileConfirmed())));
        // 完成前的补对账绝不能挡住 complete()：下载确实成功了
        verify(recordService, atLeastOnce()).update(any(PtDownloadRecordPlus.class), any(Wrapper.class));
    }

    @Test
    void 完成前补对账读文件列表失败_不影响判完成() throws Exception {
        // 这只是让日后清扫更准的补充信息，绝不能让它挡住下载完成的通知与 H&R 追踪
        PtDownloadRecordPlus r = record(100, 1, "osr-pt-fast", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501, 0, 1)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenThrow(new java.io.IOException("下载器 API 故障"));

        DownloadTrackService svc = service();
        when(subscriptionService.listByIds(any())).thenReturn(List.of(tvSub(10)));
        svc.track(downloader(), List.of(torrent("osr-pt,osr-pt-fast", 1.0)));

        verify(recordService, atLeastOnce()).update(any(PtDownloadRecordPlus.class), any(Wrapper.class));
    }

    @Test
    void 种子文件一个集号都解析不出_不做认领对账() throws Exception {
        // 整季压成单文件、或命名奇特时 actualEpisodes 为空。那不是"包里没有这些集"的证据，
        // 顺着走会把刚推送的整批集全退回缺失
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(
                episodeRow(501, 0, 1), episodeRow(502, 0, 2)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of(
                file(0, "Some.Show.Complete.Season.mkv")));

        DownloadTrackService svc = service();
        when(subscriptionService.listByIds(any())).thenReturn(List.of(tvSub(10)));
        svc.track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.1)));

        verify(episodeService, never()).update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class));
    }

    @Test
    void 电影订阅_不做认领对账() throws Exception {
        // 电影的集号是哨兵值 0，而文件名里随便一个数字都可能被解析成集号，
        // 比对必然假阳性，会把刚推送的电影退回缺失
        PtDownloadRecordPlus r = record(100, 0, "osr-pt-movie", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501, 0, 0)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of(
                file(0, "Some.Movie.2019.Part2.1080p.mkv")));

        PtSubscriptionPlus movie = tvSub(10);
        movie.setMediaType("MOVIE");
        DownloadTrackService svc = service();
        when(subscriptionService.listByIds(any())).thenReturn(List.of(movie));
        svc.track(downloader(), List.of(torrent("osr-pt,osr-pt-movie", 0.1)));

        verify(episodeService, never()).update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class));
    }

    @Test
    void 洗版包里没有目标集_集退回入库而不是缺失() throws Exception {
        // 洗版占位的是 UPGRADING，旧文件一直在库里。退成 MISSING 会让这一集显示成缺失
        // 并被从头重下，比不洗版还糟
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 7, "osr-pt-up", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        PtSubscriptionEpisodePlus upgrading = episodeRow(501, 0, 7);
        upgrading.setState("UPGRADING");
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(upgrading));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        // 文件解析出的是第 8 集，与目标第 7 集对不上
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of(
                file(0, "Show.Name.S01E08.2160p.mkv")));

        DownloadTrackService svc = service();
        when(subscriptionService.listByIds(any())).thenReturn(List.of(tvSub(10)));
        svc.track(downloader(), List.of(torrent("osr-pt,osr-pt-up", 0.1)));

        // 全部文件都不是目标集，排除指令一条都不该下发（否则 qB 拿到一个 0 字节的空任务）
        verify(downloaderClient, never()).excludeFiles(any(), any(), any());
        ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService).update(captor.capture(), any(Wrapper.class));
        assertEquals("IN_LIBRARY", captor.getValue().getState());
        assertNull(captor.getValue().getFailCount());
    }

    // ---------- 包内没有任何目标集：中止而不是空跑 ----------

    @Test
    void 包内没有任何目标集_不下发排除指令并直接判失败() throws Exception {
        // 用户实测场景：季包里那几集其实都已入库（或包只含别的段落），目标集一个都不在包里。
        // 照原逻辑会把全部视频文件 prio=0 发给 qB，任务 0 字节挂在下载器里占着并发名额，
        // 直到僵尸超时才收尾
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(
                episodeRow(501, 0, 11), episodeRow(502, 0, 12)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of(
                file(0, "Show.Name.S01E01.1080p.mkv"),
                file(1, "Show.Name.S01E02.1080p.mkv")));

        DownloadTrackService svc = service();
        when(subscriptionService.listByIds(any())).thenReturn(List.of(tvSub(10)));
        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            svc.track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.1)));
            tg.verify(() -> TgHelper.sendMsg(any(), argThat(m -> m.contains("不含任何目标集")), any()));
        }

        verify(downloaderClient, never()).excludeFiles(any(), any(), any());
        // 记录判失败，失败码不可重试——这个包与本订阅当前要补的集确实无关
        ArgumentCaptor<PtDownloadRecordPlus> rec = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(rec.capture(), any(Wrapper.class));
        assertEquals("FAILED", rec.getValue().getState());
        assertEquals("NO_TARGET_EPISODE", rec.getValue().getFailReasonCode());
        // 占位集退回缺失，但不累加 fail_count：占位范围估错了，不是这一集补不到货
        ArgumentCaptor<PtSubscriptionEpisodePlus> eps = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService, times(2)).update(eps.capture(), any(Wrapper.class));
        assertTrue(eps.getAllValues().stream().allMatch(e -> "MISSING".equals(e.getState())));
        assertTrue(eps.getAllValues().stream().allMatch(e -> e.getFailCount() == null));
        // 已是终态，绝不能再往下走 markDownloading（无条件 updateById，会把它复活成 DOWNLOADING）
        verify(recordService, never()).updateById(any());
    }

    // ---------- 暂停加种：选完文件才启动 ----------

    @Test
    void 文件选完后启动种子_且赶在标记selected之前() throws Exception {
        // 多集包以暂停态推送，不启动它就永远不会开始下载
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501, 0, 2)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of(
                file(0, "Show.Name.S01E01.1080p.mkv"),
                file(1, "Show.Name.S01E02.1080p.mkv")));

        DownloadTrackService svc = service();
        when(subscriptionService.listByIds(any())).thenReturn(List.of(tvSub(10)));
        svc.track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.1)));

        InOrder order = inOrder(downloaderClient, recordService);
        order.verify(downloaderClient).excludeFiles(any(), eq("h"), any());
        order.verify(downloaderClient).resumeTorrent(any(), eq("h"));
        // 启动失败必须留给下一轮重试，所以它得排在 markFilesSelected 之前——
        // 一旦标了 selected 就再也不会进 trySelectFiles，暂停的种子将永远没人启动。
        // 用 atLeastOnce 而不是按内容匹配：markFilesSelected 与 markDownloading 传的是同一个
        // record 实例，Mockito 验证时读到的是它被改过的最终状态，两次调用无法用 argThat 区分
        order.verify(recordService, atLeastOnce()).updateById(any());
    }

    @Test
    void 启动种子失败_不标记selected留待下一轮重试() throws Exception {
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501, 0, 2)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of(
                file(0, "Show.Name.S01E02.1080p.mkv")));
        doThrow(new IOException("boom")).when(downloaderClient).resumeTorrent(any(), any());

        DownloadTrackService svc = service();
        when(subscriptionService.listByIds(any())).thenReturn(List.of(tvSub(10)));
        svc.track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.1)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService, atLeastOnce()).updateById(captor.capture());
        assertTrue(captor.getAllValues().stream().noneMatch(rec -> Boolean.TRUE.equals(rec.getFilesSelected())));
    }

    @Test
    void 暂停中的包未选出文件_不标记下载中() throws Exception {
        // 暂停态的种子一个字节都没下，标成 DOWNLOADING 会在前端显示"下载中 0%"
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501, 0, 2)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        // 元数据还没解析出来
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of());

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.0)));

        verify(recordService, never()).updateById(any());
    }

    @Test
    void 元数据超时且零进度_判失败并从下载器移除() throws Exception {
        // 暂停的种子只有选完文件才会启动，元数据一直解析不出来就永远没人启动它
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 31 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501, 0, 2)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of());

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.0)));

        ArgumentCaptor<PtDownloadRecordPlus> rec = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(rec.capture(), any(Wrapper.class));
        assertEquals("FAILED", rec.getValue().getState());
        assertEquals("METADATA_TIMEOUT", rec.getValue().getFailReasonCode());
        verify(downloaderClient).deleteTorrent(any(), eq("h"), eq(true));
    }

    @Test
    void 元数据超时但已有下载进度_不中止也不删种() throws Exception {
        // 文件选不出来也可能是下载器 API 临时故障，而种子本身下得好好的。
        // 只按时间判会把一个正常下载的种子删掉，比不做这个兜底糟得多
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 31 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501, 0, 2)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenThrow(new IOException("下载器暂时不可用"));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.4)));

        verify(downloaderClient, never()).deleteTorrent(any(), any(), anyBoolean());
        verify(recordService, never()).update(any(PtDownloadRecordPlus.class), any(Wrapper.class));
        // 有进度就照常推进下载中，与改造前的行为一致
        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).updateById(captor.capture());
        assertEquals("DOWNLOADING", captor.getValue().getState());
    }

    @Test
    void 已完成或已做种的种子_绝不删除() throws Exception {
        // removeUselessTorrent 是「OSR 从不删种」唯一的例外，边界必须可证明：
        // 已做种的种子处在 H&R 考核期内，删它等于亲手制造一次记过
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 31 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(501, 0, 2)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of());
        DownloaderTorrent seeding = torrent("osr-pt,osr-pt-pack", 0.0);
        seeding.setSeedingSeconds(3600);

        service().track(downloader(), List.of(seeding));

        verify(downloaderClient, never()).deleteTorrent(any(), any(), anyBoolean());
    }

    @Test
    void 包内含部分目标集_照常排除其余文件不中止() throws Exception {
        // 有交集就是正常的季包命中，不该被新增的中止逻辑误伤
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(
                episodeRow(501, 0, 2), episodeRow(502, 0, 9)));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(downloaderClient.listFiles(any(), eq("h"))).thenReturn(List.of(
                file(0, "Show.Name.S01E01.1080p.mkv"),
                file(1, "Show.Name.S01E02.1080p.mkv")));
        when(episodeService.update(any(PtSubscriptionEpisodePlus.class), any(Wrapper.class))).thenReturn(true);

        DownloadTrackService svc = service();
        when(subscriptionService.listByIds(any())).thenReturn(List.of(tvSub(10)));
        svc.track(downloader(), List.of(torrent("osr-pt,osr-pt-pack", 0.1)));

        verify(downloaderClient).excludeFiles(any(), eq("h"), eq(Set.of(0)));
        verify(recordService, never()).update(any(PtDownloadRecordPlus.class), any(Wrapper.class));
    }

    // ---------- 失败重试熔断 ----------

    @Test
    void 连续失败未达阈值_回退MISSING并递增失败计数() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500, 1)));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService).update(captor.capture(), any(Wrapper.class));
        assertEquals("MISSING", captor.getValue().getState());
        assertEquals(2, captor.getValue().getFailCount());
    }

    @Test
    void 连续失败达到阈值_集转BLOCKED不再回退MISSING_并额外告警() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        // 已失败 2 次，本次是第 3 次，达到默认阈值 3
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500, 2)));

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

            ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
            verify(episodeService).update(captor.capture(), any(Wrapper.class));
            assertEquals("BLOCKED", captor.getValue().getState());
            assertEquals(3, captor.getValue().getFailCount());
            tg.verify(() -> TgHelper.sendMsg(any(), argThat(m -> m.contains("停止自动重试")), any()));
        }
    }

    @Test
    void 季包失败_部分集达到阈值_只有对应集转BLOCKED其余仍MISSING() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, -1, "osr-pt-pack", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(
                List.of(episodeRow(501, 2), episodeRow(502, 0)));

        service().track(downloader(), List.of(torrent("osr-pt", 0.5)));

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService, times(2)).update(captor.capture(), any(Wrapper.class));
        List<PtSubscriptionEpisodePlus> updates = captor.getAllValues();
        assertEquals("BLOCKED", updates.get(0).getState());
        assertEquals("MISSING", updates.get(1).getState());
    }

    // ---------- 僵尸超时：全局默认值 + 订阅级覆盖 ----------

    @Test
    void 订阅设置僵尸超时覆盖_按覆盖值判定僵尸超时() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        // 覆盖为 1 小时，记录已推送 90 分钟——超过覆盖值 1 小时，但远不到全局默认 24 小时
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 90 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(10);
        sub.setDownloadOverride("{\"zombieTimeoutHours\": 1}");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("FAILED", captor.getValue().getState());
    }

    @Test
    void 订阅覆盖非法JSON_回退全局默认值不抛异常() {
        // 记录已推送 90 分钟：若非法覆盖被误当成短超时会判失败；正确回退 24 小时默认值应仍是下载中
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 90 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(10);
        sub.setDownloadOverride("{");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).updateById(captor.capture());
        assertEquals("DOWNLOADING", captor.getValue().getState());
    }

    @Test
    void 订阅覆盖僵尸超时为零_视为无效回退全局默认值() {
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 90 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(10);
        sub.setDownloadOverride("{\"zombieTimeoutHours\": 0}");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).updateById(captor.capture());
        assertEquals("DOWNLOADING", captor.getValue().getState());
    }

    @Test
    void 订阅已删除_listByIds查不到_回退全局默认值() {
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 90 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(subscriptionService.listByIds(any())).thenReturn(List.of()); // 订阅已删除

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).updateById(captor.capture());
        assertEquals("DOWNLOADING", captor.getValue().getState());
    }

    // ---------- WebSocket 状态推送 ----------

    @Test
    void 下载中更新_推送WebSocket下载事件() {
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.35)));

            ws.verify(() -> PtStatusWebSocket.pushDownloadEvent(same(r), eq("DOWNLOADING"), eq(0.35), isNull()));
        }
    }

    @Test
    void 完成后_推送WebSocket下载事件() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        PtDownloaderPlus dl = downloader();

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            service().track(dl, List.of(torrent("osr-pt,osr-pt-aaa", 1.0)));

            ws.verify(() -> PtStatusWebSocket.pushDownloadEvent(same(r), eq("COMPLETED"), eq(1.0), isNull()));
        }
    }

    @Test
    void 完成但记录已被并发置终态_不推送WebSocket事件() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(false);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class);
             MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 1.0)));

            ws.verify(() -> PtStatusWebSocket.pushDownloadEvent(any(), any(), any(), any()), never());
        }
    }

    @Test
    void 失败后_推送WebSocket下载事件() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

            ws.verify(() -> PtStatusWebSocket.pushDownloadEvent(same(r), eq("FAILED"), isNull(),
                    eq("下载器中已找不到该种子（可能被删除或元数据解析失败）")));
        }
    }

    @Test
    void 失败但记录已被并发置终态_不推送WebSocket事件() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(false);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

            ws.verify(() -> PtStatusWebSocket.pushDownloadEvent(any(), any(), any(), any()), never());
        }
    }
}
