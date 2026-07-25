package com.ruoyi.openliststrm.pt.task;

import com.ruoyi.common.core.domain.PageResult;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.subscription.SearchSupplementService;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import com.ruoyi.openliststrm.pt.task.dto.BatchRetryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DownloadRecordAdminServiceTest {

    @Mock private IPtDownloadRecordPlusService recordService;
    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtIndexerPlusService indexerService;
    @Mock private IPtDownloaderPlusService downloaderService;
    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    @Mock private SearchSupplementService searchSupplementService;

    private DownloadRecordAdminService service() {
        return new DownloadRecordAdminService(recordService, subscriptionService, indexerService,
                downloaderService, episodeService, searchSupplementService);
    }

    private PtDownloadRecordPlus record(int id, int subId, int episode, String state, Integer indexerId, Integer downloaderId) {
        PtDownloadRecordPlus r = new PtDownloadRecordPlus();
        r.setId(id);
        r.setSubId(subId);
        r.setEpisode(episode);
        r.setState(state);
        r.setIndexerId(indexerId);
        r.setDownloaderId(downloaderId);
        r.setTitle("Some.Show.S01E05");
        return r;
    }

    private PtSubscriptionPlus tvSub(int id, String title, int season, String status) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setTitle(title);
        sub.setMediaType("TV");
        sub.setSeason(season);
        sub.setStatus(status);
        return sub;
    }

    private PtSubscriptionPlus movieSub(int id, String title, String status) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setTitle(title);
        sub.setMediaType("MOVIE");
        sub.setSeason(0);
        sub.setStatus(status);
        return sub;
    }

    // ---------- enrich ----------

    @Test
    void enrich_补上订阅索引器下载器的展示名与集号标签() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        when(subscriptionService.listByIds(List.of(10))).thenReturn(List.of(tvSub(10, "某剧", 1, "ACTIVE")));
        PtIndexerPlus indexer = new PtIndexerPlus();
        indexer.setId(20);
        indexer.setName("站点A");
        when(indexerService.listByIds(List.of(20))).thenReturn(List.of(indexer));
        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(30);
        downloader.setName("下载器A");
        when(downloaderService.listByIds(List.of(30))).thenReturn(List.of(downloader));

        var result = service().enrich(PageResult.of(List.of(r), 1, 1, 10));

        var view = result.getRecords().get(0);
        assertEquals("某剧", view.getSubTitle());
        assertEquals("S01E05", view.getEpisodeLabel());
        assertEquals("站点A", view.getIndexerName());
        assertEquals("下载器A", view.getDownloaderName());
    }

    @Test
    void enrich_关联对象已删除时名称为null不报错() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        when(subscriptionService.listByIds(List.of(10))).thenReturn(List.of());
        when(indexerService.listByIds(List.of(20))).thenReturn(List.of());
        when(downloaderService.listByIds(List.of(30))).thenReturn(List.of());

        var result = service().enrich(PageResult.of(List.of(r), 1, 1, 10));

        var view = result.getRecords().get(0);
        assertEquals(null, view.getSubTitle());
        assertEquals(null, view.getIndexerName());
        assertEquals(null, view.getDownloaderName());
    }

    @Test
    void enrich_季包集号标签() {
        PtDownloadRecordPlus r = record(1, 10, -1, "COMPLETED", 20, 30);
        when(subscriptionService.listByIds(List.of(10))).thenReturn(List.of(tvSub(10, "某剧", 2, "ACTIVE")));

        var result = service().enrich(PageResult.of(List.of(r), 1, 1, 10));

        assertEquals("S02 季包", result.getRecords().get(0).getEpisodeLabel());
    }

    @Test
    void enrich_空列表直接返回不查库() {
        var result = service().enrich(PageResult.of(List.of(), 0, 1, 10));
        assertEquals(0, result.getRecords().size());
    }

    @Test
    void enrich_透传失败原因分类字段() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        r.setFailReasonCode("ZOMBIE_TIMEOUT");
        when(subscriptionService.listByIds(List.of(10))).thenReturn(List.of(tvSub(10, "某剧", 1, "ACTIVE")));
        when(indexerService.listByIds(List.of(20))).thenReturn(List.of());
        when(downloaderService.listByIds(List.of(30))).thenReturn(List.of());

        var result = service().enrich(PageResult.of(List.of(r), 1, 1, 10));

        assertEquals("ZOMBIE_TIMEOUT", result.getRecords().get(0).getFailReasonCode());
    }

    // ---------- retry ----------

    @Test
    void retry_失败记录_按剧集拼关键词发起搜索补集() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        SupplementResult expected = new SupplementResult(true, 3);
        when(searchSupplementService.supplement(eq(10), eq(5), eq("某剧 S01E05"))).thenReturn(expected);

        SupplementResult actual = service().retry(1);

        assertEquals(expected, actual);
    }

    @Test
    void retry_电影订阅_关键词只用标题() {
        PtDownloadRecordPlus r = record(1, 10, 0, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(movieSub(10, "某电影", "ACTIVE"));
        when(searchSupplementService.supplement(eq(10), eq(0), eq("某电影"))).thenReturn(new SupplementResult(true, 1));

        service().retry(1);
        // 未抛异常即说明关键词匹配上了 mock 的 eq() 断言
    }

    @Test
    void retry_季包_关键词带季号不带集号() {
        PtDownloadRecordPlus r = record(1, 10, -1, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 3, "ACTIVE"));
        when(searchSupplementService.supplement(eq(10), eq(-1), eq("某剧 S03"))).thenReturn(new SupplementResult(false, 0));

        service().retry(1);
    }

    @Test
    void retry_记录不存在_抛异常() {
        when(recordService.getById(anyInt())).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service().retry(999));
    }

    @Test
    void retry_非失败状态_抛异常() {
        PtDownloadRecordPlus r = record(1, 10, 5, "DOWNLOADING", 20, 30);
        when(recordService.getById(1)).thenReturn(r);

        assertThrows(IllegalArgumentException.class, () -> service().retry(1));
    }

    @Test
    void retry_订阅不存在_抛异常() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service().retry(1));
    }

    @Test
    void retry_订阅未在订阅中_抛异常() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "PAUSED"));

        assertThrows(IllegalArgumentException.class, () -> service().retry(1));
    }

    // ---------- retry 时重置 BLOCKED 集 ----------

    private PtSubscriptionEpisodePlus blockedEpisode(int id, int episode) {
        PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
        ep.setId(id);
        ep.setEpisode(episode);
        ep.setState("BLOCKED");
        ep.setFailCount(3);
        return ep;
    }

    @Test
    void retry_普通集已熔断BLOCKED_重置回MISSING并清零失败计数再搜索() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(blockedEpisode(500, 5)));
        when(searchSupplementService.supplement(eq(10), eq(5), eq("某剧 S01E05")))
                .thenReturn(new SupplementResult(true, 1));

        service().retry(1);

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService).update(captor.capture(), any(Wrapper.class));
        assertEquals("MISSING", captor.getValue().getState());
        assertEquals(0, captor.getValue().getFailCount());
    }

    @Test
    void retry_季包重试_清空该订阅下所有BLOCKED集() {
        PtDownloadRecordPlus r = record(1, 10, -1, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(episodeService.list(any(Wrapper.class))).thenReturn(
                List.of(blockedEpisode(501, 1), blockedEpisode(502, 2)));
        when(searchSupplementService.supplement(eq(10), eq(-1), eq("某剧 S01")))
                .thenReturn(new SupplementResult(false, 0));

        service().retry(1);

        verify(episodeService, org.mockito.Mockito.times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    void retry_没有BLOCKED集_不触发重置更新() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of());
        when(searchSupplementService.supplement(eq(10), eq(5), eq("某剧 S01E05")))
                .thenReturn(new SupplementResult(true, 1));

        service().retry(1);

        verify(episodeService, never()).update(any(), any(Wrapper.class));
    }

    // ---------- retryBatch ----------

    @Test
    void retryBatch_全部命中_pushedCount等于总数() {
        PtDownloadRecordPlus r1 = record(1, 10, 5, "FAILED", 20, 30);
        PtDownloadRecordPlus r2 = record(2, 10, 6, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r1);
        when(recordService.getById(2)).thenReturn(r2);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of());
        when(searchSupplementService.supplement(eq(10), eq(5), eq("某剧 S01E05")))
                .thenReturn(new SupplementResult(true, 1));
        when(searchSupplementService.supplement(eq(10), eq(6), eq("某剧 S01E06")))
                .thenReturn(new SupplementResult(true, 1));

        BatchRetryResult result = service().retryBatch(List.of(1, 2));

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getPushedCount());
        assertEquals(0, result.getSkippedCount());
    }

    @Test
    void retryBatch_一条不是FAILED状态_计入skipped不影响其余() {
        PtDownloadRecordPlus r1 = record(1, 10, 5, "FAILED", 20, 30);
        PtDownloadRecordPlus r2 = record(2, 10, 6, "DOWNLOADING", 20, 30);
        when(recordService.getById(1)).thenReturn(r1);
        when(recordService.getById(2)).thenReturn(r2);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of());
        when(searchSupplementService.supplement(eq(10), eq(5), eq("某剧 S01E05")))
                .thenReturn(new SupplementResult(true, 1));

        BatchRetryResult result = service().retryBatch(List.of(1, 2));

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getPushedCount());
        assertEquals(1, result.getSkippedCount());
        verify(searchSupplementService, times(1)).supplement(anyInt(), anyInt(), anyString());
    }

    @Test
    void retryBatch_搜到0候选_计入skipped而非异常路径() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of());
        when(searchSupplementService.supplement(eq(10), eq(5), eq("某剧 S01E05")))
                .thenReturn(new SupplementResult(false, 0));

        BatchRetryResult result = service().retryBatch(List.of(1));

        assertEquals(1, result.getTotal());
        assertEquals(0, result.getPushedCount());
        assertEquals(1, result.getSkippedCount());
    }
}
