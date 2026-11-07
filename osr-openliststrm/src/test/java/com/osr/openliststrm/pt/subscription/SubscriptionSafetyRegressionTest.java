package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.*;
import com.osr.openliststrm.mybatisplus.service.*;
import com.osr.openliststrm.pt.downloader.*;
import com.osr.openliststrm.pt.filter.*;
import com.osr.openliststrm.pt.indexer.*;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.dto.*;
import com.osr.openliststrm.pt.task.AutoSearchService;
import com.osr.openliststrm.pt.ws.PtStatusWebSocket;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实解析、匹配、过滤、搜索编排和推送主干；只替换数据库、通知及外部网络。 */
class SubscriptionSafetyRegressionTest {
    private static final String WRONG_TITLE = "The Silent Storm S01E13 2160p TX WEB-DL AAC2.0 H.265-MWeb";
    private static final String WRONG_SUBTITLE = "宿敌 | 2024 | 中国大陆 | 剧情 悬疑 犯罪 | 奇道 | 廖凡 朱珠 | 第1季第13集";

    private static PtSubscriptionPlus sub(int season, int total) {
        var s = new PtSubscriptionPlus();
        s.setId(1); s.setTitle("Some Show"); s.setTmdbId("123");
        s.setMediaType("TV"); s.setStatus("ACTIVE"); s.setSeason(season); s.setTotalEpisodes(total);
        return s;
    }

    private static List<PtSubscriptionEpisodePlus> episodes(int n) {
        var out = new ArrayList<PtSubscriptionEpisodePlus>();
        for (int i = 1; i <= n; i++) {
            var e = new PtSubscriptionEpisodePlus();
            e.setId(i); e.setSubId(1); e.setEpisode(i); e.setState("MISSING"); out.add(e);
        }
        return out;
    }

    private static TorrentInfo torrent(String title, String guid, int files) {
        var t = new TorrentInfo();
        t.setTitle(title); t.setGuid(guid); t.setIndexerId(1); t.setFiles(files);
        t.setDownloadUrl("https://example.invalid/download/" + guid);
        t.setSeeders(10); t.setSize(1_000_000_000L);
        return t;
    }

    private static class Fixture {
        final IPtSubscriptionPlusService subs = mock(IPtSubscriptionPlusService.class);
        final IPtSubscriptionEpisodePlusService eps = mock(IPtSubscriptionEpisodePlusService.class);
        final IPtDownloadRecordPlusService records = mock(IPtDownloadRecordPlusService.class);
        final IPtDownloaderPlusService downloaders = mock(IPtDownloaderPlusService.class);
        final IPtFilterConfigPlusService configs = mock(IPtFilterConfigPlusService.class);
        final DownloaderClientFactory clients = mock(DownloaderClientFactory.class);
        final IDownloaderClient client = mock(IDownloaderClient.class);
        final SearchLogService logs = mock(SearchLogService.class);
        final IPtTorrentBlacklistPlusService blacklists = mock(IPtTorrentBlacklistPlusService.class);
        final TmdbSearchService tmdb = mock(TmdbSearchService.class);
        final IPtIndexerPlusService indexers = mock(IPtIndexerPlusService.class);
        final TorznabClient torznab = mock(TorznabClient.class);
        final PtFilterConfigPlus config = new PtFilterConfigPlus();
        final PtDownloaderPlus downloader = new PtDownloaderPlus();
        final SubscriptionMatcher matcher = new SubscriptionMatcher();
        final SubscriptionEngine engine;

        Fixture() {
            config.setMinSeeders(0); config.setMinSize(0L); config.setMaxSize(0L); config.setFreeOnly("0");
            config.setSortPriority("RESOLUTION,SEEDERS"); config.setResolutionPriority("2160p,1080p");
            downloader.setId(1); downloader.setName("download"); downloader.setTag("osr-pt");
            downloader.setEnabled("1"); downloader.setSavePath("/downloads");
            when(configs.getConfig()).thenReturn(config); when(blacklists.list()).thenReturn(List.of());
            when(records.list(any(Wrapper.class))).thenReturn(List.of());
            when(records.save(any())).thenAnswer(inv -> { inv.<PtDownloadRecordPlus>getArgument(0).setId(1); return true; });
            when(downloaders.list(any(Wrapper.class))).thenReturn(List.of(downloader));
            when(clients.get(any())).thenReturn(client);
            when(eps.update(any(), any(Wrapper.class))).thenReturn(true);
            when(logs.digestRejectionsSince(any(), anyLong())).thenReturn(SearchLogService.RejectionDigest.EMPTY);
            var idx = new PtIndexerPlus(); idx.setId(1); idx.setName("mock-indexer"); idx.setEnabled("1");
            when(indexers.listEnabled()).thenReturn(List.of(idx));
            when(torznab.getCaps(any())).thenReturn(IndexerCapability.NONE);
            engine = new SubscriptionEngine(subs, eps, records, downloaders, configs, clients,
                    new TorrentFilterEngine(), matcher, logs, blacklists, tmdb, indexers);
        }

        void subscription(PtSubscriptionPlus s, List<PtSubscriptionEpisodePlus> rows) {
            when(subs.getById(1)).thenReturn(s); when(subs.listActive()).thenReturn(List.of(s));
            when(eps.listBySubscription(1)).thenReturn(rows);
            when(eps.list(any(Wrapper.class))).thenReturn(rows);
        }

        SearchSupplementService search() {
            return new SearchSupplementService(indexers, torznab, engine, subs, eps, matcher,
                    new IndexerCapabilityCache(torznab, 300_000L), configs, new TorrentFilterEngine(),
                    tmdb, blacklists, logs, 0, 5, 0, 3);
        }
    }

    @Test
    void 谷雨订阅_宿敌原始资源_在RSS补搜和手动推送三处拒绝() throws Exception {
        var f = new Fixture(); var s = sub(1, 20);
        s.setTitle("谷雨"); s.setOriginalTitle("谷雨"); s.setEnglishTitle("The Silent Storm"); s.setYear("2026");
        f.subscription(s, episodes(20));
        var t = torrent(WRONG_TITLE, "wrong-show", 1); t.setDescription(WRONG_SUBTITLE);
        try (var tg = mockStatic(TgHelper.class); var ws = mockStatic(PtStatusWebSocket.class)) {
            assertEquals(0, f.engine.process(List.of(t)));
            assertEquals("2024", t.getParsedYear());
            assertNull(f.matcher.match(t, List.of(s)));
            when(f.torznab.search(any(), anyString())).thenReturn(List.of(t));
            assertFalse(f.search().supplement(1, 13, "The Silent Storm S01E13").isPushed());
            var request = new PushSelectedRequest();
            request.setTitle(WRONG_TITLE); request.setDescription(WRONG_SUBTITLE);
            var error = assertThrows(IllegalArgumentException.class, () -> f.search().pushSelected(1, 13, request));
            assertTrue(error.getMessage().contains("年份"));
            assertEquals(PushOutcome.Code.IDENTITY_CONFLICT, f.engine.pushManual(s, 13, List.of(t)).code());
            verify(f.client, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
        }
    }

    @Test
    void 英文相同且年份相同_副标题中文名冲突仍拒绝_已确认ID允许合法译名() {
        var f = new Fixture(); var s = sub(1, 20);
        s.setTitle("谷雨"); s.setEnglishTitle("The Silent Storm"); s.setYear("2026"); s.setImdbId("tt1234567");
        var t = torrent(WRONG_TITLE, "subtitle-conflict", 1);
        t.setDescription("宿敌 | 2026 | 剧情"); f.engine.fillParsed(t);
        assertNull(f.matcher.match(t, List.of(s)));
        assertTrue(f.matcher.workConflict(t, s).contains("中文名"));
        t.setImdbId("tt1234567");
        assertNotNull(f.matcher.match(t, List.of(s)));
    }

    @Test
    void 正确中文别名与缺副标题均兼容_剧情数字不作年份() {
        var f = new Fixture(); var s = sub(1, 20);
        s.setTitle("谷雨"); s.setEnglishTitle("The Silent Storm"); s.setYear("2026");
        var t = torrent(WRONG_TITLE, "valid", 1);
        for (String description : Arrays.asList(null, "故事发生在2024年的某座城市", "谷雨 第一季 / 另一译名 | 2026 | 剧情",
                "海外独家发布 | 内封简繁字幕", "【原盘首发】谷雨 | 2026 | 剧情")) {
            t.setDescription(description); f.engine.fillParsed(t);
            assertNotNull(f.matcher.match(t, List.of(s)), description);
        }
        assertNull(DescriptionAliases.year("2024年的故事 | 剧情介绍"));
        assertNull(DescriptionAliases.year("这是剧情。 | 2024 | 故事"));
    }

    @Test
    void 同名新剧不能匹配旧剧第一季_后续季允许晚于作品首播年() {
        var f = new Fixture(); var s = sub(1, 10); s.setTitle("Dark Matter"); s.setYear("2016");
        var t = torrent("Dark.Matter.2024.S01E01.2160p.WEB-DL", "new-series", 1); f.engine.fillParsed(t);
        assertNull(f.matcher.match(t, List.of(s)));
        s.setSeason(3); t.setTitle("Dark.Matter.2024.S03E01.2160p.WEB-DL"); f.engine.fillParsed(t);
        assertNotNull(f.matcher.match(t, List.of(s)));
    }

    @Test
    void 多个同名订阅缺少消歧证据_不按数据库顺序认领() {
        var f = new Fixture(); var a = sub(1, 10); var b = sub(1, 10); b.setId(2); b.setTmdbId("456");
        var t = torrent("Some.Show.S01E01.1080p", "ambiguous", 1); f.engine.fillParsed(t);
        assertNull(f.matcher.match(t, List.of(a, b)));
        assertNull(f.matcher.match(t, List.of(b, a)));
    }

    @Test
    void 忽略ID参数的索引器返回无关电影_不能跳过作品校验() throws Exception {
        var f = new Fixture(); var s = sub(0, 1); s.setMediaType("MOVIE"); s.setYear("2024"); s.setImdbId("tt1234567");
        var rows = episodes(1); rows.getFirst().setEpisode(0); f.subscription(s, rows);
        when(f.torznab.getCaps(any())).thenReturn(new IndexerCapability(true, false, false, false));
        var t = torrent("Totally.Unrelated.Movie.1980.1080p.BluRay", "unrelated", 1);
        when(f.torznab.searchByExternalId(any(), anyBoolean(), anyString(), anyString(), any(), any())).thenReturn(List.of(t));
        when(f.torznab.search(any(), anyString())).thenReturn(List.of());
        assertFalse(f.search().supplement(1, 0, "Some Show").isPushed());
        verify(f.records, never()).save(any());
        verify(f.client, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 绝对编号区间_真实RSS匹配至推送保持本地集号() throws Exception {
        var f = new Fixture(); var s = sub(3, 16); var rows = episodes(16);
        rows.forEach(e -> e.setTmdbEpisodeNumber(e.getEpisode() + 50)); f.subscription(s, rows);
        var t = torrent("Some.Show.S01E51-E66.2160p.WEB-DL", "absolute", 16);
        try (var tg = mockStatic(TgHelper.class); var ws = mockStatic(PtStatusWebSocket.class)) {
            assertEquals(1, f.engine.process(List.of(t)));
            var saved = ArgumentCaptor.forClass(PtDownloadRecordPlus.class); verify(f.records).save(saved.capture());
            assertEquals(1, saved.getValue().getEpisode()); assertEquals(16, saved.getValue().getEpisodeEnd());
            assertTrue(rows.stream().allMatch(e -> "IN_FLIGHT".equals(e.getState())));
            verify(f.client).addTorrent(any(), eq(t.getDownloadUrl()), anyString(), anyString(), eq(true));
        }
    }

    @Test
    void 不合格完整季包_不会挤掉合格的部分覆盖资源() throws Exception {
        var f = new Fixture(); var s = sub(1, 16); f.subscription(s, episodes(16));
        f.config.setResolutionWhitelist("2160p");
        var partial = torrent("Some.Show.S01.2160p.WEB-DL", "partial", 1);
        var complete = torrent("Some.Show.S01.1080p.WEB-DL", "complete", 16);
        try (var tg = mockStatic(TgHelper.class); var ws = mockStatic(PtStatusWebSocket.class)) {
            assertEquals(1, f.engine.process(List.of(partial, complete)));
            verify(f.client).addTorrent(any(), eq(partial.getDownloadUrl()), anyString(), anyString(), anyBoolean());
        }
    }

    @Test
    void 自动分配跳过满载机器_保留用户显式指定的等待语义() throws Exception {
        var f = new Fixture(); var s = sub(1, 1);
        f.downloader.setMaxConcurrent(1);
        var other = new PtDownloaderPlus(); other.setId(2); other.setName("available"); other.setMaxConcurrent(10);
        var t = torrent("Some.Show.S01E01.2160p.WEB-DL", "capacity", 1); f.engine.fillParsed(t);
        try (var tg = mockStatic(TgHelper.class); var ws = mockStatic(PtStatusWebSocket.class)) {
            var result = f.engine.handleGroup(new MatchResult(s, 1), List.of(t), f.config,
                    new HashMap<>(Map.of(1, episodes(1))), List.of(f.downloader, other),
                    new HashMap<>(Map.of(1, 1L, 2, 2L)), SearchLogService.SOURCE_MANUAL,
                    TorrentBlacklist.EMPTY, PushMode.FILL_MISSING);
            assertTrue(result.pushed()); verify(f.clients).get(same(other));
            s.setDownloaderId(1);
            var fixed = f.engine.handleGroup(new MatchResult(s, 1), List.of(t), f.config,
                    new HashMap<>(Map.of(1, episodes(1))), List.of(f.downloader, other),
                    new HashMap<>(Map.of(1, 1L, 2, 2L)), SearchLogService.SOURCE_MANUAL,
                    TorrentBlacklist.EMPTY, PushMode.FILL_MISSING);
            assertEquals(PushOutcome.Code.CAPACITY, fixed.code());
        }
    }

    @Test
    void 季搜索只有不合格版本_单集扩搜补到合格版本() throws Exception {
        var f = new Fixture(); var s = sub(1, 1); s.setSeasonPackPolicy("EPISODE"); f.subscription(s, episodes(1));
        f.config.setResolutionWhitelist("2160p");
        var poor = torrent("Some.Show.S01E01.1080p.WEB-DL", "poor", 1);
        var good = torrent("Some.Show.S01E01.2160p.WEB-DL", "good", 1);
        when(f.torznab.search(any(), anyString())).thenAnswer(inv ->
                "Some Show S01E01".equals(inv.getArgument(1)) ? List.of(good) : List.of(poor));
        try (var tg = mockStatic(TgHelper.class); var ws = mockStatic(PtStatusWebSocket.class)) {
            assertTrue(f.search().searchAndPushMissing(1).anyPushed());
            verify(f.torznab).search(any(), eq("Some Show S01E01"));
            verify(f.client).addTorrent(any(), eq(good.getDownloadUrl()), anyString(), anyString(), anyBoolean());
        }
    }

    @Test
    void 全部索引器故障_不推进有效搜索时间也不累计片源落空() throws Exception {
        var f = new Fixture(); var s = sub(1, 1); s.setAutoSearch("1"); f.subscription(s, episodes(1));
        when(f.torznab.search(any(), anyString())).thenThrow(new IOException("模拟索引器故障"));
        var search = f.search(); var summary = search.searchAndPushMissing(1);
        assertTrue(summary.isRetrySoon()); assertFalse(summary.isSkipped());
        verify(f.subs, never()).updateLastSearchTime(any(), any());
        when(f.subs.listAutoSearchCandidates()).thenReturn(List.of(s));
        try (var tg = mockStatic(TgHelper.class)) {
            new AutoSearchService(f.subs, f.configs, search, f.indexers, f.eps, 0).run();
            verify(f.subs, never()).updateAutoSearchMissState(any(), anyInt(), any());
            tg.verifyNoInteractions();
        }
    }

    @Test
    void 下载器满载_不扩大搜索且不记作片源不存在() throws Exception {
        var f = new Fixture(); var s = sub(1, 1); s.setSeasonPackPolicy("EPISODE"); f.subscription(s, episodes(1));
        f.downloader.setMaxConcurrent(1);
        var active = new PtDownloadRecordPlus(); active.setDownloaderId(1); active.setState("DOWNLOADING");
        when(f.records.list(any(Wrapper.class))).thenReturn(List.of(active));
        var t = torrent("Some.Show.S01E01.2160p", "full", 1);
        when(f.torznab.search(any(), anyString())).thenReturn(List.of(t));
        var summary = f.search().searchAndPushMissing(1);
        assertTrue(summary.isRetrySoon()); assertFalse(summary.anyPushed());
        verify(f.torznab, times(1)).search(any(), anyString());
        verify(f.subs, never()).updateLastSearchTime(any(), any());
    }

    @Test
    void 手动候选用服务端快照保留ID_拒绝篡改画像和跨订阅令牌() throws Exception {
        var f = new Fixture(); var s = sub(1, 1); s.setTitle("谷雨"); s.setYear("2026"); s.setImdbId("tt1234567");
        f.subscription(s, episodes(1));
        var t = torrent("Alternative.Title.2026.S01E01.2160p", "original", 1);
        t.setImdbId("tt1234567"); t.setDescription("另一种中文译名 | 2026 | 剧情");
        when(f.torznab.search(any(), anyString())).thenReturn(List.of(t));
        var search = f.search(); var result = search.supplement(1, 1, "谷雨", true);
        assertEquals(1, result.getCandidates().size());
        var request = new PushSelectedRequest(); request.setCandidateId(result.getCandidates().getFirst().getCandidateId());
        assertNotNull(request.getCandidateId());
        request.setTitle(WRONG_TITLE); request.setDescription(WRONG_SUBTITLE); request.setDownloadUrl("https://example.invalid/tampered");
        try (var tg = mockStatic(TgHelper.class); var ws = mockStatic(PtStatusWebSocket.class)) {
            assertTrue(search.pushSelected(1, 1, request).pushed());
            verify(f.client).addTorrent(any(), eq(t.getDownloadUrl()), anyString(), anyString(), anyBoolean());
        }
        var other = sub(1, 1); other.setId(2); when(f.subs.getById(2)).thenReturn(other);
        assertThrows(IllegalArgumentException.class, () -> search.pushSelected(2, 1, request));
        request.setCandidateId("expired-token");
        assertThrows(IllegalArgumentException.class, () -> search.pushSelected(1, 1, request));
    }

    @Test
    void 部分站点失败不能当作全站有效无结果() throws Exception {
        var f = new Fixture(); var s = sub(1, 1); f.subscription(s, episodes(1));
        var good = new PtIndexerPlus(); good.setId(1); good.setName("good");
        var bad = new PtIndexerPlus(); bad.setId(2); bad.setName("bad");
        when(f.indexers.listEnabled()).thenReturn(List.of(good, bad));
        when(f.torznab.search(same(good), anyString())).thenReturn(List.of());
        when(f.torznab.search(same(bad), anyString())).thenThrow(new IOException("模拟单站故障"));
        assertTrue(f.search().searchAndPushMissing(1).isRetrySoon());
        verify(f.subs, never()).updateLastSearchTime(any(), any());
    }

    @Test
    void 单集补发被上限截断_未搜的缺集不进入长周期片源退避() throws Exception {
        var f = new Fixture(); var s = sub(1, 6); s.setSeasonPackPolicy("EPISODE"); f.subscription(s, episodes(6));
        when(f.torznab.search(any(), anyString())).thenReturn(List.of());
        assertTrue(f.search().searchAndPushMissing(1).isRetrySoon());
        verify(f.subs, never()).updateLastSearchTime(any(), any());
        verify(f.torznab, times(6)).search(any(), anyString());
    }
}
