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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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

        assertEquals(1, countContaining("无可占位的缺失集"));
        // 压掉的只是日志：逐次明细照旧落 pt_search_log，否则这就不是降噪而是丢数据
        verify(searchLogService, times(20))
                .recordSummary(any(), anyInt(), anyString(), anyString());
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
        // 与「无可占位的缺失集」同理：压掉的只是叙述，逐次明细照旧落 pt_search_log
        verify(searchLogService, times(30))
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
}
