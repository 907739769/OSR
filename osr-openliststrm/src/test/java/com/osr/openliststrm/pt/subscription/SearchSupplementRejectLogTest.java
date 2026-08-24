package com.osr.openliststrm.pt.subscription;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.indexer.IndexerCapabilityCache;
import com.osr.openliststrm.pt.indexer.TorznabClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 候选淘汰日志在同一次搜索内去重。
 * <p>
 * 同一个发布在多个站点上是多条记录（{@code dedupeByIndexerGuid} 按 {@code (indexerId, guid)}
 * 去重，站点不同就留不同的条目），而 ID 检索计划有 3 步、每步都要过一遍同样的候选。于是
 * 同一行会被打 3×站点数 遍：生产日志里一次补搜 240 行只有 <b>50 条不重复</b>，单条最多 12 遍。
 * 那行文本里既没有站点也没有步骤，读的人根本分不出这 12 行有什么不同。
 * <p>
 * 但<b>不能</b>做成跨次去重：手动搜索的收尾 INFO 承诺过「开启 DEBUG 日志可看到每个候选
 * 具体被哪一步、哪条规则淘汰」，那是用户刚按下按钮后等的回音。所以键里带 traceId。
 *
 * @author Jack
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchSupplementRejectLogTest {

    @Mock private IPtIndexerPlusService indexerService;
    @Mock private TorznabClient torznabClient;
    @Mock private SubscriptionEngine subscriptionEngine;
    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    @Mock private IndexerCapabilityCache capabilityCache;
    @Mock private IPtFilterConfigPlusService filterConfigService;
    @Mock private TorrentFilterEngine filterEngine;
    @Mock private TmdbSearchService tmdbSearchService;
    @Mock private IPtTorrentBlacklistPlusService blacklistService;
    @Mock private SearchLogService searchLogService;

    private SearchSupplementService service;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        service = new SearchSupplementService(indexerService, torznabClient, subscriptionEngine,
                subscriptionService, episodeService, new SubscriptionMatcher(), capabilityCache,
                filterConfigService, filterEngine, tmdbSearchService, blacklistService,
                searchLogService, 0, 0, 0, 0);
        logger = (Logger) LoggerFactory.getLogger(SearchSupplementService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    void 同一次搜索内逐字相同的淘汰说明只记一行() {
        MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, "trace-a");

        // 同一个发布来自 3 个站点、被 3 个检索步骤各过一遍 = 9 次判定
        for (int step = 0; step < 3; step++) {
            for (int site = 0; site < 3; site++) {
                boolean first = service.firstRejectionInSearch(
                        "idSeason", "Link Click S03 1080p CR WEB-DL", 3, 4);
                assertEquals(step == 0 && site == 0, first,
                        "只有第一次该放行（step=" + step + ", site=" + site + "）");
            }
        }
    }

    @Test
    void 不同候选各记一行() {
        MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, "trace-a");

        assertTrue(service.firstRejectionInSearch("idSeason", "Link Click S01", 1, 4));
        assertTrue(service.firstRejectionInSearch("idSeason", "Link Click S02", 2, 4));
        assertTrue(service.firstRejectionInSearch("idSeason", "Link Click S03", 3, 4));
    }

    @Test
    void 同一标题被不同规则淘汰时各记一行() {
        MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, "trace-a");

        // tag 就是为这个而存在：四条文案说的不是同一件事
        assertTrue(service.firstRejectionInSearch("season", "Some.Show.S02E01", 2, 1));
        assertTrue(service.firstRejectionInSearch("title", "Some.Show.S02E01", "别的剧"));
        assertTrue(service.firstRejectionInSearch("episode", "Some.Show.S02E01", 1));
    }

    @Test
    void 键少一个字段会把两行不同的日志吞掉_所以字段要全() {
        MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, "trace-a");

        // 同一个标题、不同的解析季号 —— 打出来是两行不同的文本，必须都留下
        assertTrue(service.firstRejectionInSearch("idSeason", "Link Click 2021", 1, 4));
        assertTrue(service.firstRejectionInSearch("idSeason", "Link Click 2021", 2, 4));
    }

    @Test
    void 换一次搜索就重新输出_手动搜索等的就是这个回音() {
        MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, "trace-a");
        assertTrue(service.firstRejectionInSearch("idSeason", "Link Click S03", 3, 4));
        assertFalse(service.firstRejectionInSearch("idSeason", "Link Click S03", 3, 4));

        // 下一次搜索是另一个 traceId：同样的候选照常再输出一遍
        MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, "trace-b");
        assertTrue(service.firstRejectionInSearch("idSeason", "Link Click S03", 3, 4),
                "跨次去重会让用户第二次点搜索时一行都看不到");
    }

    @Test
    void 拿不到traceId时一律放行_宁可多打也不跨次去重() {
        MDC.clear();

        assertTrue(service.firstRejectionInSearch("idSeason", "Link Click S03", 3, 4));
        assertTrue(service.firstRejectionInSearch("idSeason", "Link Click S03", 3, 4));
    }
}
