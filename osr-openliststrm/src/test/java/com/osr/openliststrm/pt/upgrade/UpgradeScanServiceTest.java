package com.osr.openliststrm.pt.upgrade;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtUpgradeConfigPlusService;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import com.osr.openliststrm.rename.MediaParser;
import com.osr.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 洗版扫描的编排逻辑。判定本身在 {@link UpgradeEvaluatorTest} 里覆盖，这里只钉编排：
 * 开关、名额闸门、集匹配、以及"什么情况下不该动手"。
 *
 * @author Jack
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UpgradeScanServiceTest {

    @Mock private IPtUpgradeConfigPlusService upgradeConfigService;
    @Mock private IPtFilterConfigPlusService filterConfigService;
    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    @Mock private IPtTorrentBlacklistPlusService blacklistService;
    @Mock private SearchSupplementService searchSupplementService;
    @Mock private SubscriptionEngine subscriptionEngine;

    private UpgradeScanService service;

    /** 真实解析器：用来把测试里的种子标题变成 parsedXxx，与生产路径一致 */
    private final MediaParser mediaParser = new MediaParser(null, null);

    @BeforeEach
    void setUp() {
        service = new UpgradeScanService(upgradeConfigService, filterConfigService, subscriptionService,
                episodeService, blacklistService, searchSupplementService, subscriptionEngine,
                new TorrentFilterEngine(), new UpgradeEvaluator());

        when(upgradeConfigService.getConfig()).thenReturn(upgradeConfig("1"));
        when(filterConfigService.getConfig()).thenReturn(filterConfig());
        when(blacklistService.list()).thenReturn(new ArrayList<>());
        // 让 fillParsed 走真实解析，否则候选没有 parsedXxx，集匹配与画像全是空
        org.mockito.Mockito.doAnswer(inv -> {
            TorrentInfo t = inv.getArgument(0);
            MediaInfo info = mediaParser.parseLocal(t.getTitle());
            t.setParsedTitle(info.getOriginalTitle());
            t.setParsedYear(info.getYear());
            t.setParsedSeason(toInt(info.getSeason()));
            t.setParsedEpisode(toInt(info.getEpisode()));
            t.setParsedEpisodeEnd(toInt(info.getEpisodeEnd()));
            t.setParsedResolution(info.getResolution());
            t.setParsedSource(info.getSource());
            t.setParsedReleaseGroup(info.getReleaseGroup());
            t.setParsedTags(QualityProfile.collectTags(info));
            return null;
        }).when(subscriptionEngine).fillParsed(any());
    }

    private Integer toInt(String v) {
        return (v == null || v.isBlank()) ? null : Integer.valueOf(v.trim());
    }

    private PtUpgradeConfigPlus upgradeConfig(String enabled) {
        PtUpgradeConfigPlus c = new PtUpgradeConfigPlus();
        c.setEnabled(enabled);
        c.setQualityPriority("RESOLUTION,SOURCE");
        c.setTargetResolution("2160p");
        c.setTargetSources("REMUX,BluRay");
        c.setMaxConcurrent(2);
        c.setScanIntervalHours(6);
        return c;
    }

    private PtFilterConfigPlus filterConfig() {
        PtFilterConfigPlus c = new PtFilterConfigPlus();
        c.setMinSeeders(0);
        c.setMinSize(0L);
        c.setMaxSize(0L);
        c.setFreeOnly("0");
        c.setResolutionPriority("2160p,1080p,720p");
        c.setSourcePriority("REMUX,BluRay,WEBDL,HDTV");
        c.setSortPriority("RESOLUTION");
        c.setPreferredSize(0L);
        return c;
    }

    private PtSubscriptionPlus sub() {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(10);
        s.setMediaType("TV");
        s.setTitle("Some Show");
        s.setSeason(1);
        s.setStatus("ACTIVE");
        s.setUpgradeEnabled("1");
        return s;
    }

    private PtSubscriptionEpisodePlus episode(String quality) {
        PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
        ep.setId(501);
        ep.setSubId(10);
        ep.setEpisode(1);
        ep.setState("IN_LIBRARY");
        ep.setQuality(quality);
        ep.setUpgradeState("PENDING");
        return ep;
    }

    private TorrentInfo torrent(String title) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        t.setGuid(title);
        t.setIndexerId(1);
        t.setSeeders(10);
        t.setSize(5_000_000_000L);
        return t;
    }

    private void givenEpisodes(PtSubscriptionEpisodePlus... episodes) {
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodes));
        when(episodeService.count(any(Wrapper.class))).thenReturn(0L);
        when(subscriptionService.getById(10)).thenReturn(sub());
    }

    @Test
    void 总开关关闭_不做任何事() {
        when(upgradeConfigService.getConfig()).thenReturn(upgradeConfig("0"));

        assertEquals(0, service.run());
        verify(searchSupplementService, never()).searchAcrossIndexers(anyString());
    }

    @Test
    void 未配置目标质量_不做任何事() {
        // 安全默认：用户没想清楚要什么质量时，系统不该自作主张开始搜
        PtUpgradeConfigPlus c = upgradeConfig("1");
        c.setTargetResolution(null);
        c.setTargetSources(null);
        c.setTargetTags(null);
        when(upgradeConfigService.getConfig()).thenReturn(c);

        assertEquals(0, service.run());
        verify(searchSupplementService, never()).searchAcrossIndexers(anyString());
    }

    @Test
    void 在途洗版已达上限_跳过本轮() {
        // 缺集是刚需，洗版是锦上添花，不能把新剧的更新堵在门外
        when(episodeService.count(any(Wrapper.class))).thenReturn(2L);

        assertEquals(0, service.run());
        verify(searchSupplementService, never()).searchAcrossIndexers(anyString());
    }

    @Test
    void 已达目标质量_标记REACHED且不再搜索() {
        givenEpisodes(episode("{\"resolution\":\"2160p\",\"source\":\"REMUX\"}"));

        assertEquals(0, service.run());

        verify(searchSupplementService, never()).searchAcrossIndexers(anyString());
        org.mockito.ArgumentCaptor<PtSubscriptionEpisodePlus> captor =
                org.mockito.ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService).update(captor.capture(), any(Wrapper.class));
        assertEquals("REACHED", captor.getValue().getUpgradeState());
    }

    @Test
    void 无质量基线_标记NO_BASELINE且不再搜索() {
        // 不知道库里躺的是什么货色，盲目升级可能把好版本换成差版本
        givenEpisodes(episode(null));

        assertEquals(0, service.run());

        verify(searchSupplementService, never()).searchAcrossIndexers(anyString());
        org.mockito.ArgumentCaptor<PtSubscriptionEpisodePlus> captor =
                org.mockito.ArgumentCaptor.forClass(PtSubscriptionEpisodePlus.class);
        verify(episodeService).update(captor.capture(), any(Wrapper.class));
        assertEquals("NO_BASELINE", captor.getValue().getUpgradeState());
    }

    @Test
    void 搜到更好的版本_推送洗版() {
        givenEpisodes(episode("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}"));
        when(searchSupplementService.searchAcrossIndexers(anyString()))
                .thenReturn(List.of(torrent("Some.Show.S01E01.2160p.BluRay-CHDBits")));
        when(subscriptionEngine.pushUpgrade(any(), anyInt(), anyList())).thenReturn(true);

        assertEquals(1, service.run());
        verify(subscriptionEngine).pushUpgrade(any(), org.mockito.ArgumentMatchers.eq(1), anyList());
    }

    @Test
    void 搜到的版本不比现有更好_不推送() {
        givenEpisodes(episode("{\"resolution\":\"2160p\",\"source\":\"WEBDL\"}"));
        when(searchSupplementService.searchAcrossIndexers(anyString()))
                .thenReturn(List.of(torrent("Some.Show.S01E01.1080p.BluRay-CHDBits")));

        assertEquals(0, service.run());
        verify(subscriptionEngine, never()).pushUpgrade(any(), anyInt(), anyList());
    }

    @Test
    void 季包与区间包被排除_洗版只换目标那一集() {
        // 用季包覆盖会连带动到那些没打算升级的集，它们的质量基线根本没被比较过
        givenEpisodes(episode("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}"));
        when(searchSupplementService.searchAcrossIndexers(anyString())).thenReturn(List.of(
                torrent("Some.Show.S01.2160p.BluRay-CHDBits"),
                torrent("Some.Show.S01E01-E06.2160p.BluRay-CHDBits")));

        assertEquals(0, service.run());
        verify(subscriptionEngine, never()).pushUpgrade(any(), anyInt(), anyList());
    }

    @Test
    void 别的季的候选被排除() {
        givenEpisodes(episode("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}"));
        when(searchSupplementService.searchAcrossIndexers(anyString()))
                .thenReturn(List.of(torrent("Some.Show.S02E01.2160p.BluRay-CHDBits")));

        assertEquals(0, service.run());
        verify(subscriptionEngine, never()).pushUpgrade(any(), anyInt(), anyList());
    }

    @Test
    void 订阅已暂停_不参与洗版() {
        PtSubscriptionPlus paused = sub();
        paused.setStatus("PAUSED");
        when(episodeService.list(any(Wrapper.class)))
                .thenReturn(List.of(episode("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}")));
        when(episodeService.count(any(Wrapper.class))).thenReturn(0L);
        when(subscriptionService.getById(10)).thenReturn(paused);

        assertEquals(0, service.run());
        verify(searchSupplementService, never()).searchAcrossIndexers(anyString());
    }

    @Test
    void 订阅已完结_仍参与洗版() {
        // 集齐了不代表质量到位，"追完的老剧慢慢换成 4K"正是洗版最典型的用法
        PtSubscriptionPlus completed = sub();
        completed.setStatus("COMPLETED");
        when(episodeService.list(any(Wrapper.class)))
                .thenReturn(List.of(episode("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}")));
        when(episodeService.count(any(Wrapper.class))).thenReturn(0L);
        when(subscriptionService.getById(10)).thenReturn(completed);
        when(searchSupplementService.searchAcrossIndexers(anyString()))
                .thenReturn(List.of(torrent("Some.Show.S01E01.2160p.BluRay-CHDBits")));
        when(subscriptionEngine.pushUpgrade(any(), anyInt(), anyList())).thenReturn(true);

        assertEquals(1, service.run());
    }

    @Test
    void 订阅级洗版开关关闭_不参与() {
        PtSubscriptionPlus off = sub();
        off.setUpgradeEnabled("0");
        when(episodeService.list(any(Wrapper.class)))
                .thenReturn(List.of(episode("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}")));
        when(episodeService.count(any(Wrapper.class))).thenReturn(0L);
        when(subscriptionService.getById(10)).thenReturn(off);

        assertEquals(0, service.run());
        verify(searchSupplementService, never()).searchAcrossIndexers(anyString());
    }

    @Test
    void 名额有限时按剩余额度截断() {
        // maxConcurrent=2、在途 1 个 → 本轮只剩 1 个名额
        PtSubscriptionEpisodePlus e1 = episode("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}");
        PtSubscriptionEpisodePlus e2 = episode("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}");
        e2.setId(502);
        e2.setEpisode(2);
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(e1, e2));
        when(episodeService.count(any(Wrapper.class))).thenReturn(1L);
        when(subscriptionService.getById(10)).thenReturn(sub());
        when(searchSupplementService.searchAcrossIndexers(anyString()))
                .thenReturn(List.of(torrent("Some.Show.S01E01.2160p.BluRay-CHDBits")));
        when(subscriptionEngine.pushUpgrade(any(), anyInt(), anyList())).thenReturn(true);

        assertEquals(1, service.run());
        verify(subscriptionEngine).pushUpgrade(any(), anyInt(), anyList());
    }

    @Test
    void 单集异常不影响其它集() {
        PtSubscriptionEpisodePlus bad = episode("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}");
        PtSubscriptionEpisodePlus good = episode("{\"resolution\":\"1080p\",\"source\":\"WEBDL\"}");
        good.setId(502);
        good.setEpisode(2);
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(bad, good));
        when(episodeService.count(any(Wrapper.class))).thenReturn(0L);
        when(subscriptionService.getById(10)).thenReturn(sub());
        when(searchSupplementService.searchAcrossIndexers(anyString()))
                .thenThrow(new RuntimeException("索引器炸了"))
                .thenReturn(List.of(torrent("Some.Show.S01E02.2160p.BluRay-CHDBits")));
        when(subscriptionEngine.pushUpgrade(any(), anyInt(), anyList())).thenReturn(true);

        assertEquals(1, service.run());
    }
}
