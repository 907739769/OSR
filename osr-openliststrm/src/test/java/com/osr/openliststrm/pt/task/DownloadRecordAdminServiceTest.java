package com.osr.openliststrm.pt.task;

import com.osr.common.core.domain.PageResult;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.dto.SupplementResult;
import com.osr.openliststrm.pt.task.dto.BatchRetryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    @Mock private IPtTorrentBlacklistPlusService blacklistService;

    private DownloadRecordAdminService service() {
        return new DownloadRecordAdminService(recordService, subscriptionService, indexerService,
                downloaderService, episodeService, searchSupplementService, blacklistService);
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
    void enrich_区间匹配集号标签显示为区间而非单集() {
        PtDownloadRecordPlus r = record(1, 10, 1, "COMPLETED", 20, 30);
        r.setEpisodeEnd(2);
        when(subscriptionService.listByIds(List.of(10))).thenReturn(List.of(tvSub(10, "某剧", 2, "ACTIVE")));

        var result = service().enrich(PageResult.of(List.of(r), 1, 1, 10));

        assertEquals("S02E01-E02", result.getRecords().get(0).getEpisodeLabel());
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

    @Test
    void retry_重置BLOCKED集_一条条件更新_置MISSING并清零失败计数() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(searchSupplementService.supplement(eq(10), eq(5), eq("某剧 S01E05")))
                .thenReturn(new SupplementResult(true, 1));

        service().retry(1);

        ArgumentCaptor<PtSubscriptionEpisodePlus> captor = ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        ArgumentCaptor<Wrapper> where = ArgumentCaptor.forClass(Wrapper.class);
        verify(episodeService, times(1)).update(captor.capture(), where.capture());
        assertEquals("MISSING", captor.getValue().getState());
        assertEquals(0, captor.getValue().getFailCount());
        String sql = where.getValue().getSqlSegment();
        assertTrue(sql.contains("state"), sql);
        assertTrue(sql.contains("episode ="), sql);
        // 不再先 list 再逐条改
        verify(episodeService, never()).list(any(Wrapper.class));
    }

    @Test
    void retry_季包重试_不限集号清空该订阅下所有BLOCKED集() {
        PtDownloadRecordPlus r = record(1, 10, -1, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(searchSupplementService.supplement(eq(10), eq(-1), eq("某剧 S01")))
                .thenReturn(new SupplementResult(false, 0));

        service().retry(1);

        ArgumentCaptor<Wrapper> where = ArgumentCaptor.forClass(Wrapper.class);
        verify(episodeService, times(1)).update(any(), where.capture());
        assertFalse(where.getValue().getSqlSegment().contains("episode"), where.getValue().getSqlSegment());
    }

    @Test
    void retry_区间匹配记录_按区间重置BLOCKED集() {
        // 原下载记录覆盖 S01E01-E02（episode=1, episodeEnd=2），重试时第 2 集也该被解除熔断，不能只重置第 1 集
        PtDownloadRecordPlus r = record(1, 10, 1, "FAILED", 20, 30);
        r.setEpisodeEnd(2);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(searchSupplementService.supplement(eq(10), eq(1), eq("某剧 S01E01")))
                .thenReturn(new SupplementResult(true, 1));

        service().retry(1);

        ArgumentCaptor<Wrapper> where = ArgumentCaptor.forClass(Wrapper.class);
        verify(episodeService, times(1)).update(any(), where.capture());
        String sql = where.getValue().getSqlSegment();
        assertTrue(sql.contains("episode >=") && sql.contains("episode <="), sql);
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

    // ---------- 归属 ----------

    private PtSubscriptionPlus owned(int id, Long owner) {
        PtSubscriptionPlus sub = tvSub(id, "某剧", 1, "ACTIVE");
        sub.setOwnerUserId(owner);
        return sub;
    }

    @Test
    void canAccess_管理员不查库直接放行() {
        assertTrue(service().canAccess(1, PtStatsScope.ALL));
        verify(recordService, never()).getById(anyInt());
    }

    @Test
    void canAccess_自己的订阅与公共订阅放行_别人的与已删除订阅拒绝() {
        PtStatsScope me = PtStatsScope.of(false, 7L);
        when(recordService.getById(1)).thenReturn(record(1, 10, 5, "FAILED", 20, 30));
        when(recordService.getById(2)).thenReturn(record(2, 11, 5, "FAILED", 20, 30));
        when(recordService.getById(3)).thenReturn(record(3, 12, 5, "FAILED", 20, 30));
        when(recordService.getById(4)).thenReturn(record(4, 13, 5, "FAILED", 20, 30));
        when(subscriptionService.getById(10)).thenReturn(owned(10, 7L));
        when(subscriptionService.getById(11)).thenReturn(owned(11, null));
        when(subscriptionService.getById(12)).thenReturn(owned(12, 8L));
        when(subscriptionService.getById(13)).thenReturn(null);

        assertTrue(service().canAccess(1, me));
        assertTrue(service().canAccess(2, me));
        assertFalse(service().canAccess(3, me));
        assertFalse(service().canAccess(4, me));
        assertFalse(service().canAccess(999, me));
    }

    @Test
    void filterAccessible_只留下有权操作的记录并保持原顺序() {
        PtStatsScope me = PtStatsScope.of(false, 7L);
        when(recordService.listByIds(List.of(3, 1, 2))).thenReturn(List.of(
                record(1, 10, 5, "FAILED", 20, 30),
                record(2, 12, 5, "FAILED", 20, 30),
                record(3, 11, 5, "FAILED", 20, 30)));
        when(subscriptionService.listByIds(any())).thenReturn(List.of(owned(10, 7L), owned(11, null), owned(12, 8L)));

        assertEquals(List.of(3, 1), service().filterAccessible(List.of(3, 1, 2), me));
    }

    // ---------- enrich：拉黑标记与接替者 ----------

    @Test
    void enrich_标出种子与发布组是否已拉黑() {
        PtDownloadRecordPlus r = record(1, 10, 5, "COMPLETED", 20, 30);
        r.setGuidHash("h1");
        r.setTitle("Some.Show.S01E05-GRP");
        when(subscriptionService.listByIds(List.of(10))).thenReturn(List.of(tvSub(10, "某剧", 1, "ACTIVE")));
        when(blacklistService.releaseGroupOf("Some.Show.S01E05-GRP")).thenReturn("Grp");
        when(blacklistService.normalizeReleaseGroup("Grp")).thenReturn("GRP");
        when(blacklistService.findBlockedValues(eq("GUID"), any())).thenReturn(Set.of("h1"));
        when(blacklistService.findBlockedValues(eq("RELEASE_GROUP"), any())).thenReturn(Set.of());

        var view = service().enrich(PageResult.of(List.of(r), 1, 1, 10)).getRecords().get(0);

        assertEquals("Grp", view.getReleaseGroup());
        assertTrue(view.getGuidBlacklisted());
        assertFalse(view.getReleaseGroupBlacklisted());
    }

    @Test
    void enrich_失败记录之后同集有新推送_标出接替者() {
        PtDownloadRecordPlus failed = record(5, 10, 3, "FAILED", 20, 30);
        PtDownloadRecordPlus otherEp = record(6, 10, 4, "PUSHED", 20, 30);
        PtDownloadRecordPlus range = record(8, 10, 2, "COMPLETED", 20, 30);
        range.setEpisodeEnd(3);
        PtDownloadRecordPlus sameEp = record(9, 10, 3, "DOWNLOADING", 20, 30);
        when(subscriptionService.listByIds(List.of(10))).thenReturn(List.of(tvSub(10, "某剧", 1, "ACTIVE")));
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(sameEp, otherEp, range));

        var views = service().enrich(PageResult.of(List.of(failed), 1, 1, 10)).getRecords();

        // 取 id 最小的那条覆盖者：区间 E02-E03 的 #8 早于单集的 #9
        assertEquals(8, views.get(0).getSupersededById());
    }

    @Test
    void enrich_失败的季包不被后续单集当成接替() {
        PtDownloadRecordPlus failed = record(5, 10, -1, "FAILED", 20, 30);
        when(subscriptionService.listByIds(List.of(10))).thenReturn(List.of(tvSub(10, "某剧", 1, "ACTIVE")));
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(record(6, 10, 1, "COMPLETED", 20, 30)));

        var views = service().enrich(PageResult.of(List.of(failed), 1, 1, 10)).getRecords();

        assertNull(views.get(0).getSupersededById());
    }

    @Test
    void enrich_没有失败记录_不查接替者() {
        when(subscriptionService.listByIds(List.of(10))).thenReturn(List.of(tvSub(10, "某剧", 1, "ACTIVE")));

        service().enrich(PageResult.of(List.of(record(1, 10, 5, "COMPLETED", 20, 30)), 1, 1, 10));

        verify(recordService, never()).list(any(Wrapper.class));
    }

    // ---------- 清理 ----------

    @Test
    void cleanup_保留天数不在白名单_拒绝() {
        assertThrows(IllegalArgumentException.class, () -> service().cleanup(1));
        verify(recordService, never()).remove(any(Wrapper.class));
    }

    @Test
    void cleanup_条件含终态_保种中除外_未被集引用() {
        when(recordService.count(any(Wrapper.class))).thenReturn(3L);

        assertEquals(3, service().cleanup(90));

        ArgumentCaptor<Wrapper> where = ArgumentCaptor.forClass(Wrapper.class);
        verify(recordService).remove(where.capture());
        String sql = where.getValue().getSqlSegment();
        assertTrue(sql.contains("state IN"), sql);
        assertTrue(sql.contains("pushed_time <"), sql);
        assertTrue(sql.contains("hr_state IS NULL"), sql);
        assertTrue(sql.contains("NOT EXISTS"), sql);
    }

    @Test
    void cleanup_没有可清理的_不发删除() {
        when(recordService.count(any(Wrapper.class))).thenReturn(0L);

        assertEquals(0, service().cleanup(180));
        verify(recordService, never()).remove(any(Wrapper.class));
    }
}
