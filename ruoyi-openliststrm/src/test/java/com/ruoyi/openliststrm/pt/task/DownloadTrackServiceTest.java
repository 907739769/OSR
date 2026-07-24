package com.ruoyi.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ruoyi.openliststrm.helper.TgHelper;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.downloader.model.DownloaderTorrent;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
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

    private DownloadTrackService service() {
        // 默认桩：查不到任何订阅（对应"订阅已删除"分支），使全部现有用例回退全局默认值 24 小时，
        // 与改造前的行为保持一致，不用逐个用例改断言。
        when(subscriptionService.listByIds(any())).thenReturn(List.of());
        return new DownloadTrackService(recordService, episodeService, completionSyncTrigger, subscriptionService, 3, 24);
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
}
