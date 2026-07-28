package com.ruoyi.openliststrm.pt.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ruoyi.openliststrm.pt.model.TorrentInfo;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentFilterEngineFilterTest {

    private final TorrentFilterEngine engine = new TorrentFilterEngine();

    private FilterCriteria criteria(int minSeeders, long minSize, long maxSize, boolean freeOnly,
                                    List<String> include, List<String> exclude) {
        return criteriaWithWhitelist(minSeeders, minSize, maxSize, freeOnly, include, exclude, List.of());
    }

    private FilterCriteria criteriaWithWhitelist(int minSeeders, long minSize, long maxSize, boolean freeOnly,
                                    List<String> include, List<String> exclude, List<String> resolutionWhitelist) {
        return new FilterCriteria(minSeeders, minSize, maxSize, freeOnly, include, exclude,
                List.of("1080p"), resolutionWhitelist, List.of(SortDimension.SEEDERS), 0L, false);
    }

    private TorrentInfo torrent(String title, int seeders, long size, boolean free) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        t.setSeeders(seeders);
        t.setSize(size);
        t.setDownloadVolumeFactor(free ? 0.0 : 1.0);
        return t;
    }

    private TorrentInfo torrentWithResolution(String title, String resolution) {
        TorrentInfo t = torrent(title, 10, 5_000_000_000L, false);
        t.setParsedResolution(resolution);
        return t;
    }

    private TorrentInfo ok() {
        return torrent("Some.Show.S01E05.1080p.WEB-DL", 10, 5_000_000_000L, false);
    }

    @Test
    void 全部条件满足_保留() {
        List<TorrentInfo> result = engine.filter(List.of(ok()),
                criteria(1, 0L, 0L, false, List.of(), List.of()), (String) null);

        assertEquals(1, result.size());
    }

    @Test
    void 做种数低于下限_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("t", 2, 5_000_000_000L, false)),
                criteria(3, 0L, 0L, false, List.of(), List.of()), (String) null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 做种数等于下限_保留() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("t", 3, 5_000_000_000L, false)),
                criteria(3, 0L, 0L, false, List.of(), List.of()), (String) null);

        assertEquals(1, result.size());
    }

    @Test
    void 体积小于下限_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("t", 10, 500L, false)),
                criteria(0, 1_000L, 0L, false, List.of(), List.of()), (String) null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 体积大于上限_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("t", 10, 90_000_000_000L, false)),
                criteria(0, 0L, 50_000_000_000L, false, List.of(), List.of()), (String) null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 体积上下限为0_表示不限() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("t", 10, 1L, false), torrent("t2", 10, 999_999_999_999L, false)),
                criteria(0, 0L, 0L, false, List.of(), List.of()), (String) null);

        assertEquals(2, result.size());
    }

    @Test
    void 仅要免费_非免费种被淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("free", 10, 100L, true), torrent("paid", 10, 100L, false)),
                criteria(0, 0L, 0L, true, List.of(), List.of()), (String) null);

        assertEquals(1, result.size());
        assertEquals("free", result.get(0).getTitle());
    }

    @Test
    void 命中排除词_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("Some.Show.预告片.1080p", 10, 100L, false)),
                criteria(0, 0L, 0L, false, List.of(), List.of("预告", "花絮")), (String) null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 排除词大小写不敏感() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("Some.Show.SAMPLES.1080p", 10, 100L, false)),
                criteria(0, 0L, 0L, false, List.of(), List.of("samples")), (String) null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 包含词非空_一个都没命中则淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("Some.Show.1080p.WEB-DL", 10, 100L, false)),
                criteria(0, 0L, 0L, false, List.of("中字", "国语"), List.of()), (String) null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 包含词命中其一即保留() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("Some.Show.1080p.中字", 10, 100L, false)),
                criteria(0, 0L, 0L, false, List.of("中字", "国语"), List.of()), (String) null);

        assertEquals(1, result.size());
    }

    @Test
    void 排除优先于包含_同时命中两者时淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("Some.Show.中字.预告", 10, 100L, false)),
                criteria(0, 0L, 0L, false, List.of("中字"), List.of("预告")), (String) null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 多个候选_只保留合格的() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("good.1080p", 10, 5_000_000_000L, false),
                        torrent("低做种.1080p", 1, 5_000_000_000L, false),
                        torrent("预告.1080p", 10, 5_000_000_000L, false),
                        torrent("good2.1080p", 20, 5_000_000_000L, false)),
                criteria(5, 0L, 0L, false, List.of(), List.of("预告")), (String) null);

        assertEquals(List.of("good.1080p", "good2.1080p"),
                result.stream().map(TorrentInfo::getTitle).toList());
    }

    @Test
    void 输入为空列表_返回空列表() {
        assertTrue(engine.filter(List.of(), criteria(0, 0L, 0L, false, List.of(), List.of()), (String) null).isEmpty());
    }

    @Test
    void 标题为null的候选_被淘汰而非抛异常() {
        TorrentInfo t = torrent(null, 10, 100L, false);

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(0, 0L, 0L, false, List.of(), List.of("预告")), (String) null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 结果列表不含原列表引用_不会被调用方修改() {
        List<TorrentInfo> result = engine.filter(List.of(ok()),
                criteria(0, 0L, 0L, false, List.of(), List.of()), (String) null);

        // 返回新列表而非原列表的视图
        result.add(ok());
        assertEquals(2, result.size());
    }

    @Test
    void 淘汰记录写debug日志且原因具体到规则阈值与实际值() {
        // 规格要求：被淘汰的种子记 debug 日志，且原因必须具体（哪条规则、阈值、实际值），
        // 不能只写一句"被过滤"。用 ListAppender 把日志内容锁住，而不是靠人工阅读代码保证。
        Logger logger = (Logger) LoggerFactory.getLogger(TorrentFilterEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level originalLevel = logger.getLevel();
        // debug 日志默认级别通常不输出，不显式调低级别就看不到这条日志
        logger.setLevel(Level.DEBUG);
        try {
            engine.filter(List.of(torrent("做种不足的种子", 2, 5_000_000_000L, false)),
                    criteria(10, 0L, 0L, false, List.of(), List.of()), (String) null);

            String logged = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.DEBUG)
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);

            assertTrue(logged.contains("做种数"), "应指明是哪条规则淘汰的，实际内容：" + logged);
            assertTrue(logged.contains("10"), "应带上配置的阈值，实际内容：" + logged);
            assertTrue(logged.contains("2"), "应带上种子的实际值，实际内容：" + logged);
        } finally {
            logger.setLevel(originalLevel);
            logger.detachAppender(appender);
        }
    }

    // ---------- 分辨率白名单(硬性过滤，不同于只影响排序的 resolutionPriority) ----------

    @Test
    void 白名单命中_保留() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithResolution("t", "1080p")),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), (String) null);

        assertEquals(1, result.size());
    }

    @Test
    void 白名单未命中_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithResolution("t", "720p")),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), (String) null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 白名单大小写不敏感() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithResolution("t", "1080P")),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), (String) null);

        assertEquals(1, result.size());
    }

    @Test
    void 白名单非空_解析不出分辨率则淘汰() {
        // 无法判定是否在白名单内的一律不放行，而不是当作"无所谓"直接通过
        List<TorrentInfo> resultNull = engine.filter(
                List.of(torrentWithResolution("t", (String) null)),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), (String) null);
        List<TorrentInfo> resultBlank = engine.filter(
                List.of(torrentWithResolution("t2", "   ")),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), (String) null);

        assertTrue(resultNull.isEmpty());
        assertTrue(resultBlank.isEmpty());
    }

    @Test
    void 白名单为空_不限制分辨率() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithResolution("t", "480p")),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of()), (String) null);

        assertEquals(1, result.size());
    }

    @Test
    void 白名单未命中_淘汰原因写明白名单内容与实际分辨率() {
        Logger logger = (Logger) LoggerFactory.getLogger(TorrentFilterEngine.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Level originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        try {
            List<TorrentInfo> result = engine.filter(
                    List.of(torrentWithResolution("t", "720p")),
                    criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), (String) null);

            assertTrue(result.isEmpty());

            String logged = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.DEBUG)
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);

            assertTrue(logged.contains("720p"), "应带上种子的实际分辨率，实际内容：" + logged);
            assertTrue(logged.contains("2160p") && logged.contains("1080p"),
                    "应带上白名单内容，实际内容：" + logged);
        } finally {
            logger.setLevel(originalLevel);
            logger.detachAppender(appender);
        }
    }

    // ---------- 种子/发布组黑名单（不改变以上任何现有用例的调用方式或断言） ----------

    @Test
    void GUID命中黑名单_淘汰原因包含拉黑() {
        TorrentInfo t = ok();
        t.setGuid("bad-guid");
        TorrentBlacklist blacklist = new TorrentBlacklist(
                Set.of(com.ruoyi.openliststrm.pt.indexer.GuidHasher.hash("bad-guid")), Set.of());

        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertFalse(verdicts.get(0).accepted());
        assertTrue(verdicts.get(0).rejectReason().contains("拉黑"));
    }

    @Test
    void GUID未命中黑名单_不受影响_走原有判定链() {
        TorrentInfo t = ok();
        t.setGuid("good-guid");
        TorrentBlacklist blacklist = new TorrentBlacklist(
                Set.of(com.ruoyi.openliststrm.pt.indexer.GuidHasher.hash("other-guid")), Set.of());

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertEquals(1, result.size());
    }

    @Test
    void 发布组命中黑名单_大小写不一致也命中_淘汰() {
        TorrentInfo t = ok();
        t.setParsedReleaseGroup("chdweb");
        TorrentBlacklist blacklist = new TorrentBlacklist(Set.of(), Set.of("CHDWEB"));

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertTrue(result.isEmpty());
    }

    @Test
    void 发布组未命中黑名单_不受影响() {
        TorrentInfo t = ok();
        t.setParsedReleaseGroup("someother");
        TorrentBlacklist blacklist = new TorrentBlacklist(Set.of(), Set.of("CHDWEB"));

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertEquals(1, result.size());
    }

    @Test
    void 标题为空_即使parsedReleaseGroup非空_仍先被标题为空淘汰() {
        TorrentInfo t = torrent(null, 10, 100L, false);
        t.setParsedReleaseGroup("CHDWEB");
        TorrentBlacklist blacklist = new TorrentBlacklist(Set.of(), Set.of("CHDWEB"));

        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                criteria(0, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertTrue(verdicts.get(0).rejectReason().contains("标题为空"));
    }

    @Test
    void 同时命中GUID和做种数不足_淘汰原因是GUID命中() {
        TorrentInfo t = torrent("t", 1, 100L, false);
        t.setGuid("bad-guid");
        TorrentBlacklist blacklist = new TorrentBlacklist(
                Set.of(com.ruoyi.openliststrm.pt.indexer.GuidHasher.hash("bad-guid")), Set.of());

        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                criteria(10, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertTrue(verdicts.get(0).rejectReason().contains("拉黑"));
    }

    @Test
    void 未传黑名单参数_两参旧签名_行为与改动前完全一致() {
        List<TorrentInfo> result = engine.filter(List.of(ok()),
                criteria(1, 0L, 0L, false, List.of(), List.of()));

        assertEquals(1, result.size());
    }

    @Test
    void TorrentBlacklistEMPTY_与两参旧签名结果一致() {
        List<TorrentInfo> withEmpty = engine.filter(List.of(ok()),
                criteria(1, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY);
        List<TorrentInfo> withoutBlacklist = engine.filter(List.of(ok()),
                criteria(1, 0L, 0L, false, List.of(), List.of()));

        assertEquals(withoutBlacklist.size(), withEmpty.size());
    }
}
