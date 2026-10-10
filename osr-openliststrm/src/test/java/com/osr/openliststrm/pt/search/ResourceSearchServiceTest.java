package com.osr.openliststrm.pt.search;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.filter.RejectCode;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.SearchCandidateDTO;
import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import com.osr.openliststrm.rename.RenameClientProvider;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.openliststrm.tmdb.TMDbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourceSearchServiceTest {

    private SearchSupplementService supplement;
    private SubscriptionEngine engine;
    private TorrentFilterEngine filterEngine;
    private IPtFilterConfigPlusService filterConfigService;
    private IPtDownloaderPlusService downloaderService;
    private DownloaderClientFactory clientFactory;
    private IDownloaderClient client;
    private IPtSubscriptionPlusService subscriptionService;
    private TmdbSearchService tmdbSearchService;
    private TMDbClient tmdbClient;
    private RenameClientProvider provider;
    private ResourceSearchService service;

    @BeforeEach
    void setUp() {
        supplement = mock(SearchSupplementService.class);
        engine = mock(SubscriptionEngine.class);
        filterEngine = mock(TorrentFilterEngine.class);
        filterConfigService = mock(IPtFilterConfigPlusService.class);
        IPtTorrentBlacklistPlusService blacklistService = mock(IPtTorrentBlacklistPlusService.class);
        downloaderService = mock(IPtDownloaderPlusService.class);
        clientFactory = mock(DownloaderClientFactory.class);
        client = mock(IDownloaderClient.class);
        subscriptionService = mock(IPtSubscriptionPlusService.class);
        tmdbSearchService = mock(TmdbSearchService.class);
        tmdbClient = mock(TMDbClient.class);
        provider = mock(RenameClientProvider.class);
        when(provider.tmdb()).thenReturn(tmdbClient);
        when(clientFactory.get(any())).thenReturn(client);
        when(filterConfigService.getConfig()).thenReturn(new PtFilterConfigPlus());
        when(blacklistService.list()).thenReturn(List.of());
        when(subscriptionService.list(any(QueryWrapper.class))).thenReturn(List.of());
        // DTO 转换是 SearchSupplementService 的真实逻辑，这里只按标题与做种数还原，够断言用
        when(supplement.toCandidateDtos(anyList())).thenAnswer(inv -> inv.<List<TorrentInfo>>getArgument(0).stream()
                .map(t -> SearchCandidateDTO.builder()
                        .title(t.getTitle()).seeders(t.getSeeders())
                        .parsedTitle(t.getParsedTitle())
                        .detailUrl(t.getDetailUrl()).build())
                .toList());
        service = new ResourceSearchService(supplement, engine, filterEngine, filterConfigService,
                blacklistService, downloaderService, clientFactory, subscriptionService, tmdbSearchService,
                provider, true, ResourceSearchService.MAX_TMDB_LOOKUPS, 30_000L);
    }

    private static TorrentInfo torrent(String title, int seeders) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        t.setSeeders(seeders);
        t.setDownloadUrl("http://site/dl/" + title);
        return t;
    }

    private static TorrentInfo torrent(String title, String parsedTitle, int season, int episode, int seeders) {
        TorrentInfo t = torrent(title, seeders);
        t.setParsedTitle(parsedTitle);
        t.setParsedSeason(season);
        t.setParsedEpisode(episode);
        return t;
    }

    private static TmdbSearchItem work(String tmdbId, String title) {
        TmdbSearchItem item = new TmdbSearchItem();
        item.setTmdbId(tmdbId);
        item.setTitle(title);
        item.setOriginalTitle(title);
        item.setYear("2023");
        return item;
    }

    // ---------------- 搜索 ----------------

    @Test
    void 关键词太短直接拒绝_不发请求() {
        assertThrows(IllegalArgumentException.class, () -> service.search(" 三 ", null, 1L, true));
        verify(supplement, never()).searchKeyword(anyString(), any());
    }

    @Test
    void 没有启用的索引器时直说_不返回空结果() {
        when(supplement.hasNoEnabledIndexer()).thenReturn(true);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.search("三体", null, 1L, true));
        assertTrue(e.getMessage().contains("没有启用中的索引器"));
    }

    /** 规则只标注不淘汰：被规则挡的那条照样在结果里，只是带上原因 */
    @Test
    void 全局规则只标注不淘汰_按做种数降序() {
        TorrentInfo low = torrent("三体 720p", 3);
        TorrentInfo high = torrent("三体 2160p", 50);
        when(supplement.searchKeyword(eq("三体"), any())).thenReturn(List.of(low, high));
        when(filterEngine.evaluate(anyList(), any(), any(), any())).thenReturn(List.of(
                TorrentFilterEngine.Verdict.reject(low, RejectCode.RESOLUTION_NOT_ALLOWED, "分辨率 720p 不在白名单 [2160p]"),
                TorrentFilterEngine.Verdict.accept(high)));

        ResourceSearchService.Result result = service.search(" 三体 ", List.of(1), 1L, true);

        assertEquals(2, result.candidateCount());
        assertEquals(1, result.rejectedCount());
        assertEquals("三体 2160p", result.items().get(0).getTitle(), "做种多的排前面");
        assertNull(result.items().get(0).getRuleRejection());
        assertEquals("分辨率不在白名单", result.items().get(1).getRuleRejection());
        assertEquals("分辨率 720p 不在白名单 [2160p]", result.items().get(1).getRuleRejectionDetail());
        verify(engine).markHitAndRun(anyList());
        verify(supplement).searchKeyword("三体", List.of(1));
    }

    @Test
    void 规则判定出错时照样返回结果_只是不带标注() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(torrent("三体", 1)));
        when(filterEngine.evaluate(anyList(), any(), any(), any())).thenThrow(new IllegalStateException("坏配置"));

        ResourceSearchService.Result result = service.search("三体", null, 1L, true);

        assertEquals(1, result.candidateCount());
        assertEquals(0, result.rejectedCount());
    }

    /**
     * 规则标注与作品身份按下标回填，前提是 DTO 与种子一一对应。前提不成立时必须直接失败——
     * 静默继续的话，结论会套到别的行上，而串了行的身份带着 TMDb 链接，没有任何迹象可查。
     */
    @Test
    void 候选DTO与种子不一一对应时直接失败_不把串了行的结果交出去() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(
                List.of(torrent("三体 S01E01", "三体", 1, 1, 3), torrent("三体 S01E02", "三体", 1, 2, 2)));
        when(supplement.toCandidateDtos(anyList()))
                .thenReturn(List.of(SearchCandidateDTO.builder().title("三体 S01E02").build()));

        assertThrows(IllegalStateException.class, () -> service.search("三体", null, 1L, true));
    }

    // ---------------- 作品识别 ----------------

    /** 同一部剧的 12 集是 12 条种子、一个作品：识别必须按归一化标题去重，逐条查会打爆配额 */
    @Test
    void 同一部剧的多条种子只识别一次() {
        when(supplement.searchKeyword(anyString(), any())).thenAnswer(inv -> {
            List<TorrentInfo> list = new java.util.ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                list.add(torrent("Fights Break Sphere S05E" + i + " 1080p", "Fights Break Sphere", 5, i, i));
            }
            return list;
        });
        when(tmdbClient.matchTmdbId(eq("TV"), any(MediaInfo.class))).thenReturn("79481");
        when(tmdbSearchService.describeWork(eq("TV"), eq("79481"))).thenReturn(work("79481", "斗破苍穹"));

        ResourceSearchService.Result result = service.search("斗破苍穹", null, 1L, true);

        assertEquals(1, result.distinctWorks());
        assertEquals(1, result.identifiedWorks());
        verify(tmdbClient, times(1)).matchTmdbId(anyString(), any(MediaInfo.class));
        assertEquals("斗破苍穹", result.items().get(0).getMatchedTitle());
        assertEquals("79481", result.items().get(11).getMatchedTmdbId(), "同一作品的每一条都填上同一个身份");
    }

    /** 索引器已经给出 tmdbid 时直接用，不再靠标题去猜——那是站点自填的、比标题强得多的信号 */
    @Test
    void 索引器已给TMDb_ID时不再走标题匹配() {
        TorrentInfo t = torrent("Re Zero S01E01", "Re Zero kara Hajimeru Isekai Seikatsu", 1, 1, 5);
        t.setTmdbId("1600");
        t.setCategories(List.of(5000));
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(t));
        when(tmdbSearchService.describeWork(eq("TV"), eq("1600"))).thenReturn(work("1600", "Re:从零开始的异世界生活"));

        ResourceSearchService.Result result = service.search("re zero", null, 1L, true);

        verify(tmdbClient, never()).matchTmdbId(anyString(), any(MediaInfo.class));
        assertEquals("1600", result.items().get(0).getMatchedTmdbId());
    }

    /** 分类判不出大类时不能采纳索引器的 tmdbid：TMDb 的 movie/1399 与 tv/1399 是两部不相干的作品 */
    @Test
    void 分类判不出类型时不采纳索引器给的tmdbId_退回标题匹配() {
        TorrentInfo t = torrent("Re Zero S01E01", "Re Zero", 1, 1, 5);
        t.setTmdbId("1600");
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(t));
        when(tmdbClient.matchTmdbId(eq("TV"), any(MediaInfo.class))).thenReturn("2222");
        when(tmdbSearchService.describeWork(eq("TV"), eq("2222"))).thenReturn(work("2222", "Re:从零开始的异世界生活"));

        ResourceSearchService.Result result = service.search("re zero", null, 1L, true);

        verify(tmdbClient).matchTmdbId(anyString(), any(MediaInfo.class));
        assertEquals("2222", result.items().get(0).getMatchedTmdbId());
    }

    /** 整季包解析不出季号会被当成电影，分类能判时以分类为准 */
    @Test
    void 索引器给的tmdbId按分类判类型_不按有没有季号猜() {
        TorrentInfo t = torrent("Re Zero S01 [1080p]", "Re Zero", 0, 0, 5);
        t.setParsedSeason(null);
        t.setParsedEpisode(null);
        t.setTmdbId("1600");
        t.setCategories(List.of(5000));
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(t));
        when(tmdbSearchService.describeWork(eq("TV"), eq("1600"))).thenReturn(work("1600", "Re:从零开始的异世界生活"));

        ResourceSearchService.Result result = service.search("re zero", null, 1L, true);

        assertEquals("TV", result.items().get(0).getMediaType());
    }

    /** 去重键必须带类型：同名的电影与剧集是两个作品，第一条的身份不能套给全部 */
    @Test
    void 去重键必须带类型_同名电影与剧集算两个作品() {
        TorrentInfo movie = torrent("From 1985", 3);
        movie.setParsedTitle("From");
        TorrentInfo tv = torrent("From S01E01", "From", 1, 1, 2);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(movie, tv));
        when(tmdbClient.matchTmdbId(eq("MOVIE"), any(MediaInfo.class))).thenReturn("111");
        when(tmdbClient.matchTmdbId(eq("TV"), any(MediaInfo.class))).thenReturn("222");
        when(tmdbSearchService.describeWork(eq("MOVIE"), eq("111"))).thenReturn(work("111", "From（电影）"));
        when(tmdbSearchService.describeWork(eq("TV"), eq("222"))).thenReturn(work("222", "From（剧集）"));

        ResourceSearchService.Result result = service.search("from", null, 1L, true);

        assertEquals(2, result.distinctWorks());
        assertEquals("111", result.items().get(0).getMatchedTmdbId(), "做种多的电影排在前");
        assertEquals("222", result.items().get(1).getMatchedTmdbId());
    }

    /**
     * 剧集种子上的年份是本季播出年：进键会把同一部剧按季劈开，一部多季的剧占掉好几组、
     * 更容易撞上限让整页都不识别。有年份与没年份的同理不能劈开。
     */
    @Test
    void 剧集的归并键不带年份_同一部剧的各季算一组() {
        TorrentInfo s1 = torrent("Dark Matter S01 2024 1080p", "Dark Matter", 1, 1, 3);
        s1.setParsedYear("2024");
        TorrentInfo s2 = torrent("Dark Matter S02 2026 1080p", "Dark Matter", 2, 1, 2);
        s2.setParsedYear("2026");
        TorrentInfo noYear = torrent("Dark Matter S02E03 1080p", "Dark Matter", 2, 3, 1);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(s1, s2, noYear));
        when(tmdbClient.matchTmdbId(eq("TV"), any(MediaInfo.class))).thenReturn("108978");
        when(tmdbSearchService.describeWork(eq("TV"), eq("108978"))).thenReturn(work("108978", "人生复本"));

        ResourceSearchService.Result result = service.search("dark matter", null, 1L, true);

        assertEquals(1, result.distinctWorks());
        verify(tmdbClient, times(1)).matchTmdbId(anyString(), any(MediaInfo.class));
        assertEquals("108978", result.items().get(2).getMatchedTmdbId());
    }

    /** 电影的年份是上映年，同名不同年就是两部作品（翻拍），年份必须进键 */
    @Test
    void 电影的归并键带年份_同名翻拍算两组() {
        TorrentInfo old = torrent("Dune 1984 1080p", 3);
        old.setParsedTitle("Dune");
        old.setParsedYear("1984");
        TorrentInfo remake = torrent("Dune 2021 2160p", 2);
        remake.setParsedTitle("Dune");
        remake.setParsedYear("2021");
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(old, remake));
        when(tmdbClient.matchTmdbId(eq("MOVIE"), any(MediaInfo.class)))
                .thenAnswer(inv -> "1984".equals(inv.<MediaInfo>getArgument(1).getYear()) ? "841" : "438631");
        when(tmdbSearchService.describeWork(eq("MOVIE"), anyString())).thenAnswer(inv -> work(inv.getArgument(1), "沙丘"));

        ResourceSearchService.Result result = service.search("dune", null, 1L, true);

        assertEquals(2, result.distinctWorks());
        assertEquals("841", result.items().get(0).getMatchedTmdbId());
        assertEquals("438631", result.items().get(1).getMatchedTmdbId());
    }

    /**
     * 预算到点就不再等：已识别的照常返回，没跑完的那组不带身份，并显式标记「没跑完」——
     * 不标的话那一行会显示「识别不出」，而真相是还没识别。
     */
    @Test
    void 识别超出预算时返回已识别的部分并标记没跑完() {
        service = new ResourceSearchService(supplement, engine, filterEngine, filterConfigService,
                mock(IPtTorrentBlacklistPlusService.class), downloaderService, clientFactory, subscriptionService,
                tmdbSearchService, provider, true, ResourceSearchService.MAX_TMDB_LOOKUPS, 300L);
        TorrentInfo fast = torrent("三体 S01E01", "三体", 1, 1, 3);
        TorrentInfo slow = torrent("慢 S01E01", "慢", 1, 1, 2);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(fast, slow));
        when(tmdbClient.matchTmdbId(anyString(), any(MediaInfo.class))).thenAnswer(inv -> {
            if ("慢".equals(inv.<MediaInfo>getArgument(1).getOriginalTitle())) {
                Thread.sleep(3_000);
            }
            return "98325";
        });
        when(tmdbSearchService.describeWork(anyString(), anyString())).thenReturn(work("98325", "三体"));

        long start = System.currentTimeMillis();
        ResourceSearchService.Result result = service.search("三体", null, 1L, true);

        assertTrue(System.currentTimeMillis() - start < 2_000, "不能等慢的那组跑完");
        assertTrue(result.tmdbLookupTruncated());
        assertEquals(2, result.distinctWorks());
        assertEquals(1, result.identifiedWorks());
        assertEquals("98325", result.items().get(0).getMatchedTmdbId());
        assertNull(result.items().get(1).getMatchedTmdbId());
    }

    // ---------------- 同名剧 ----------------

    private static TorrentInfo titled(String title, String parsedTitle, Integer season, Integer episode,
                                      String year, int seeders) {
        TorrentInfo t = torrent(title, seeders);
        t.setParsedTitle(parsedTitle);
        t.setParsedSeason(season);
        t.setParsedEpisode(episode);
        t.setParsedYear(year);
        return t;
    }

    private static TmdbSearchService.SeriesShape shape(Integer first, int total, int... seasonAndYear) {
        java.util.NavigableMap<Integer, Integer> seasons = new java.util.TreeMap<>();
        for (int i = 0; i + 1 < seasonAndYear.length; i += 2) {
            seasons.put(seasonAndYear[i], seasonAndYear[i + 1]);
        }
        return new TmdbSearchService.SeriesShape(first, seasons, total);
    }

    /** 动画《航海王》(1999, tv/37854) 与真人版《海贼王》(2023, tv/111110)：TMDb 按年份与集号分得清这两部 */
    private void stubOnePiece() {
        when(tmdbClient.matchTmdbId(eq("TV"), any(MediaInfo.class))).thenAnswer(inv -> {
            MediaInfo info = inv.getArgument(1);
            boolean anime = "1999".equals(info.getYear())
                    || (info.getEpisode() != null && Integer.parseInt(info.getEpisode()) > 100);
            return anime ? "37854" : "111110";
        });
        when(tmdbSearchService.describeWork(eq("TV"), eq("37854"))).thenReturn(work("37854", "航海王"));
        when(tmdbSearchService.describeWork(eq("TV"), eq("111110"))).thenReturn(work("111110", "海贼王"));
        when(tmdbSearchService.seriesShape("37854")).thenReturn(shape(1999, 1180, 1, 1999, 2, 2001, 22, 2019, 23, 2026));
        when(tmdbSearchService.seriesShape("111110")).thenReturn(shape(2023, 16, 1, 2023, 2, 2026));
    }

    /**
     * 实际反馈：{@code One Piece S23E1171 1999 1080p CR WEB-DL} 在资源搜索页被标成真人版《海贼王》，
     * 而重命名同一个文件认得出是动画。同名的两部剧标题、类型都相同、落在同一组，只问代表种子
     * （做种最多的真人版季包）的话整组都跟着它走；重命名逐个文件识别，集号 1171 过不了真人版的集数反证。
     */
    @Test
    void 同名剧在组内拆开_动画的集数与年份放在真人版上说不通() {
        TorrentInfo live = titled("One Piece S01 2023 2160p NF WEB-DL", "One Piece", 1, null, "2023", 90);
        TorrentInfo anime = titled("One Piece S23E1171 1999 1080p CR WEB-DL H.264 AAC-FROGWeb", "One Piece", 23, 1171, "1999", 10);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(live, anime));
        stubOnePiece();

        ResourceSearchService.Result result = service.search("One Piece", null, 1L, true);

        assertEquals(1, result.distinctWorks(), "仍是一组标题，只是组内分属两部作品");
        assertEquals(1, result.identifiedWorks());
        assertEquals("111110", result.items().get(0).getMatchedTmdbId());
        assertEquals("海贼王", result.items().get(0).getMatchedTitle());
        assertEquals("37854", result.items().get(1).getMatchedTmdbId());
        assertEquals("航海王", result.items().get(1).getMatchedTitle());
    }

    /** 反过来代表是动画时，真人版靠「年份不在这一季的播出区间里」挑出来：S01 标 2023，而动画第 1 季是 1999~2001 */
    @Test
    void 同名剧在组内拆开_代表是老剧时新剧按季的播出年挑出来() {
        TorrentInfo anime = titled("One Piece S21E0950 2019 1080p", "One Piece", 21, 950, "2019", 90);
        TorrentInfo live = titled("One Piece S01E03 2023 1080p NF WEB-DL", "One Piece", 1, 3, "2023", 10);
        TorrentInfo liveS2 = titled("One Piece S02 2026 2160p NF WEB-DL", "One Piece", 2, null, "2026", 5);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(anime, live, liveS2));
        stubOnePiece();

        ResourceSearchService.Result result = service.search("One Piece", null, 1L, true);

        assertEquals("37854", result.items().get(0).getMatchedTmdbId());
        assertEquals("111110", result.items().get(1).getMatchedTmdbId());
        assertEquals("111110", result.items().get(2).getMatchedTmdbId());
    }

    /** 没写年份、集号也不出格的种子没有任何可核对的东西，跟着代表走，不为它多发请求 */
    @Test
    void 没有年份也没有出格集号的种子跟着代表走() {
        TorrentInfo live = titled("One Piece S01 2023 2160p NF WEB-DL", "One Piece", 1, null, "2023", 90);
        TorrentInfo bare = titled("One Piece S01E05 1080p WEB-DL", "One Piece", 1, 5, null, 10);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(live, bare));
        stubOnePiece();

        ResourceSearchService.Result result = service.search("One Piece", null, 1L, true);

        assertEquals("111110", result.items().get(1).getMatchedTmdbId());
        verify(tmdbClient, times(1)).matchTmdbId(anyString(), any(MediaInfo.class));
    }

    /**
     * 年份上界是弱信号：{@code The.Office.S03E05.2019} 标的是压制年。它只负责挑出「值得再问一次」的，
     * 裁决是 TMDb 的回答——再问得到的还是同一部作品，就全部收下，不能留成识别不出，也不能一条条问下去。
     */
    @Test
    void 再认一轮还是同一部作品时全部收下_不逐条问下去() {
        TorrentInfo first = titled("The Office S01 2005 1080p", "The Office", 1, null, "2005", 90);
        TorrentInfo late1 = titled("The Office S03E05 2019 1080p", "The Office", 3, 5, "2019", 30);
        TorrentInfo late2 = titled("The Office S03E06 2019 1080p", "The Office", 3, 6, "2019", 20);
        TorrentInfo late3 = titled("The Office S04E01 2020 1080p", "The Office", 4, 1, "2020", 10);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(first, late1, late2, late3));
        when(tmdbClient.matchTmdbId(eq("TV"), any(MediaInfo.class))).thenReturn("2316");
        when(tmdbSearchService.describeWork(eq("TV"), eq("2316"))).thenReturn(work("2316", "办公室"));
        when(tmdbSearchService.seriesShape("2316")).thenReturn(shape(2005, 201, 1, 2005, 2, 2005, 3, 2006, 4, 2007, 5, 2008));

        ResourceSearchService.Result result = service.search("The Office", null, 1L, true);

        for (int i = 0; i < 4; i++) {
            assertEquals("2316", result.items().get(i).getMatchedTmdbId(), "第 " + i + " 条");
        }
        verify(tmdbClient, times(2)).matchTmdbId(anyString(), any(MediaInfo.class));
    }

    /** 核对只在有依据时判矛盾：缺年份、缺季信息、缺总集数，各自都不判 */
    @Test
    void 核对种子与剧集是否说得通_缺什么就不判什么() {
        TmdbSearchService.SeriesShape live = shape(2023, 16, 1, 2023, 2, 2026);
        TmdbSearchService.SeriesShape anime = shape(1999, 1180, 1, 1999, 2, 2001, 21, 2019, 22, 2023);

        assertTrue(ResourceSearchService.contradicts(titled("x", "x", 23, 1171, "1999", 1), live), "集号装不下");
        assertTrue(ResourceSearchService.contradicts(titled("x", "x", 1, null, "1999", 1), live), "比首播年早得多");
        assertTrue(ResourceSearchService.contradicts(titled("x", "x", 1, 3, "2023", 1), anime), "第 1 季早在 2001 年前后就播完了");
        assertFalse(ResourceSearchService.contradicts(titled("x", "x", 21, 950, "2021", 1), anime), "一季跨几年播，年份在本季与下一季之间");
        assertFalse(ResourceSearchService.contradicts(titled("x", "x", 22, 1100, "2031", 1), anime), "最新一季没有下一季，不设上界");
        assertFalse(ResourceSearchService.contradicts(titled("x", "x", 9, 20, "2031", 1), anime), "这部剧没登记这一季，年份偏晚不算矛盾");
        assertFalse(ResourceSearchService.contradicts(titled("x", "x", 1, 5, null, 1), live), "没写年份");
        assertFalse(ResourceSearchService.contradicts(titled("x", "x", 1, 20, "2024", 1), live), "集号略超总集数在余量内");
        assertFalse(ResourceSearchService.contradicts(titled("x", "x", 1, 900, "bad", 1), shape(null, 0)), "什么依据都没有");
    }

    // ---------------- 别名兜底 ----------------

    private static TorrentInfo frieren(int episode, String description) {
        TorrentInfo t = torrent("Sousou no Frieren S01E" + episode + " 1080p", "Sousou no Frieren", 1, episode, episode);
        t.setDescription(description);
        return t;
    }

    /** 标题那条路只认解析出的片名；别名那条路只认别名。按片名分流，免得桩互相串 */
    private void stubMatchByTitle(java.util.Map<String, String> idByTitle) {
        when(tmdbClient.matchTmdbId(anyString(), any(MediaInfo.class)))
                .thenAnswer(inv -> idByTitle.get(inv.<MediaInfo>getArgument(1).getOriginalTitle()));
    }

    /**
     * 罗马音命名的种子标题对不上 TMDb 的任何一个名字，能对上的名字在 description 的别名列表里。
     * 实测「Sousou no Frieren」100 条里 83 条因此识别不出，而副标题里明明写着「葬送的芙莉莲」。
     */
    @Test
    void 标题识别落空时拿description别名再试() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(
                frieren(2, "葬送的芙莉莲 / 葬送のフリーレン | 第02集 | 内封简繁"),
                frieren(1, "葬送的芙莉莲 / 葬送のフリーレン | 第01集 | 内封简繁")));
        stubMatchByTitle(java.util.Map.of("葬送的芙莉莲", "209867"));
        when(tmdbSearchService.describeWork(eq("TV"), eq("209867"))).thenReturn(work("209867", "葬送的芙莉莲"));

        ResourceSearchService.Result result = service.search("Sousou no Frieren", null, 1L, true);

        assertEquals(1, result.identifiedWorks());
        assertEquals("209867", result.items().get(0).getMatchedTmdbId());
        assertEquals("葬送的芙莉莲", result.items().get(1).getMatchedTitle());
    }

    /** 别名尾部的季号要剥掉再问：TMDb 的条目名里从来不带季号，带着它搜必然落空 */
    @Test
    void 别名尾部的季号剥掉后再试() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(
                frieren(1, "葬送的芙莉莲 第二季 / 葬送のフリーレン 第2期 | 第01集")));
        stubMatchByTitle(java.util.Map.of("葬送的芙莉莲", "209867"));
        when(tmdbSearchService.describeWork(eq("TV"), eq("209867"))).thenReturn(work("209867", "葬送的芙莉莲"));

        ResourceSearchService.Result result = service.search("Sousou no Frieren", null, 1L, true);

        assertEquals("209867", result.items().get(0).getMatchedTmdbId());
    }

    /**
     * 别名这条路比标题更严：搜到的作品必须与别名全等才采纳。description 是自由文本，
     * 带修饰的别名搜回不相干的结果、靠「年份接近」混过门槛时，假身份会带着 TMDb 链接出现在页面上。
     */
    @Test
    void 别名搜到的作品与别名不全等时不采纳() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(
                frieren(1, "【原盘首发】葬送的芙莉莲 | 第01集")));
        stubMatchByTitle(java.util.Map.of("【原盘首发】葬送的芙莉莲", "999"));
        when(tmdbSearchService.describeWork(eq("TV"), eq("999"))).thenReturn(work("999", "某部不相干的剧"));

        ResourceSearchService.Result result = service.search("Sousou no Frieren", null, 1L, true);

        assertEquals(0, result.identifiedWorks());
        assertNull(result.items().get(0).getMatchedTmdbId());
    }

    /** 干净的作品名每条种子都会写，修饰语各写各的：按出现次数排，前者先试，且总共只试有限个 */
    @Test
    void 别名按组内出现次数排序_只试前几个() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(
                frieren(4, "杂项甲 / 葬送的芙莉莲 | 第04集"),
                frieren(3, "杂项乙 / 葬送的芙莉莲 | 第03集"),
                frieren(2, "杂项丙 / 葬送的芙莉莲 | 第02集"),
                frieren(1, "杂项丁 / 葬送的芙莉莲 | 第01集")));
        stubMatchByTitle(java.util.Map.of("葬送的芙莉莲", "209867"));
        when(tmdbSearchService.describeWork(eq("TV"), eq("209867"))).thenReturn(work("209867", "葬送的芙莉莲"));

        ResourceSearchService.Result result = service.search("Sousou no Frieren", null, 1L, true);

        assertEquals("209867", result.items().get(0).getMatchedTmdbId());
        // 标题 1 次 + 排第一的别名 1 次：出现四次的干净名字排在只出现一次的杂项前面
        verify(tmdbClient, times(2)).matchTmdbId(anyString(), any(MediaInfo.class));
    }

    @Test
    void 别名全部落空时最多试有限个_不把配额打光() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(
                frieren(1, "甲甲 / 乙乙 / 丙丙 / 丁丁 / 戊戊 | 第01集")));
        when(tmdbClient.matchTmdbId(anyString(), any(MediaInfo.class))).thenReturn(null);

        service.search("Sousou no Frieren", null, 1L, true);

        verify(tmdbClient, times(1 + ResourceSearchService.MAX_ALIAS_ATTEMPTS))
                .matchTmdbId(anyString(), any(MediaInfo.class));
    }

    /** 标题已经识别出来的组不走别名：别名是兜底，不是第二意见 */
    @Test
    void 标题识别成功时不碰别名() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(
                frieren(1, "葬送的芙莉莲 / 葬送のフリーレン | 第01集")));
        stubMatchByTitle(java.util.Map.of("Sousou no Frieren", "209867"));
        when(tmdbSearchService.describeWork(eq("TV"), eq("209867"))).thenReturn(work("209867", "葬送的芙莉莲"));

        service.search("Sousou no Frieren", null, 1L, true);

        verify(tmdbClient, times(1)).matchTmdbId(anyString(), any(MediaInfo.class));
    }

    /** TMDb key 没配时整页一个都没识别，必须显式说出来——每行显示「识别不出」会被读成「这些种子不属于任何作品」 */
    @Test
    void TMDb_key未配置时显式标记没识别() {
        when(provider.tmdb()).thenReturn(null);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(torrent("三体 S01E01", "三体", 1, 1, 3)));

        ResourceSearchService.Result result = service.search("三体", null, 1L, true);

        assertTrue(result.tmdbLookupUnavailable());
        assertFalse(result.tmdbLookupSkipped());
        assertNull(result.items().get(0).getMatchedTmdbId());
    }

    /**
     * 超上限时识别种子最多的那些组，而不是整页放弃：实测「Shingeki no Kyojin」100 条结果有 33 组标题，
     * 整页放弃等于最需要识别的那类搜索一条都认不出。没问的组必须显式标记——
     * 不标的话那些行会显示「识别不出」，而真相是没识别。
     */
    @Test
    void 标题组数超过上限时只识别种子最多的那些组并显式标记() {
        int max = ResourceSearchService.MAX_TMDB_LOOKUPS;
        List<TorrentInfo> list = new java.util.ArrayList<>();
        // 做种最少、但组内有三条种子的那一组：按做种数排它在最后，按组大小排它在最前
        for (int ep = 1; ep <= 3; ep++) {
            list.add(torrent("big E" + ep, "big", 1, ep, 0));
        }
        for (int i = 0; i < max + 4; i++) {
            list.add(torrent("x" + i, "work" + i, 1, 1, 100 - i));
        }
        when(supplement.searchKeyword(anyString(), any())).thenReturn(list);
        when(tmdbClient.matchTmdbId(anyString(), any(MediaInfo.class)))
                .thenAnswer(inv -> "big".equals(inv.<MediaInfo>getArgument(1).getOriginalTitle()) ? "7" : null);
        when(tmdbSearchService.describeWork(anyString(), eq("7"))).thenReturn(work("7", "大组"));

        ResourceSearchService.Result result = service.search("三体", null, 1L, true);

        assertEquals(max + 5, result.distinctWorks());
        assertTrue(result.tmdbLookupSkipped());
        assertEquals(1, result.identifiedWorks());
        verify(tmdbClient, times(max)).matchTmdbId(anyString(), any(MediaInfo.class));
        // 大组排在结果末尾（做种最少），照样被识别
        assertEquals("7", result.items().get(result.items().size() - 1).getMatchedTmdbId());
        // 被上限挡在外面的是只有一条种子里做种最少的那几组
        assertNull(result.items().get(max + 3).getMatchedTmdbId());
    }

    /** 识别不出照样返回结果、只是不带作品身份——不能让一次 TMDb 落空把整页搜索拖垮 */
    @Test
    void 识别落空时结果照回_只是不带作品身份() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(torrent("怪奇物语 1985", "From", 4, 10, 3)));
        when(tmdbClient.matchTmdbId(anyString(), any(MediaInfo.class))).thenReturn(null);

        ResourceSearchService.Result result = service.search("from", null, 1L, true);

        assertEquals(1, result.candidateCount());
        assertFalse(result.tmdbLookupSkipped());
        assertNull(result.items().get(0).getMatchedTmdbId());
        assertNull(result.items().get(0).getMatchedTitle());
    }

    @Test
    void TMDb识别抛异常时不整页失败() {
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(torrent("三体 S01E01", "三体", 1, 1, 3)));
        when(tmdbClient.matchTmdbId(anyString(), any(MediaInfo.class))).thenThrow(new IllegalStateException("429"));

        ResourceSearchService.Result result = service.search("三体", null, 1L, true);

        assertEquals(1, result.candidateCount());
        assertNull(result.items().get(0).getMatchedTmdbId());
    }

    @Test
    void 已订阅标记按tmdbId回填() {
        TorrentInfo t = torrent("三体 S01E01", "三体", 1, 1, 3);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(t));
        when(tmdbClient.matchTmdbId(anyString(), any(MediaInfo.class))).thenReturn("98325");
        when(tmdbSearchService.describeWork(anyString(), anyString())).thenReturn(work("98325", "三体"));
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setTmdbId("98325");
        sub.setMediaType("TV");
        when(subscriptionService.list(any(QueryWrapper.class))).thenReturn(List.of(sub));

        ResourceSearchService.Result result = service.search("三体", null, 1L, true);

        assertTrue(result.items().get(0).isSubscribed());
    }

    /** 同一个 tmdbId 在 TMDb 上是两套编号：库里订的是电影，种子是剧集，不能算已订阅 */
    @Test
    void 已订阅标记必须分电影与剧集() {
        TorrentInfo t = torrent("三体 S01E01", "三体", 1, 1, 3);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(t));
        when(tmdbClient.matchTmdbId(anyString(), any(MediaInfo.class))).thenReturn("1399");
        when(tmdbSearchService.describeWork(anyString(), anyString())).thenReturn(work("1399", "三体"));
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setTmdbId("1399");
        sub.setMediaType("MOVIE");
        when(subscriptionService.list(any(QueryWrapper.class))).thenReturn(List.of(sub));

        ResourceSearchService.Result result = service.search("三体", null, 1L, true);

        assertFalse(result.items().get(0).isSubscribed(), "tv/1399 与 movie/1399 是两部作品");
    }

    private String subscribedQuerySql(Long me, boolean admin) {
        TorrentInfo t = torrent("三体 S01E01", "三体", 1, 1, 3);
        when(supplement.searchKeyword(anyString(), any())).thenReturn(List.of(t));
        when(tmdbClient.matchTmdbId(anyString(), any(MediaInfo.class))).thenReturn("98325");
        when(tmdbSearchService.describeWork(anyString(), anyString())).thenReturn(work("98325", "三体"));

        service.search("三体", null, me, admin);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<QueryWrapper<PtSubscriptionPlus>> wrapper =
                org.mockito.ArgumentCaptor.forClass(QueryWrapper.class);
        verify(subscriptionService).list(wrapper.capture());
        return wrapper.getValue().getCustomSqlSegment();
    }

    /**
     * 已订阅标记是权限逻辑：别人的订阅不能透过这个标记露出来。口径与订阅页同一份——
     * 自己的 + 无归属的公共订阅（owner_user_id 为 NULL 的历史订阅）。
     */
    @Test
    void 非管理员的已订阅标记只看自己的与公共订阅() {
        String sql = subscribedQuerySql(7L, false);

        assertTrue(sql.contains("tmdb_id IN"), sql);
        assertTrue(sql.contains("owner_user_id ="), sql);
        assertTrue(sql.contains("owner_user_id IS NULL"), sql);
        // 两个归属条件必须括在一起再与 tmdb_id 条件相与：不括的话 OR 会把全部公共订阅放进来
        assertTrue(sql.replaceAll("\\s+", " ").matches(".*AND \\(.*owner_user_id = .* OR .*owner_user_id IS NULL.*\\).*"), sql);
    }

    @Test
    void 管理员的已订阅标记看全部订阅() {
        String sql = subscribedQuerySql(1L, true);

        assertTrue(sql.contains("tmdb_id IN"), sql);
        assertFalse(sql.contains("owner_user_id"), sql);
    }

    // ---------------- 直接下载 ----------------

    private static PtDownloaderPlus downloader(String enabled, String role) {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(7);
        d.setName("qb");
        d.setEnabled(enabled);
        d.setRole(role);
        d.setSavePath("/downloads/");
        d.setSmartClassifyLevel("CATEGORY");
        return d;
    }

    private static ResourcePushRequest pushRequest(String title) {
        ResourcePushRequest r = new ResourcePushRequest();
        r.setDownloaderId(7);
        r.setTitle(title);
        r.setDownloadUrl("http://site/dl/1");
        return r;
    }

    @Test
    void 直接下载_打独立标签_不暂停_按智能分类落盘() throws Exception {
        when(downloaderService.getById(7)).thenReturn(downloader("1", "DOWNLOAD"));

        String savePath = service.push(pushRequest("Dune.Part.Two.2024.2160p"));

        // fillParsed 被 mock 掉，解析不出季集号，按电影落盘
        assertEquals("/downloads/电影", savePath);
        verify(client).addTorrent(any(), eq("http://site/dl/1"), eq("/downloads/电影"),
                eq(ResourceSearchService.MANUAL_TAG), eq(false));
    }

    @Test
    void 直接下载_剧集落进剧集目录() throws Exception {
        when(downloaderService.getById(7)).thenReturn(downloader("1", null));
        doAnswerParsedSeason();

        assertEquals("/downloads/剧集", service.push(pushRequest("Three.Body.S01E01")));
    }

    private void doAnswerParsedSeason() {
        org.mockito.Mockito.doAnswer(inv -> {
            inv.<TorrentInfo>getArgument(0).setParsedSeason(1);
            return null;
        }).when(engine).fillParsed(any());
    }

    @Test
    void 直接下载_只做种的下载器拒绝() throws Exception {
        when(downloaderService.getById(7)).thenReturn(downloader("1", "SEED_ONLY"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.push(pushRequest("x")));
        assertTrue(e.getMessage().contains("只用于做种"));
        verify(client, never()).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    void 直接下载_停用的下载器拒绝() {
        when(downloaderService.getById(7)).thenReturn(downloader("0", "DOWNLOAD"));

        assertThrows(IllegalArgumentException.class, () -> service.push(pushRequest("x")));
    }

    @Test
    void 直接下载_没有下载链接拒绝() {
        ResourcePushRequest r = pushRequest("x");
        r.setDownloadUrl(" ");

        assertThrows(IllegalArgumentException.class, () -> service.push(r));
    }

    @Test
    void 直接下载_下载器报错时把原因带回给用户() throws Exception {
        when(downloaderService.getById(7)).thenReturn(downloader("1", "DOWNLOAD"));
        doThrow(new IOException("Fails.")).when(client).addTorrent(any(), anyString(), anyString(), anyString(), anyBoolean());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.push(pushRequest("x")));
        assertTrue(e.getMessage().contains("Fails."), e.getMessage());
    }
}
