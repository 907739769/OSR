package com.osr.openliststrm.pt.subscription;

import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.filter.TorrentBlacklist;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.indexer.IndexerCapability;
import com.osr.openliststrm.pt.indexer.IndexerCapabilityCache;
import com.osr.openliststrm.pt.indexer.TorznabClient;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.PushSelectedRequest;
import com.osr.openliststrm.pt.subscription.dto.SupplementResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchSupplementServiceTest {

    @Mock private IPtIndexerPlusService indexerService;
    @Mock private TorznabClient torznabClient;
    @Mock private SubscriptionEngine subscriptionEngine;
    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    // 用真实实例而非 mock：标题归一化逻辑本身就是本测试要验证的行为
    private final SubscriptionMatcher matcher = new SubscriptionMatcher();
    @Mock
    private IPtFilterConfigPlusService filterConfigService;
    @Mock
    private TorrentFilterEngine filterEngine;
    @Mock
    private TmdbSearchService tmdbSearchService;
    @Mock
    private IPtTorrentBlacklistPlusService blacklistService;
    @Mock
    private SearchLogService searchLogService;

    private IndexerCapabilityCache capabilityCache;

    private SearchSupplementService service;

    @BeforeEach
    void setUp() {
        capabilityCache = new IndexerCapabilityCache(torznabClient, 300_000L);
        service = new SearchSupplementService(indexerService, torznabClient, subscriptionEngine, subscriptionService, episodeService, matcher, capabilityCache, filterConfigService, filterEngine, tmdbSearchService, blacklistService, searchLogService);
    }

    private PtIndexerPlus indexer(int id) {
        PtIndexerPlus i = new PtIndexerPlus();
        i.setId(id);
        i.setName("idx-" + id);
        i.setEnabled("1");
        return i;
    }

    private PtSubscriptionPlus tvSub(int id, int season, int total) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setMediaType("TV");
        sub.setTitle("Some Show");
        sub.setSeason(season);
        sub.setTotalEpisodes(total);
        sub.setStatus("ACTIVE");
        return sub;
    }

    private TorrentInfo torrent(String title) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        // guid 按标题赋值，保证不同候选去重键不同：真实索引器返回的 guid 恒非空
        // （TorznabParser 未提供时回退 downloadUrl），guid 都为 null 只是测试夹具的简化，
        // 但会导致 addDeduped() 按 (indexerId, guid) 去重时把两个不同集的候选误判为同一条丢弃一个。
        t.setGuid(title);
        return t;
    }

    private PtSubscriptionPlus movieSub(int id, String title, String year) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setMediaType("MOVIE");
        sub.setTitle(title);
        sub.setYear(year);
        sub.setStatus("ACTIVE");
        return sub;
    }

    private PtSubscriptionEpisodePlus episode(int number, String state) {
        PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
        ep.setEpisode(number);
        ep.setState(state);
        return ep;
    }

    // ---------- searchAcrossIndexers ----------

    @Test
    void 并发搜索所有启用索引器_合并结果() throws Exception {
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1), indexer(2)));
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(torrent("a")), List.of(torrent("b")));

        List<TorrentInfo> results = service.searchAcrossIndexers("kw");

        assertEquals(2, results.size());
    }

    @Test
    void 单个索引器搜索失败_不影响其他索引器结果() throws Exception {
        PtIndexerPlus idx1 = indexer(1);
        PtIndexerPlus idx2 = indexer(2);
        when(indexerService.listEnabled()).thenReturn(List.of(idx1, idx2));
        // 注意：PtIndexerPlus 未覆写 equals()/hashCode()，继承自 BaseEntity 的 Lombok @Data
        // 生成版本只比较 createTime/updateTime/params——新建实体这些字段均为 null，
        // 导致不同 id 的两个实例被判为"相等"。用 same() 按引用而非 equals 区分两个桩，
        // 否则第二个 when() 的取参调用会命中第一个桩（idx1 的 thenThrow），在此处就直接抛出。
        when(torznabClient.search(same(idx1), eq("kw"))).thenThrow(new IOException("timeout"));
        when(torznabClient.search(same(idx2), eq("kw"))).thenReturn(List.of(torrent("b")));

        List<TorrentInfo> results = service.searchAcrossIndexers("kw");

        assertEquals(1, results.size());
        assertEquals("b", results.get(0).getTitle());
    }

    @Test
    void 无启用索引器_返回空列表() {
        when(indexerService.listEnabled()).thenReturn(List.of());

        assertTrue(service.searchAcrossIndexers("kw").isEmpty());
    }

    // ---------- 计划执行：索引器并发、单索引器内串行 ----------

    @Test
    void 多步计划_同一索引器内严格串行_索引器之间并发() throws Exception {
        // 这是 executePlan 的核心保证。反过来（同一索引器的多步并发发出）会让它们在
        // IndexerRateLimiter 里抢同一把 slot.serial 并各自叠加最小间隔，排最后的那个
        // 可能等到超过 max-wait-ms 被静默跳过，表现为搜索结果凭空少一批
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1), indexer(2), indexer(3)));

        Map<Integer, AtomicInteger> inFlightPerIndexer = new ConcurrentHashMap<>();
        AtomicInteger maxPerIndexer = new AtomicInteger(0);
        AtomicInteger maxAcrossIndexers = new AtomicInteger(0);
        AtomicInteger inFlightTotal = new AtomicInteger(0);
        CountDownLatch allStarted = new CountDownLatch(3);

        when(torznabClient.search(any(), anyString())).thenAnswer(inv -> {
            PtIndexerPlus idx = inv.getArgument(0);
            AtomicInteger mine = inFlightPerIndexer.computeIfAbsent(idx.getId(), k -> new AtomicInteger());
            // 先自增到局部变量再比较：updateAndGet 的 lambda 在 CAS 竞争下会被重试，
            // 把 incrementAndGet 写在里面会多加几次，观测值凭空变大
            int mineNow = mine.incrementAndGet();
            int totalNow = inFlightTotal.incrementAndGet();
            maxPerIndexer.updateAndGet(prev -> Math.max(prev, mineNow));
            maxAcrossIndexers.updateAndGet(prev -> Math.max(prev, totalNow));
            allStarted.countDown();
            // 等另外两个索引器也进来，证明它们确实是并发的（真串行的话这里会超时）
            allStarted.await(2, TimeUnit.SECONDS);
            inFlightTotal.decrementAndGet();
            mine.decrementAndGet();
            return List.of(torrent("t-" + idx.getId()));
        });

        PtSubscriptionPlus sub = tvSub(10, 1, 12);
        sub.setEnglishTitle("Another Name");
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(1, "MISSING")));

        service.searchAndPushMissing(10);

        assertEquals(1, maxPerIndexer.get(), "同一索引器上不应有两步同时在途");
        assertEquals(3, maxAcrossIndexers.get(), "三个索引器应当并发执行");
    }

    // ---------- supplement ----------

    @Test
    void supplement_成功推送_返回pushed为true并回写搜索时间() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo t = torrent("Some.Show.S01E02.1080p");
        t.setParsedSeason(1);
        t.setParsedEpisode(2);
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(t));
        when(subscriptionEngine.pushBest(eq(sub), eq(2), anyList())).thenReturn(true);

        SupplementResult result = service.supplement(10, 2, "Some Show S01E02");

        assertTrue(result.isPushed());
        assertEquals(1, result.getCandidateCount());
        ArgumentCaptor<PtSubscriptionPlus> captor = ArgumentCaptor.forClass(PtSubscriptionPlus.class);
        verify(subscriptionService).updateById(captor.capture());
        Assertions.assertNotNull(captor.getValue().getLastSearchTime());
    }

    @Test
    void supplement_搜索结果先做本地解析再交给引擎() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo raw = torrent("Some.Show.S01E02.1080p");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(raw));

        service.supplement(10, 2, "Some Show S01E02");

        verify(subscriptionEngine).fillParsed(raw);
    }

    @Test
    void supplement_手动模式_把真实黑名单传给过滤引擎而不是空黑名单() throws Exception {
        // 手动搜索若漏传黑名单，已拉黑的发布组/种子会照常出现在候选列表里，而用户真去选中它时，
        // pushSelected → SubscriptionEngine#pushBest 侧的黑名单又会把它拦下，只回一个没有原因的
        // false。两条链路必须看到同一份黑名单，这里锁死"传进引擎的黑名单来自 blacklistService"。
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo candidate = torrent("Some.Show.S01E01.1080p");
        candidate.setIndexerId(1);
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(candidate));
        when(filterConfigService.getConfig()).thenReturn(null);

        PtTorrentBlacklistPlus rule = new PtTorrentBlacklistPlus();
        rule.setType(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP);
        rule.setValue("CHDWEB");
        when(blacklistService.list()).thenReturn(List.of(rule));
        when(filterEngine.evaluate(anyList(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenReturn(List.of());

        service.supplement(10, 1, "Some Show S01E01", true);

        ArgumentCaptor<TorrentBlacklist> captor = ArgumentCaptor.forClass(TorrentBlacklist.class);
        verify(filterEngine).evaluate(anyList(), any(), captor.capture(),
                org.mockito.ArgumentMatchers.nullable(String.class));
        assertEquals(Set.of("CHDWEB"), captor.getValue().releaseGroupsUpper());
    }

    @Test
    void supplement_手动模式_候选DTO带上区间结尾集号供前端展示区间而非单集() throws Exception {
        // 种子实际覆盖 S01E01-E02（区间），若 DTO 丢了 parsedEpisodeEnd，前端只能显示成"第1集"
        PtSubscriptionPlus sub = tvSub(10, 1, 4);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo range = torrent("Some.Show.S01E01-E02.1080p");
        range.setParsedSeason(1);
        range.setParsedEpisode(1);
        range.setParsedEpisodeEnd(2);
        range.setIndexerId(1);
        range.setGuid("g-range");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(range));
        when(filterConfigService.getConfig()).thenReturn(null);
        when(filterEngine.evaluate(anyList(), any(), any(), org.mockito.ArgumentMatchers.nullable(String.class)))
                .thenAnswer(inv -> List.of(TorrentFilterEngine.Verdict.accept(range)));

        SupplementResult result = service.supplement(10, 1, "Some Show S01E01", true);

        assertEquals(1, result.getCandidates().size());
        assertEquals(1, result.getCandidates().get(0).getParsedEpisode());
        assertEquals(2, result.getCandidates().get(0).getParsedEpisodeEnd());
    }

    @Test
    void supplement_订阅不存在_抛异常() {
        when(subscriptionService.getById(99)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.supplement(99, 1, "kw"));
    }

    @Test
    void supplement_订阅已暂停_抛异常() {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        sub.setStatus("PAUSED");
        when(subscriptionService.getById(10)).thenReturn(sub);

        assertThrows(IllegalArgumentException.class, () -> service.supplement(10, 1, "kw"));
    }

    @Test
    void supplement_电影订阅传非0集号_抛异常() {
        PtSubscriptionPlus movie = new PtSubscriptionPlus();
        movie.setId(20);
        movie.setMediaType("MOVIE");
        movie.setStatus("ACTIVE");
        when(subscriptionService.getById(20)).thenReturn(movie);

        assertThrows(IllegalArgumentException.class, () -> service.supplement(20, 1, "kw"));
    }

    @Test
    void supplement_剧集集号超出总集数_抛异常() {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);

        assertThrows(IllegalArgumentException.class, () -> service.supplement(10, 4, "kw"));
    }

    @Test
    void supplement_季包哨兵值_剧集订阅允许通过() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of());

        SupplementResult result = service.supplement(10, SubscriptionMatcher.SEASON_PACK, "Some Show S01");

        assertFalse(result.isPushed());
    }

    // ---------- 季/集号一致性校验（filterByTarget） ----------

    @Test
    void supplement_季包目标_季号不匹配的候选被过滤_不会传给引擎() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo mismatch = torrent("Some.Show.S02.1080p");
        mismatch.setParsedSeason(2);
        mismatch.setParsedEpisode(null);
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(mismatch));

        SupplementResult result = service.supplement(10, SubscriptionMatcher.SEASON_PACK, "Some Show S01");

        assertEquals(1, result.getCandidateCount());
        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(sub), eq(SubscriptionMatcher.SEASON_PACK), captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    void supplement_季包目标_parsedEpisode不为null的单集候选被过滤() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo singleEpisode = torrent("Some.Show.S01E05.1080p");
        singleEpisode.setParsedSeason(1);
        singleEpisode.setParsedEpisode(5);
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(singleEpisode));

        SupplementResult result = service.supplement(10, SubscriptionMatcher.SEASON_PACK, "Some Show S01");

        assertEquals(1, result.getCandidateCount());
        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(sub), eq(SubscriptionMatcher.SEASON_PACK), captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    void supplement_单集目标_集号不匹配的候选被过滤() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo wrongEpisode = torrent("Some.Show.S01E05.1080p");
        wrongEpisode.setParsedSeason(1);
        wrongEpisode.setParsedEpisode(5);
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(wrongEpisode));

        SupplementResult result = service.supplement(10, 3, "Some Show S01E03");

        assertEquals(1, result.getCandidateCount());
        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(sub), eq(3), captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    void supplement_parsedSeason为null的候选被过滤() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo unparsed = torrent("Some.Show.Unknown");
        unparsed.setParsedSeason(null);
        unparsed.setParsedEpisode(null);
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(unparsed));

        service.supplement(10, SubscriptionMatcher.SEASON_PACK, "Some Show S01");

        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(sub), eq(SubscriptionMatcher.SEASON_PACK), captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    void supplement_季号集号都匹配的候选能正常传给引擎() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo mismatch = torrent("Some.Show.S02E03.1080p");
        mismatch.setParsedSeason(2);
        mismatch.setParsedEpisode(3);
        TorrentInfo match = torrent("Some.Show.S01E03.1080p");
        match.setParsedSeason(1);
        match.setParsedEpisode(3);
        match.setParsedTitle("Some Show");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(mismatch, match));
        when(subscriptionEngine.pushBest(eq(sub), eq(3), anyList())).thenReturn(true);

        SupplementResult result = service.supplement(10, 3, "Some Show S01E03");

        assertTrue(result.isPushed());
        assertEquals(2, result.getCandidateCount());
        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(sub), eq(3), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertTrue(captor.getValue().contains(match));
        assertFalse(captor.getValue().contains(mismatch));
    }

    @Test
    void supplement_电影订阅_标题年份都匹配的候选能正常传给引擎() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo t = torrent("手机.2003.1080p");
        t.setParsedTitle("手机");
        t.setParsedYear("2003");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(t));
        when(subscriptionEngine.pushBest(eq(movie), eq(0), anyList())).thenReturn(true);

        SupplementResult result = service.supplement(20, 0, "手机");

        assertTrue(result.isPushed());
        assertEquals(1, result.getCandidateCount());
        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(movie), eq(0), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertTrue(captor.getValue().contains(t));
    }

    @Test
    void supplement_电影订阅_标题不匹配的候选被过滤_不会传给引擎() throws Exception {
        // 复现用户反馈的错配场景：搜索关键词"手机"命中的候选标题里含"手机"二字，
        // 但归一化后与订阅标题不相等（不是同一部作品），必须被过滤掉。
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo unrelated = torrent("有手机就打.2020.1080p");
        unrelated.setParsedTitle("有手机就打");
        unrelated.setParsedYear("2020");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(unrelated));

        SupplementResult result = service.supplement(20, 0, "手机");

        assertFalse(result.isPushed());
        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(movie), eq(0), captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    void supplement_电影订阅_年份不一致的候选被过滤() throws Exception {
        // 同名翻拍常见，标题相同但年份不符宁可漏也不能串台
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo remake = torrent("手机.2020.1080p");
        remake.setParsedTitle("手机");
        remake.setParsedYear("2020");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(remake));

        SupplementResult result = service.supplement(20, 0, "手机");

        assertFalse(result.isPushed());
        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(movie), eq(0), captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    @Test
    void supplement_电影订阅_年份差一年的候选仍放行() throws Exception {
        // 与 SubscriptionMatcher 的电影分支共用 movieYearMatches：电影节首映 vs 正式公映、
        // 跨年上映都会让同一部电影差一年，严格相等会把这类完全正确的候选整条淘汰
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo offByOne = torrent("手机.2004.1080p");
        offByOne.setParsedTitle("手机");
        offByOne.setParsedYear("2004");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(offByOne));

        service.supplement(20, 0, "手机");

        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(movie), eq(0), captor.capture());
        assertEquals(1, captor.getValue().size());
    }

    @Test
    void supplement_电影订阅_候选带季集信息的被过滤() throws Exception {
        // 带季/集号的候选一定是剧集/综艺，不该匹配电影订阅
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo tvShow = torrent("手机.S01E01.2003.1080p");
        tvShow.setParsedTitle("手机");
        tvShow.setParsedYear("2003");
        tvShow.setParsedSeason(1);
        tvShow.setParsedEpisode(1);
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(tvShow));

        SupplementResult result = service.supplement(20, 0, "手机");

        assertFalse(result.isPushed());
        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(movie), eq(0), captor.capture());
        assertTrue(captor.getValue().isEmpty());
    }

    // ---------- 中英文双语关键词兜底 ----------

    @Test
    void supplement_中文搜到候选_不触发英文补搜() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        movie.setOriginalTitle("手机");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo t = torrent("手机.2003.1080p");
        t.setParsedTitle("手机");
        t.setParsedYear("2003");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(t));
        when(subscriptionEngine.pushBest(eq(movie), eq(0), anyList())).thenReturn(true);

        service.supplement(20, 0, "手机");

        verify(torznabClient, times(1)).search(any(), anyString());
    }

    @Test
    void supplement_中文搜不到_originalTitle非空且不同_触发英文补搜() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "沙丘", "2021");
        movie.setOriginalTitle("Dune");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo enTorrent = torrent("Dune.2021.2160p.WEB-DL");
        enTorrent.setParsedTitle("Dune");
        enTorrent.setParsedYear("2021");
        when(torznabClient.search(any(), eq("沙丘"))).thenReturn(List.of());
        when(torznabClient.search(any(), eq("Dune"))).thenReturn(List.of(enTorrent));
        when(subscriptionEngine.pushBest(eq(movie), eq(0), anyList())).thenReturn(true);

        SupplementResult result = service.supplement(20, 0, "沙丘");

        assertTrue(result.isPushed());
        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(movie), eq(0), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertTrue(captor.getValue().contains(enTorrent));
    }

    @Test
    void supplement_originalTitle为空_不触发英文补搜() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        when(torznabClient.search(any(), eq("手机"))).thenReturn(List.of());

        service.supplement(20, 0, "手机");

        verify(torznabClient, times(1)).search(any(), anyString());
    }

    @Test
    void supplement_originalTitle归一化后与title相同_不触发英文补搜() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        movie.setOriginalTitle("手机");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        when(torznabClient.search(any(), eq("手机"))).thenReturn(List.of());

        service.supplement(20, 0, "手机");

        verify(torznabClient, times(1)).search(any(), anyString());
    }

    @Test
    void supplement_剧集英文补搜关键词按原有格式拼season和episode() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 12);
        sub.setOriginalTitle("Breaking Bad");
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        when(torznabClient.search(any(), eq("Some Show S01E03"))).thenReturn(List.of());
        TorrentInfo enTorrent = torrent("Breaking.Bad.S01E03.1080p");
        enTorrent.setParsedSeason(1);
        enTorrent.setParsedEpisode(3);
        when(torznabClient.search(any(), eq("Breaking Bad S01E03"))).thenReturn(List.of(enTorrent));
        when(subscriptionEngine.pushBest(eq(sub), eq(3), anyList())).thenReturn(true);

        service.supplement(10, 3, "Some Show S01E03");

        verify(torznabClient).search(any(), eq("Breaking Bad S01E03"));
    }

    @Test
    void supplement_剧集季包英文补搜关键词不带E后缀() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 12);
        sub.setOriginalTitle("Breaking Bad");
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        when(torznabClient.search(any(), eq("Some Show S01"))).thenReturn(List.of());
        when(torznabClient.search(any(), eq("Breaking Bad S01"))).thenReturn(List.of());

        service.supplement(10, SubscriptionMatcher.SEASON_PACK, "Some Show S01");

        verify(torznabClient).search(any(), eq("Breaking Bad S01"));
    }

    // ---------- validateEpisode 的 totalEpisodes null 安全 ----------

    @Test
    void supplement_剧集totalEpisodes为null_抛IllegalArgumentException而非NPE() {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        sub.setTotalEpisodes(null);
        when(subscriptionService.getById(10)).thenReturn(sub);

        assertThrows(IllegalArgumentException.class, () -> service.supplement(10, 2, "kw"));
    }

    // ---------- ID 搜索第一级 ----------

    @Test
    void supplement_电影订阅_索引器支持imdbid且订阅有imdbId_优先用imdbid搜索() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "沙丘", "2021");
        movie.setOriginalTitle("Dune");
        movie.setImdbId("tt1160419");
        movie.setTmdbId("438631");
        when(subscriptionService.getById(20)).thenReturn(movie);
        PtIndexerPlus idx = indexer(1);
        when(indexerService.listEnabled()).thenReturn(List.of(idx));
        when(torznabClient.getCaps(idx)).thenReturn(new IndexerCapability(true, true, false, false));
        TorrentInfo t = torrent("Dune.2021.2160p.WEB-DL");
        t.setParsedTitle("Dune");
        t.setParsedYear("2021");
        when(torznabClient.searchByExternalId(idx, true, "imdbid", "tt1160419", null, null))
                .thenReturn(List.of(t));
        when(subscriptionEngine.pushBest(eq(movie), eq(0), anyList())).thenReturn(true);

        SupplementResult result = service.supplement(20, 0, "沙丘");

        assertTrue(result.isPushed());
        verify(torznabClient, never()).search(any(), anyString());
        ArgumentCaptor<List<TorrentInfo>> captor = ArgumentCaptor.forClass(List.class);
        verify(subscriptionEngine).pushBest(eq(movie), eq(0), captor.capture());
        assertTrue(captor.getValue().contains(t));
    }

    @Test
    void supplement_电影订阅_索引器只支持tmdbid_退到tmdbid() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "沙丘", "2021");
        movie.setImdbId("tt1160419");
        movie.setTmdbId("438631");
        when(subscriptionService.getById(20)).thenReturn(movie);
        PtIndexerPlus idx = indexer(1);
        when(indexerService.listEnabled()).thenReturn(List.of(idx));
        when(torznabClient.getCaps(idx)).thenReturn(new IndexerCapability(false, true, false, false));
        TorrentInfo t = torrent("Dune.2021.2160p.WEB-DL");
        t.setParsedTitle("Dune");
        t.setParsedYear("2021");
        when(torznabClient.searchByExternalId(idx, true, "tmdbid", "438631", null, null))
                .thenReturn(List.of(t));
        when(subscriptionEngine.pushBest(eq(movie), eq(0), anyList())).thenReturn(true);

        service.supplement(20, 0, "沙丘");

        verify(torznabClient).searchByExternalId(idx, true, "tmdbid", "438631", null, null);
        verify(torznabClient, never()).searchByExternalId(eq(idx), eq(true), eq("imdbid"), any(), any(), any());
    }

    @Test
    void supplement_电影订阅_订阅无imdbId_退到tmdbid() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "沙丘", "2021");
        movie.setTmdbId("438631");
        when(subscriptionService.getById(20)).thenReturn(movie);
        PtIndexerPlus idx = indexer(1);
        when(indexerService.listEnabled()).thenReturn(List.of(idx));
        when(torznabClient.getCaps(idx)).thenReturn(new IndexerCapability(true, true, false, false));
        when(torznabClient.searchByExternalId(idx, true, "tmdbid", "438631", null, null))
                .thenReturn(List.of());
        when(torznabClient.search(any(), anyString())).thenReturn(List.of());

        service.supplement(20, 0, "沙丘");

        verify(torznabClient).searchByExternalId(idx, true, "tmdbid", "438631", null, null);
    }

    @Test
    void supplement_索引器不支持ID搜索_跳过ID搜索直接走标题() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        movie.setImdbId("tt0125664");
        movie.setTmdbId("1");
        when(subscriptionService.getById(20)).thenReturn(movie);
        PtIndexerPlus idx = indexer(1);
        when(indexerService.listEnabled()).thenReturn(List.of(idx));
        when(torznabClient.getCaps(idx)).thenReturn(IndexerCapability.NONE);
        TorrentInfo t = torrent("手机.2003.1080p");
        t.setParsedTitle("手机");
        t.setParsedYear("2003");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(t));
        when(subscriptionEngine.pushBest(eq(movie), eq(0), anyList())).thenReturn(true);

        service.supplement(20, 0, "手机");

        verify(torznabClient, never()).searchByExternalId(any(), anyBoolean(), anyString(), anyString(), any(), any());
    }

    @Test
    void supplement_ID搜索直接推送不过滤() throws Exception {
        // ID 搜索是精确匹配，结果直接走 pushBest，不经过 filterByTarget
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        movie.setImdbId("tt0125664");
        when(subscriptionService.getById(20)).thenReturn(movie);
        PtIndexerPlus idx = indexer(1);
        when(indexerService.listEnabled()).thenReturn(List.of(idx));
        when(torznabClient.getCaps(idx)).thenReturn(new IndexerCapability(true, false, false, false));
        TorrentInfo anySeed = torrent("手机.2003.1080p");
        anySeed.setParsedTitle("手机");
        anySeed.setParsedYear("2003");
        when(torznabClient.searchByExternalId(idx, true, "imdbid", "tt0125664", null, null))
                .thenReturn(List.of(anySeed));
        when(subscriptionEngine.pushBest(eq(movie), eq(0), anyList())).thenReturn(true);

        SupplementResult result = service.supplement(20, 0, "手机");

        assertTrue(result.isPushed());
        // ID 有结果直接推送，不降级到关键词搜索
        verify(torznabClient, never()).search(any(), anyString());
    }

    @Test
    void supplement_剧集订阅_季包ID搜索不带ep参数() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 12);
        sub.setImdbId("tt0903747");
        when(subscriptionService.getById(10)).thenReturn(sub);
        PtIndexerPlus idx = indexer(1);
        when(indexerService.listEnabled()).thenReturn(List.of(idx));
        when(torznabClient.getCaps(idx)).thenReturn(new IndexerCapability(false, false, true, false));
        when(torznabClient.searchByExternalId(idx, false, "imdbid", "tt0903747", 1, null))
                .thenReturn(List.of());

        service.supplement(10, SubscriptionMatcher.SEASON_PACK, "Some Show S01");

        verify(torznabClient).searchByExternalId(idx, false, "imdbid", "tt0903747", 1, null);
    }

    @Test
    void supplement_剧集订阅_单集ID搜索带season和ep() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 12);
        sub.setImdbId("tt0903747");
        when(subscriptionService.getById(10)).thenReturn(sub);
        PtIndexerPlus idx = indexer(1);
        when(indexerService.listEnabled()).thenReturn(List.of(idx));
        when(torznabClient.getCaps(idx)).thenReturn(new IndexerCapability(false, false, true, false));
        when(torznabClient.searchByExternalId(idx, false, "imdbid", "tt0903747", 1, 3))
                .thenReturn(List.of());

        service.supplement(10, 3, "Some Show S01E03");

        verify(torznabClient).searchByExternalId(idx, false, "imdbid", "tt0903747", 1, 3);
    }

    // ---------- supplementOnCreate ----------

    @Test
    void supplementOnCreate_订阅不存在_不发起搜索() {
        when(subscriptionService.getById(99)).thenReturn(null);

        service.supplementOnCreate(99);

        verify(subscriptionEngine, never()).pushBest(any(), anyInt(), anyList());
    }

    @Test
    void supplementOnCreate_订阅非ACTIVE_不发起搜索() {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        sub.setStatus("COMPLETED");
        when(subscriptionService.getById(10)).thenReturn(sub);

        service.supplementOnCreate(10);

        verify(subscriptionEngine, never()).pushBest(any(), anyInt(), anyList());
        verify(episodeService, never()).listBySubscription(10);
    }

    @Test
    void supplementOnCreate_无缺失集_不发起搜索() {
        PtSubscriptionPlus sub = tvSub(10, 1, 2);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(episodeService.listBySubscription(10)).thenReturn(
                List.of(episode(1, "IN_LIBRARY"), episode(2, "IN_LIBRARY")));

        service.supplementOnCreate(10);

        verify(subscriptionEngine, never()).pushBest(any(), anyInt(), anyList());
    }

    @Test
    void supplementOnCreate_电影订阅_只搜一次() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(episodeService.listBySubscription(20)).thenReturn(List.of(episode(0, "MISSING")));
        when(indexerService.listEnabled()).thenReturn(List.of());

        service.supplementOnCreate(20);

        verify(subscriptionEngine, times(1)).pushBest(eq(movie), eq(0), anyList());
    }

    @Test
    void supplementOnCreate_电影补搜异常_不抛出异常仍正常返回() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(episodeService.listBySubscription(20)).thenReturn(List.of(episode(0, "MISSING")));
        when(indexerService.listEnabled()).thenReturn(List.of());
        when(subscriptionEngine.pushBest(eq(movie), eq(0), anyList()))
                .thenThrow(new RuntimeException("boom"));

        assertDoesNotThrow(() -> service.supplementOnCreate(20));

        verify(subscriptionEngine, times(1)).pushBest(eq(movie), eq(0), anyList());
    }

    @Test
    void supplementOnCreate_剧集季包命中_不逐集兜底() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 2);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(episodeService.listBySubscription(10)).thenReturn(
                List.of(episode(1, "MISSING"), episode(2, "MISSING")));
        // searchSeasonCandidates: keyword 搜索返回季包候选
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo seasonPack = torrent("Some.Show.S01.1080p");
        seasonPack.setParsedSeason(1);
        seasonPack.setParsedEpisode(null);
        seasonPack.setParsedTitle("Some Show");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(seasonPack));
        when(subscriptionEngine.pushBest(eq(sub), eq(SubscriptionMatcher.SEASON_PACK), anyList())).thenReturn(true);

        service.supplementOnCreate(10);

        verify(subscriptionEngine, times(1)).pushBest(eq(sub), eq(SubscriptionMatcher.SEASON_PACK), anyList());
        verify(subscriptionEngine, never()).pushBest(eq(sub), eq(1), anyList());
        verify(subscriptionEngine, never()).pushBest(eq(sub), eq(2), anyList());
    }

    @Test
    void supplementOnCreate_季包未命中_逐集兜底剩余缺失集() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        List<PtSubscriptionEpisodePlus> episodes = List.of(
                episode(1, "MISSING"), episode(2, "MISSING"), episode(3, "IN_LIBRARY"));
        when(episodeService.listBySubscription(10)).thenReturn(episodes);
        // searchSeasonCandidates: 返回两个单集候选
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo ep1torrent = torrent("Some.Show.S01E01.1080p");
        ep1torrent.setParsedSeason(1);
        ep1torrent.setParsedEpisode(1);
        ep1torrent.setParsedTitle("Some Show");
        TorrentInfo ep2torrent = torrent("Some.Show.S01E02.1080p");
        ep2torrent.setParsedSeason(1);
        ep2torrent.setParsedEpisode(2);
        ep2torrent.setParsedTitle("Some Show");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(ep1torrent, ep2torrent));
        when(subscriptionEngine.pushBest(eq(sub), eq(1), anyList())).thenReturn(true);
        when(subscriptionEngine.pushBest(eq(sub), eq(2), anyList())).thenReturn(true);

        service.supplementOnCreate(10);

        verify(subscriptionEngine, times(1)).pushBest(eq(sub), eq(1), anyList());
        verify(subscriptionEngine, times(1)).pushBest(eq(sub), eq(2), anyList());
        verify(subscriptionEngine, never()).pushBest(eq(sub), eq(3), anyList());
    }

    @Test
    void supplementOnCreate_中文关键词与英文兜底候选合并_而非命中即停() throws Exception {
        // 中文标题关键词搜索只命中第1集，英文原名兜底命中第2集；
        // 修复前"命中即停"会导致原名搜索根本不会发起，第2集永远补不到
        PtSubscriptionPlus sub = tvSub(10, 1, 2);
        sub.setOriginalTitle("Breaking Bad");
        when(subscriptionService.getById(10)).thenReturn(sub);
        List<PtSubscriptionEpisodePlus> episodes = List.of(
                episode(1, "MISSING"), episode(2, "MISSING"));
        when(episodeService.listBySubscription(10)).thenReturn(episodes);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));

        TorrentInfo ep1 = torrent("Some.Show.S01E01.1080p");
        ep1.setParsedSeason(1);
        ep1.setParsedEpisode(1);
        ep1.setParsedTitle("Some Show");
        when(torznabClient.search(any(), eq("Some Show S01"))).thenReturn(List.of(ep1));

        TorrentInfo ep2 = torrent("Breaking.Bad.S01E02.1080p");
        ep2.setParsedSeason(1);
        ep2.setParsedEpisode(2);
        ep2.setParsedTitle("Breaking Bad");
        when(torznabClient.search(any(), eq("Breaking Bad S01"))).thenReturn(List.of(ep2));

        when(subscriptionEngine.pushBest(eq(sub), eq(1), anyList())).thenReturn(true);
        when(subscriptionEngine.pushBest(eq(sub), eq(2), anyList())).thenReturn(true);

        service.supplementOnCreate(10);

        verify(torznabClient).search(any(), eq("Some Show S01"));
        verify(torznabClient).search(any(), eq("Breaking Bad S01"));
        // 两级搜索都要发起，且候选池合并后两集都能推送成功
        verify(subscriptionEngine, times(1)).pushBest(eq(sub), eq(1), anyList());
        verify(subscriptionEngine, times(1)).pushBest(eq(sub), eq(2), anyList());
    }

    @Test
    void supplementOnCreate_逐集兜底关键词按season两位数格式拼() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 2, 5);
        when(subscriptionService.getById(10)).thenReturn(sub);
        List<PtSubscriptionEpisodePlus> episodes = List.of(episode(5, "MISSING"));
        when(episodeService.listBySubscription(10)).thenReturn(episodes);
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        when(torznabClient.search(any(), anyString())).thenReturn(List.of());

        service.supplementOnCreate(10);

        // 优化后：只有整季搜索关键字，不再逐集搜索（免去 E05 的搜索）
        verify(torznabClient).search(any(), eq("Some Show S02"));
        verify(torznabClient, never()).search(any(), eq("Some Show S02E05"));
    }

    @Test
    void supplementOnCreate_季包推送异常_仍继续逐集兜底() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 2);
        when(subscriptionService.getById(10)).thenReturn(sub);
        List<PtSubscriptionEpisodePlus> episodes = List.of(
                episode(1, "MISSING"), episode(2, "MISSING"));
        when(episodeService.listBySubscription(10)).thenReturn(episodes);
        // searchSeasonCandidates 返回单集候选
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo ep1 = torrent("Some.Show.S01E01.1080p");
        ep1.setParsedSeason(1);
        ep1.setParsedEpisode(1);
        ep1.setParsedTitle("Some Show");
        TorrentInfo ep2 = torrent("Some.Show.S01E02.1080p");
        ep2.setParsedSeason(1);
        ep2.setParsedEpisode(2);
        ep2.setParsedTitle("Some Show");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(ep1, ep2));
        when(subscriptionEngine.pushBest(eq(sub), eq(1), anyList())).thenReturn(true);
        when(subscriptionEngine.pushBest(eq(sub), eq(2), anyList())).thenReturn(true);

        service.supplementOnCreate(10);

        verify(subscriptionEngine, times(1)).pushBest(eq(sub), eq(1), anyList());
        verify(subscriptionEngine, times(1)).pushBest(eq(sub), eq(2), anyList());
    }

    @Test
    void supplementOnCreate_某集推送异常_不影响其余集继续搜索() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 3);
        when(subscriptionService.getById(10)).thenReturn(sub);
        List<PtSubscriptionEpisodePlus> episodes = List.of(
                episode(1, "MISSING"), episode(2, "MISSING"), episode(3, "IN_LIBRARY"));
        when(episodeService.listBySubscription(10)).thenReturn(episodes);
        // searchSeasonCandidates 返回候选
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo ep1 = torrent("Some.Show.S01E01.1080p");
        ep1.setParsedSeason(1);
        ep1.setParsedEpisode(1);
        ep1.setParsedTitle("Some Show");
        TorrentInfo ep2 = torrent("Some.Show.S01E02.1080p");
        ep2.setParsedSeason(1);
        ep2.setParsedEpisode(2);
        ep2.setParsedTitle("Some Show");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(ep1, ep2));
        when(subscriptionEngine.pushBest(eq(sub), eq(1), anyList())).thenThrow(new RuntimeException("boom"));
        when(subscriptionEngine.pushBest(eq(sub), eq(2), anyList())).thenReturn(true);

        service.supplementOnCreate(10);

        verify(subscriptionEngine, times(1)).pushBest(eq(sub), eq(1), anyList());
        verify(subscriptionEngine, times(1)).pushBest(eq(sub), eq(2), anyList());
    }

    // ---------- supplementOnCreate 全部落空时的告警通知 ----------

    @Test
    void supplementOnCreate_电影补搜全部落空_发送告警通知() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(episodeService.listBySubscription(20)).thenReturn(List.of(episode(0, "MISSING")));
        when(indexerService.listEnabled()).thenReturn(List.of());
        when(subscriptionEngine.pushBest(eq(movie), eq(0), anyList())).thenReturn(false);

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service.supplementOnCreate(20);
            tg.verify(() -> TgHelper.sendMsg(any(), anyString(), any()));
        }
    }

    @Test
    void supplementOnCreate_电影补搜命中_不发送告警通知() throws Exception {
        PtSubscriptionPlus movie = movieSub(20, "手机", "2003");
        when(subscriptionService.getById(20)).thenReturn(movie);
        when(episodeService.listBySubscription(20)).thenReturn(List.of(episode(0, "MISSING")));
        when(indexerService.listEnabled()).thenReturn(List.of());
        when(subscriptionEngine.pushBest(eq(movie), eq(0), anyList())).thenReturn(true);

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service.supplementOnCreate(20);
            tg.verify(() -> TgHelper.sendMsg(any(), anyString(), any()), never());
        }
    }

    @Test
    void supplementOnCreate_剧集无任何候选_发送告警通知() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 2);
        when(subscriptionService.getById(10)).thenReturn(sub);
        List<PtSubscriptionEpisodePlus> episodes = List.of(
                episode(1, "MISSING"), episode(2, "MISSING"));
        when(episodeService.listBySubscription(10)).thenReturn(episodes);
        // 无索引器 → 搜索无候选 → 无推送 → 告警
        when(indexerService.listEnabled()).thenReturn(List.of());

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service.supplementOnCreate(10);
            tg.verify(() -> TgHelper.sendMsg(any(), anyString(), any()));
        }
    }

    @Test
    void supplementOnCreate_剧集季包命中_不发送告警通知() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 2);
        when(subscriptionService.getById(10)).thenReturn(sub);
        when(episodeService.listBySubscription(10)).thenReturn(
                List.of(episode(1, "MISSING"), episode(2, "MISSING")));
        // 有季包候选
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo seasonPack = torrent("Some.Show.S01.1080p");
        seasonPack.setParsedSeason(1);
        seasonPack.setParsedEpisode(null);
        seasonPack.setParsedTitle("Some Show");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(seasonPack));
        when(subscriptionEngine.pushBest(eq(sub), eq(SubscriptionMatcher.SEASON_PACK), anyList())).thenReturn(true);

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service.supplementOnCreate(10);
            tg.verify(() -> TgHelper.sendMsg(any(), anyString(), any()), never());
        }
    }

    @Test
    void supplementOnCreate_剧集季包未命中但逐集命中_不发送告警通知() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, 1, 2);
        when(subscriptionService.getById(10)).thenReturn(sub);
        List<PtSubscriptionEpisodePlus> episodes = List.of(
                episode(1, "MISSING"), episode(2, "MISSING"));
        when(episodeService.listBySubscription(10)).thenReturn(episodes);
        // 有单集候选（季包 searchSeasonCandidates 返回单集 → 季包 push 不会执行 → 逐集推送）
        when(indexerService.listEnabled()).thenReturn(List.of(indexer(1)));
        TorrentInfo ep1 = torrent("Some.Show.S01E01.1080p");
        ep1.setParsedSeason(1);
        ep1.setParsedEpisode(1);
        ep1.setParsedTitle("Some Show");
        TorrentInfo ep2 = torrent("Some.Show.S01E02.1080p");
        ep2.setParsedSeason(1);
        ep2.setParsedEpisode(2);
        ep2.setParsedTitle("Some Show");
        when(torznabClient.search(any(), anyString())).thenReturn(List.of(ep1, ep2));
        when(subscriptionEngine.pushBest(eq(sub), eq(1), anyList())).thenReturn(true);

        try (MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service.supplementOnCreate(10);
            tg.verify(() -> TgHelper.sendMsg(any(), anyString(), any()), never());
        }
    }

    // ---------- pushSelected：占位目标必须与选中的种子对得上 ----------

    /** 让 mock 的 fillParsed 把解析结果写进候选，模拟真实 SubscriptionEngine 的行为 */
    private void stubFillParsed(Integer season, Integer ep, Integer epEnd) {
        org.mockito.Mockito.doAnswer(inv -> {
            TorrentInfo t = inv.getArgument(0);
            t.setParsedSeason(season);
            t.setParsedEpisode(ep);
            t.setParsedEpisodeEnd(epEnd);
            return null;
        }).when(subscriptionEngine).fillParsed(any());
    }

    private PushSelectedRequest pushRequest(String title, int episode) {
        PushSelectedRequest req = new PushSelectedRequest();
        req.setTitle(title);
        req.setEpisode(episode);
        req.setIndexerId(1);
        req.setGuid(title);
        req.setDownloadUrl("http://x/" + title);
        return req;
    }

    @Test
    void pushSelected_整季目标选中单集种子_按种子实际集号占位而非占全部缺失集() {
        PtSubscriptionPlus sub = tvSub(10, 8, 10);
        when(subscriptionService.getById(10)).thenReturn(sub);
        stubFillParsed(8, 7, null);
        when(subscriptionEngine.pushBest(same(sub), eq(7), anyList())).thenReturn(true);

        assertTrue(service.pushSelected(10, SubscriptionMatcher.SEASON_PACK,
                pushRequest("Great Escape S08E07 2026 1080p WEB-DL", SubscriptionMatcher.SEASON_PACK)));

        // 关键：不能再用 -1 去占位——那会把当前所有缺失集都标成在途，而包里只有第 7 集
        verify(subscriptionEngine, never()).pushBest(any(), eq(SubscriptionMatcher.SEASON_PACK), anyList());
    }

    @Test
    void pushSelected_指定集目标选中别的集_直接拒绝并说明原因() {
        PtSubscriptionPlus sub = tvSub(10, 8, 10);
        when(subscriptionService.getById(10)).thenReturn(sub);
        stubFillParsed(8, 7, null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.pushSelected(10, 8, pushRequest("Great Escape S08E07 2026 1080p WEB-DL", 8)));

        assertTrue(e.getMessage().contains("第 7 集"), e.getMessage());
        assertTrue(e.getMessage().contains("不含第 8 集"), e.getMessage());
        verify(subscriptionEngine, never()).pushBest(any(), anyInt(), anyList());
    }

    @Test
    void pushSelected_区间包覆盖目标集_放行且沿用目标集号() {
        PtSubscriptionPlus sub = tvSub(10, 8, 10);
        when(subscriptionService.getById(10)).thenReturn(sub);
        stubFillParsed(8, 7, 9);
        when(subscriptionEngine.pushBest(same(sub), eq(8), anyList())).thenReturn(true);

        assertTrue(service.pushSelected(10, 8, pushRequest("Great Escape S08E07-E09 1080p", 8)));
    }

    @Test
    void pushSelected_季包解析不出集号_维持原目标交给文件列表兜底() {
        PtSubscriptionPlus sub = tvSub(10, 8, 10);
        when(subscriptionService.getById(10)).thenReturn(sub);
        stubFillParsed(8, null, null);
        when(subscriptionEngine.pushBest(same(sub), eq(SubscriptionMatcher.SEASON_PACK), anyList())).thenReturn(true);

        assertTrue(service.pushSelected(10, SubscriptionMatcher.SEASON_PACK,
                pushRequest("Great Escape S08 2026 1080p WEB-DL", SubscriptionMatcher.SEASON_PACK)));
    }

    @Test
    void pushSelected_季号不符_拒绝推送() {
        PtSubscriptionPlus sub = tvSub(10, 8, 10);
        when(subscriptionService.getById(10)).thenReturn(sub);
        stubFillParsed(7, 7, null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.pushSelected(10, 7, pushRequest("Great Escape S07E07 1080p", 7)));

        assertTrue(e.getMessage().contains("第 7 季"), e.getMessage());
        verify(subscriptionEngine, never()).pushBest(any(), anyInt(), anyList());
    }

    @Test
    void pushSelected_电影_不做季集校验() {
        PtSubscriptionPlus sub = movieSub(20, "Some Movie", "2026");
        when(subscriptionService.getById(20)).thenReturn(sub);
        stubFillParsed(null, null, null);
        when(subscriptionEngine.pushBest(same(sub), eq(0), anyList())).thenReturn(true);

        assertTrue(service.pushSelected(20, 0, pushRequest("Some Movie 2026 2160p WEB-DL", 0)));
    }
}
