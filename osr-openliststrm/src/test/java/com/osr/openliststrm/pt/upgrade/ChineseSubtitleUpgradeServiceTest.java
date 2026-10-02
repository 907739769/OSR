package com.osr.openliststrm.pt.upgrade;

import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.PushOutcome;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 找中字版本：只要本集单集资源、只要带中字标识的、照样过过滤规则，走洗版通道推送；
 * 每一种没推成的情形都要给出不同的说法（处置方向不同）。
 */
class ChineseSubtitleUpgradeServiceTest {

    private SearchSupplementService search;
    private SubscriptionEngine engine;
    private TorrentFilterEngine filterEngine;
    private IPtSubscriptionEpisodePlusService episodeService;
    private ChineseSubtitleUpgradeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        search = mock(SearchSupplementService.class);
        engine = mock(SubscriptionEngine.class);
        filterEngine = mock(TorrentFilterEngine.class);
        IPtFilterConfigPlusService filterConfig = mock(IPtFilterConfigPlusService.class);
        IPtTorrentBlacklistPlusService blacklist = mock(IPtTorrentBlacklistPlusService.class);
        episodeService = mock(IPtSubscriptionEpisodePlusService.class);
        when(filterConfig.getConfig()).thenReturn(new PtFilterConfigPlus());
        when(blacklist.list()).thenReturn(List.of());
        // 过滤与择优默认放行、取第一个；需要「全被挡掉」时单独改
        when(filterEngine.filter(anyList(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(filterEngine.pickBest(anyList(), any())).thenAnswer(inv -> inv.<List<TorrentInfo>>getArgument(0).get(0));
        when(engine.pushUpgradeOutcome(any(), anyInt(), anyList())).thenReturn(PushOutcome.ok());
        service = new ChineseSubtitleUpgradeService(search, engine, filterEngine, filterConfig, blacklist, episodeService);
    }

    private static PtSubscriptionPlus sub(String mediaType) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(7);
        s.setTitle("Three Body");
        s.setSeason(1);
        s.setYear("2024");
        s.setMediaType(mediaType);
        return s;
    }

    private void givenStates(int... inLibrary) {
        List<PtSubscriptionEpisodePlus> eps = IntStream.of(inLibrary).mapToObj(n -> {
            PtSubscriptionEpisodePlus e = new PtSubscriptionEpisodePlus();
            e.setEpisode(n);
            e.setState("IN_LIBRARY");
            return e;
        }).toList();
        when(episodeService.listBySubscription(7)).thenReturn(eps);
    }

    /** fillParsed 被 mock 掉了，解析字段直接在种子上写好 */
    private static TorrentInfo torrent(String title, Integer season, Integer episode, String description) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        t.setDescription(description);
        t.setParsedSeason(season);
        t.setParsedEpisode(episode);
        return t;
    }

    @Test
    @SuppressWarnings("unchecked")
    void 只推本集带中字的单集资源_描述里写的中字也算() {
        givenStates(5);
        when(search.searchAcrossIndexers("Three Body S01E05")).thenReturn(List.of(
                torrent("Three.Body.S01E05.1080p.WEB-DL", 1, 5, null),
                torrent("Three.Body.S01E06.CHS.1080p", 1, 6, null),
                torrent("Three.Body.S01E05.2160p.WEB-DL", 1, 5, "三体 | 内封简繁 | 简中字幕")));

        ChineseSubtitleUpgradeService.Result result = service.fetch(sub("TV"), List.of(5));

        assertEquals(ChineseSubtitleUpgradeService.Status.PUSHED, result.results().get(0).status());
        ArgumentCaptor<List<TorrentInfo>> pushed = ArgumentCaptor.forClass(List.class);
        verify(engine).pushUpgradeOutcome(any(), eq(5), pushed.capture());
        assertEquals("Three.Body.S01E05.2160p.WEB-DL", pushed.getValue().get(0).getTitle());
    }

    @Test
    void 有本集资源但都没中字_与没搜到分开说() {
        givenStates(5, 6);
        when(search.searchAcrossIndexers("Three Body S01E05")).thenReturn(List.of(torrent("Three.Body.S01E05.1080p", 1, 5, null)));
        when(search.searchAcrossIndexers("Three Body S01E06")).thenReturn(List.of());

        ChineseSubtitleUpgradeService.Result result = service.fetch(sub("TV"), List.of(5, 6));

        assertEquals(ChineseSubtitleUpgradeService.Status.NO_CHINESE_RELEASE, result.results().get(0).status());
        assertEquals(ChineseSubtitleUpgradeService.Status.NOT_FOUND, result.results().get(1).status());
        verify(engine, never()).pushUpgradeOutcome(any(), anyInt(), anyList());
    }

    /** 季包与区间包会连带动到没打算换的集，理由同洗版 */
    @Test
    void 季包与区间包不算本集资源() {
        givenStates(5);
        TorrentInfo range = torrent("Three.Body.S01E05-E08.CHS", 1, 5, null);
        range.setParsedEpisodeEnd(8);
        when(search.searchAcrossIndexers(anyString())).thenReturn(List.of(
                torrent("Three.Body.S01.CHS.Complete", 1, null, null), range));

        assertEquals(ChineseSubtitleUpgradeService.Status.NOT_FOUND,
                service.fetch(sub("TV"), List.of(5)).results().get(0).status());
    }

    @Test
    void 带中字的全被过滤规则挡掉() {
        givenStates(5);
        when(search.searchAcrossIndexers(anyString())).thenReturn(List.of(torrent("Three.Body.S01E05.CHS", 1, 5, null)));
        when(filterEngine.filter(anyList(), any(), any(), any())).thenReturn(List.of());

        assertEquals(ChineseSubtitleUpgradeService.Status.ALL_FILTERED,
                service.fetch(sub("TV"), List.of(5)).results().get(0).status());
    }

    /** 洗版通道只认已入库的集：先说清楚，不白打一轮搜索 */
    @Test
    void 不是已入库的集不搜() {
        PtSubscriptionEpisodePlus upgrading = new PtSubscriptionEpisodePlus();
        upgrading.setEpisode(5);
        upgrading.setState("UPGRADING");
        when(episodeService.listBySubscription(7)).thenReturn(List.of(upgrading));

        ChineseSubtitleUpgradeService.EpisodeResult r = service.fetch(sub("TV"), List.of(5)).results().get(0);

        assertEquals(ChineseSubtitleUpgradeService.Status.NOT_IN_LIBRARY, r.status());
        verify(search, never()).searchAcrossIndexers(anyString());
    }

    @Test
    void 推送没成功时带回引擎给的原因() {
        givenStates(5);
        when(search.searchAcrossIndexers(anyString())).thenReturn(List.of(torrent("Three.Body.S01E05.CHS", 1, 5, null)));
        when(engine.pushUpgradeOutcome(any(), anyInt(), anyList())).thenReturn(PushOutcome.fail("下载器并发已达上限"));

        ChineseSubtitleUpgradeService.EpisodeResult r = service.fetch(sub("TV"), List.of(5)).results().get(0);

        assertEquals(ChineseSubtitleUpgradeService.Status.FAILED, r.status());
        assertEquals("下载器并发已达上限", r.detail());
    }

    @Test
    void 一次最多6集_其余明说下次再点() {
        givenStates(IntStream.rangeClosed(1, 8).toArray());
        when(search.searchAcrossIndexers(anyString())).thenReturn(List.of());

        ChineseSubtitleUpgradeService.Result result = service.fetch(sub("TV"), IntStream.rangeClosed(1, 8).boxed().toList());

        assertEquals(2, result.count(ChineseSubtitleUpgradeService.Status.SKIPPED));
        verify(search, times(6)).searchAcrossIndexers(anyString());
    }

    @Test
    void 电影按集号0_按片名搜_只认同年份的电影资源() {
        PtSubscriptionEpisodePlus e = new PtSubscriptionEpisodePlus();
        e.setEpisode(0);
        e.setState("IN_LIBRARY");
        when(episodeService.listBySubscription(7)).thenReturn(List.of(e));
        TorrentInfo movie = torrent("Three.Body.2024.CHS.1080p", null, null, null);
        movie.setParsedYear("2024");
        when(search.searchAcrossIndexers("Three Body")).thenReturn(List.of(movie));

        ChineseSubtitleUpgradeService.Result result = service.fetch(sub("MOVIE"), List.of());

        assertEquals(0, result.results().get(0).episode());
        assertEquals(ChineseSubtitleUpgradeService.Status.PUSHED, result.results().get(0).status());
    }

    @Test
    void 没有启用的索引器直接拒绝() {
        when(search.hasNoEnabledIndexer()).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.fetch(sub("TV"), List.of(1)));
    }

    @Test
    void 中字标识判据与过滤规则同一份() {
        assertTrue(ChineseSubtitleUpgradeService.hasChineseMark(torrent("X.S01E01.简繁英.1080p", 1, 1, null)));
        assertTrue(ChineseSubtitleUpgradeService.hasChineseMark(torrent("X.S01E01.1080p", 1, 1, "Chinese Subtitles")));
        assertTrue(!ChineseSubtitleUpgradeService.hasChineseMark(torrent("X.S01E01.1080p.WEB-DL", 1, 1, null)));
    }
}
