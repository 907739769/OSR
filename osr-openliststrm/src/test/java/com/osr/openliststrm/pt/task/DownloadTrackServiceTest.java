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
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
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
            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
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
            tg.verify(() -> TgHelper.sendMsg(anyString()), never());
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
            tg.verify(() -> TgHelper.sendMsg(argThat(m -> m.contains("停止自动重试"))));
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
