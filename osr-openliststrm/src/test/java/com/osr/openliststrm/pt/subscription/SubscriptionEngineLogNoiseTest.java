package com.osr.openliststrm.pt.subscription;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.IDownloaderClient;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.indexer.GuidHasher;
import com.osr.openliststrm.pt.model.TorrentInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RSS 稳态下的日志去重。
 *
 * <p>这些断言守的是一个靠量化生产日志才发现的问题：RSS 拉取窗口 24 小时、轮询间隔几分钟，
 * 同一批种子每轮都要重走一遍匹配。实测 886 个不同种子被记了 39913 行，
 * 占整份 sys-all.log 的 <b>93%</b>——比曾经清理掉的 MyBatis SQL 那次还狠。
 * 「同一个种子只说一次」这件事不能靠人自觉，所以逐条钉在这里。
 *
 * @author Jack
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubscriptionEngineLogNoiseTest {

    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    @Mock private IPtDownloadRecordPlusService recordService;
    @Mock private IPtDownloaderPlusService downloaderService;
    @Mock private IPtFilterConfigPlusService filterConfigService;
    @Mock private DownloaderClientFactory downloaderClientFactory;
    @Mock private IDownloaderClient downloaderClient;
    @Mock private SearchLogService searchLogService;
    @Mock private IPtTorrentBlacklistPlusService blacklistService;
    @Mock private TmdbSearchService tmdbSearchService;
    @Mock private IPtIndexerPlusService indexerService;

    private SubscriptionEngine engine;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        engine = new SubscriptionEngine(
                subscriptionService, episodeService, recordService, downloaderService,
                filterConfigService, downloaderClientFactory,
                new TorrentFilterEngine(), new SubscriptionMatcher(), searchLogService,
                blacklistService, tmdbSearchService, indexerService);
        when(blacklistService.list()).thenReturn(new ArrayList<>());
        when(filterConfigService.getConfig()).thenReturn(permissiveConfig());

        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setType("QBITTORRENT");
        downloader.setSavePath("/data/downloads");
        downloader.setTag("osr-pt");
        downloader.setEnabled("1");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(downloader));
        when(downloaderClientFactory.get(any())).thenReturn(downloaderClient);
        when(recordService.list(any(Wrapper.class))).thenReturn(new ArrayList<>());
        when(recordService.save(any())).thenReturn(true);
        when(episodeService.update(any(), any(Wrapper.class))).thenReturn(true);

        logger = (Logger) LoggerFactory.getLogger(SubscriptionEngine.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private PtFilterConfigPlus permissiveConfig() {
        PtFilterConfigPlus config = new PtFilterConfigPlus();
        config.setMinSeeders(0);
        config.setMinSize(0L);
        config.setMaxSize(0L);
        config.setFreeOnly("0");
        config.setResolutionPriority("2160p,1080p,720p");
        config.setSortPriority("RESOLUTION,SEEDERS");
        config.setPreferredSize(0L);
        return config;
    }

    private long countContaining(String fragment) {
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains(fragment))
                .count();
    }

    private long countAtLevel(Level level, String fragment) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == level && e.getFormattedMessage().contains(fragment))
                .count();
    }

    private PtSubscriptionPlus tvSub(int id, String title, int season, int total) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setMediaType("TV");
        sub.setTitle(title);
        sub.setSeason(season);
        sub.setTotalEpisodes(total);
        sub.setStatus("ACTIVE");
        return sub;
    }

    private PtSubscriptionEpisodePlus episode(int id, int number, String state) {
        PtSubscriptionEpisodePlus ep = new PtSubscriptionEpisodePlus();
        ep.setId(id);
        ep.setEpisode(number);
        ep.setState(state);
        return ep;
    }

    private TorrentInfo torrent(String title, String guid) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        t.setGuid(guid);
        t.setSeeders(10);
        t.setSize(5_000_000_000L);
        t.setIndexerId(1);
        t.setDownloadUrl("http://indexer/download?id=" + guid);
        return t;
    }

    // ---------- 种子未匹配到任何订阅 ----------

    @Test
    void 未匹配的种子跨轮只记一行() {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        List<TorrentInfo> batch = List.of(torrent("Call.Me.By.Fire.S01E14.1080p.WEB-DL", "g1"));

        for (int round = 0; round < 45; round++) {
            engine.process(batch);
        }

        assertEquals(1, countContaining("种子未匹配到任何订阅"),
                "同一个种子在 45 轮里只该记一行——生产日志里那 93% 正是这么来的");
    }

    @Test
    void 不同的未匹配种子各记一行() {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));

        for (int round = 0; round < 5; round++) {
            engine.process(List.of(
                    torrent("Call.Me.By.Fire.S01E14.1080p.WEB-DL", "g1"),
                    torrent("Pearl.S01.1080p.WEB-DL", "g2"),
                    torrent("Wow.The.World.S01E09.1080p.WEB-DL", "g3")));
        }

        assertEquals(3, countContaining("种子未匹配到任何订阅"),
                "去重不能把不同种子也压掉：排查「站上有资源为什么没推给我」靠的就是这几行");
    }

    /**
     * 键取标题而不是 guid：同一部片在两个站各有一条种子时，按 guid 去重会写出两行逐字
     * 相同的日志，而那行文本里并没有站点信息，读的人根本分不出它们。
     */
    @Test
    void 同标题不同guid只记一行() {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        TorrentInfo fromSiteA = torrent("Pearl.S01.1080p.WEB-DL", "site-a-1");
        TorrentInfo fromSiteB = torrent("Pearl.S01.1080p.WEB-DL", "site-b-1");
        fromSiteB.setIndexerId(2);

        engine.process(List.of(fromSiteA, fromSiteB));

        assertEquals(1, countContaining("种子未匹配到任何订阅"));
    }

    // ---------- 无可占位的缺失集 ----------

    @Test
    void 无可占位的缺失集在RSS路径上跨轮只记一行() {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        // 集全部已入库：种子还留在 RSS 窗口里，但没有任何可占位的目标——这是稳态，不是事件
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "IN_LIBRARY"),
                episode(102, 2, "IN_LIBRARY"),
                episode(103, 3, "IN_LIBRARY")));
        List<TorrentInfo> batch = List.of(torrent("Some.Show.S01E02.1080p.WEB-DL", "g1"));

        for (int round = 0; round < 20; round++) {
            engine.process(batch);
        }

        assertEquals(1, countContaining("无需占位"));
        // 落库与日志同进退：pt_search_log 按订阅只保留 200 条并按 id 削旧，逐次写这条
        // 零信息量的稳态记录会把真正有诊断价值的淘汰记录挤出窗口（10 分钟一轮 = 144 行/天）
        verify(searchLogService, times(1))
                .recordSummary(any(), anyInt(), anyString(), anyString());
    }

    /** 文案要报集表里的实际状态，不能再写「可能已入库或在途」——那句话连自己都在猜 */
    @Test
    void 无可占位时报出实际状态而不是猜() {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "IN_LIBRARY"),
                episode(102, 2, "IN_FLIGHT"),
                episode(103, 3, "IN_LIBRARY")));

        engine.process(List.of(torrent("Some.Show.S01E02.1080p.WEB-DL", "g1")));

        org.mockito.ArgumentCaptor<String> reason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(searchLogService).recordSummary(any(), anyInt(), anyString(), reason.capture());
        assertTrue(reason.getValue().contains("第 2 集"), reason.getValue());
        assertTrue(reason.getValue().contains("在途"), reason.getValue());
    }

    /** 季包分支按状态分类计数，让用户一眼看出这一季究竟卡在哪一档 */
    @Test
    void 季包无可占位时按状态分类计数() {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "IN_LIBRARY"),
                episode(102, 2, "IN_FLIGHT"),
                episode(103, 3, "IN_LIBRARY")));

        engine.process(List.of(torrent("Some.Show.S01.1080p.WEB-DL", "g1")));

        org.mockito.ArgumentCaptor<String> reason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(searchLogService).recordSummary(any(), anyInt(), anyString(), reason.capture());
        assertTrue(reason.getValue().contains("无一缺失"), reason.getValue());
        assertTrue(reason.getValue().contains("2 集已入库"), reason.getValue());
        assertTrue(reason.getValue().contains("1 集在途"), reason.getValue());
    }

    /** 手动/补搜路径不去重：用户刚按下按钮，等的就是这个回音 */
    @Test
    void 手动路径的无可占位每次都落库() {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 3);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "IN_LIBRARY"),
                episode(102, 2, "IN_LIBRARY"),
                episode(103, 3, "IN_LIBRARY")));

        for (int round = 0; round < 5; round++) {
            engine.pushManual(sub, 2, List.of(torrent("Some.Show.S01E02.1080p.WEB-DL", "g1")));
        }

        verify(searchLogService, times(5))
                .recordSummary(any(), anyInt(), eq(SearchLogService.SOURCE_MANUAL), anyString());
    }

    // ---------- 候选都有已有下载记录 ----------

    @Test
    void 候选都已推送过在RSS路径上跨轮只记一行() {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));
        // 推过的种子会一直留在 24 小时的 RSS 窗口里，每轮重新判一次、每轮得到同一个答案
        PtDownloadRecordPlus done = new PtDownloadRecordPlus();
        done.setState("COMPLETED");
        done.setDownloaderId(1);   // loadDownloaderLoadCounts 也读这份列表，缺了会 NPE 在 ConcurrentHashMap
        done.setGuidHash(GuidHasher.hash("g1"));
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(done));
        List<TorrentInfo> batch = List.of(torrent("Some.Show.S01E02.1080p.WEB-DL", "g1"));

        for (int round = 0; round < 30; round++) {
            engine.process(batch);
        }

        assertEquals(1, countContaining("的候选都有已有下载记录"),
                "实测一条订阅在 17.5 小时里刷了 106 行逐字相同的日志");
        // 与「无可占位的缺失集」同理：落库同样只在首轮写，理由见那条用例
        verify(searchLogService, times(1))
                .recordSummary(any(), anyInt(), anyString(), anyString());
    }

    @Test
    void 不同订阅的已推送过各记一行() {
        when(subscriptionService.listActive()).thenReturn(List.of(
                tvSub(10, "Some Show", 1, 3), tvSub(11, "Other Show", 1, 3)));
        when(episodeService.listBySubscription(anyInt())).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));
        PtDownloadRecordPlus a = new PtDownloadRecordPlus();
        a.setState("COMPLETED");
        a.setDownloaderId(1);
        a.setGuidHash(GuidHasher.hash("g1"));
        PtDownloadRecordPlus b = new PtDownloadRecordPlus();
        b.setState("COMPLETED");
        b.setDownloaderId(1);
        b.setGuidHash(GuidHasher.hash("g2"));
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(a, b));

        for (int round = 0; round < 5; round++) {
            engine.process(List.of(
                    torrent("Some.Show.S01E02.1080p.WEB-DL", "g1"),
                    torrent("Other.Show.S01E02.1080p.WEB-DL", "g2")));
        }

        assertEquals(2, countContaining("的候选都有已有下载记录"),
                "去重键是订阅+集号，两条订阅各该留一行");
    }

    // ---------- 候选全部被过滤规则淘汰 ----------

    @Test
    void 全部因拉黑淘汰时降级为DEBUG() {
        PtTorrentBlacklistPlus blocked = new PtTorrentBlacklistPlus();
        blocked.setType(PtTorrentBlacklistPlus.TYPE_GUID);
        blocked.setValue(GuidHasher.hash("g1"));
        when(blacklistService.list()).thenReturn(new ArrayList<>(List.of(blocked)));
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E02.1080p.WEB-DL", "g1")));

        assertEquals(0, countAtLevel(Level.INFO, "全部被过滤规则淘汰"),
                "拉黑是用户自己按下的终态开关，每轮播报一次既不是新消息也无事可做");
        assertEquals(1, countAtLevel(Level.DEBUG, "全部被过滤规则淘汰"));
    }

    @Test
    void 拉黑之外的淘汰原因仍然是INFO() {
        PtFilterConfigPlus freeOnly = permissiveConfig();
        freeOnly.setFreeOnly("1");          // 非免费种一律淘汰
        when(filterConfigService.getConfig()).thenReturn(freeOnly);
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E02.1080p.WEB-DL", "g1")));

        assertEquals(1, countAtLevel(Level.INFO, "全部被过滤规则淘汰"),
                "「你可能把 freeOnly 打开了」这类才是需要被看见的淘汰原因");
    }

    // ---------- 淘汰日志里的站点与种子标题 ----------

    private PtFilterConfigPlus only2160p() {
        PtFilterConfigPlus config = permissiveConfig();
        config.setResolutionWhitelist("2160p");
        return config;
    }

    private void indexerNamed(int id, String name) {
        com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus indexer =
                new com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus();
        indexer.setId(id);
        indexer.setName(name);
        when(indexerService.getById(id)).thenReturn(indexer);
    }

    private String infoLine(String fragment) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains(fragment))
                .map(ILoggingEvent::getFormattedMessage)
                .findFirst()
                .orElseThrow();
    }

    /**
     * 真实场景：《星际迷航：奇异新世界》每集 RSS 都只拉到 1080p，被订阅的 2160p 白名单挡掉，
     * 日志却只有「1 个「分辨率不在白名单」」——看不出是哪个站的哪个版本，只能靠 traceId 反推。
     */
    @Test
    void 淘汰日志带上站点与种子标题() {
        when(filterConfigService.getConfig()).thenReturn(only2160p());
        indexerNamed(1, "mteam");
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E02.1080p.WEB-DL", "g1")));

        String line = infoLine("全部被过滤规则淘汰");
        assertTrue(line.contains("[mteam] Some.Show.S01E02.1080p.WEB-DL"), line);
        assertTrue(line.contains("1080p"), "要带实际值的原因，而不只是分类名：" + line);
    }

    /** 落库的匹配日志与回给用户的原因必须是同一句话，明细只进 sys-all.log */
    @Test
    void 站点与标题不进落库摘要() {
        when(filterConfigService.getConfig()).thenReturn(only2160p());
        indexerNamed(1, "mteam");
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E02.1080p.WEB-DL", "g1")));

        org.mockito.ArgumentCaptor<String> summary = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(searchLogService).recordSummary(any(), anyInt(), anyString(), summary.capture());
        assertTrue(summary.getValue().contains("全部被过滤规则淘汰"), summary.getValue());
        assertTrue(!summary.getValue().contains("mteam") && !summary.getValue().contains("候选："),
                summary.getValue());
    }

    /** 这行在 RSS 稳态下每轮重复，候选再多也不能无限变长 */
    @Test
    void 候选超过上限只报剩余个数() {
        when(filterConfigService.getConfig()).thenReturn(only2160p());
        indexerNamed(1, "mteam");
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));
        List<TorrentInfo> batch = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            batch.add(torrent("Some.Show.S01E02.1080p.WEB-DL-G" + i, "g" + i));
        }

        engine.process(batch);

        String line = infoLine("全部被过滤规则淘汰");
        assertTrue(line.contains("-G4（"), line);
        assertTrue(!line.contains("-G5（"), line);
        assertTrue(line.contains("另 2 个未列出"), line);
    }

    /** 索引器被删掉时写 id，不留空——留空会被读成「没有站点」 */
    @Test
    void 索引器已删除时退回显示id() {
        when(filterConfigService.getConfig()).thenReturn(only2160p());
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 3)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E02.1080p.WEB-DL", "g1")));

        assertTrue(infoLine("全部被过滤规则淘汰").contains("[索引器#1]"));
    }
}
