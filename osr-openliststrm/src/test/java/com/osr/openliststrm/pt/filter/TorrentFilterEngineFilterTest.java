package com.osr.openliststrm.pt.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.osr.openliststrm.pt.model.TorrentInfo;
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
        return FilterCriteria.builder()
                .minSeeders(minSeeders)
                .minSize(minSize)
                .maxSize(maxSize)
                .freeOnly(freeOnly)
                .includeKeywords(include)
                .excludeKeywords(exclude)
                .resolutionPriority(List.of("1080p"))
                .resolutionWhitelist(resolutionWhitelist)
                .sortPriority(List.of(SortDimension.SEEDERS))
                .build();
    }

    // ---------- H&R 规避 ----------

    private TorrentInfo torrentFromHrSite(boolean hitAndRun) {
        TorrentInfo t = torrent("Some.Show.S01E01.1080p", 10, 5_000_000_000L, false);
        t.setHitAndRun(hitAndRun);
        return t;
    }

    private FilterCriteria hrCriteria(boolean avoidHitAndRun) {
        return FilterCriteria.builder()
                .avoidHitAndRun(avoidHitAndRun)
                .sortPriority(List.of(SortDimension.SEEDERS))
                .build();
    }

    @Test
    void 开启规避HR_来自HR站点的候选被淘汰() {
        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(torrentFromHrSite(true)),
                hrCriteria(true), TorrentBlacklist.EMPTY, null);

        assertFalse(verdicts.get(0).accepted());
        assertTrue(verdicts.get(0).rejectReason().contains("H&R"));
    }

    @Test
    void 开启规避HR_非HR站点的候选照常放行() {
        List<TorrentInfo> result = engine.filter(List.of(torrentFromHrSite(false)),
                hrCriteria(true), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 未开启规避HR_HR站点的候选照常放行() {
        // 默认必须关闭：H&R 站点往往正是资源质量最好的站点，默认排除会让大量订阅无种可下
        List<TorrentInfo> result = engine.filter(List.of(torrentFromHrSite(true)),
                hrCriteria(false), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    // ---------- 画质维度：来源白名单 / 质量标签 ----------

    private TorrentInfo torrentWithQuality(String source, List<String> tags) {
        TorrentInfo t = torrent("Some.Show.S01E01.1080p", 10, 5_000_000_000L, false);
        t.setParsedSource(source);
        t.setParsedTags(new java.util.ArrayList<>(tags));
        return t;
    }

    private FilterCriteria qualityCriteria(List<String> sourceWhitelist, List<String> requiredTags,
                                           List<String> excludeTags) {
        return FilterCriteria.builder()
                .sourceWhitelist(sourceWhitelist)
                .requiredTags(requiredTags)
                .excludeTags(excludeTags)
                .sortPriority(List.of(SortDimension.SEEDERS))
                .build();
    }

    @Test
    void 来源在白名单内_放行() {
        List<TorrentInfo> result = engine.filter(List.of(torrentWithQuality("WEBDL", List.of())),
                qualityCriteria(List.of("REMUX", "BluRay", "WEBDL"), List.of(), List.of()),
                TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 来源不在白名单内_淘汰() {
        List<TorrentInfo> result = engine.filter(List.of(torrentWithQuality("HDTV", List.of())),
                qualityCriteria(List.of("REMUX", "BluRay"), List.of(), List.of()),
                TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 来源白名单大小写不敏感() {
        // 索引器标题里 WEB-DL / web-dl 都出现过，归一化后的取值大小写也不保证统一
        List<TorrentInfo> result = engine.filter(List.of(torrentWithQuality("webdl", List.of())),
                qualityCriteria(List.of("WEBDL"), List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 来源解析不出_白名单非空时淘汰() {
        // 与分辨率白名单同构：判定不了是否满足，就不能放行
        List<TorrentInfo> result = engine.filter(List.of(torrentWithQuality(null, List.of())),
                qualityCriteria(List.of("REMUX"), List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 来源解析不出_白名单为空时放行() {
        List<TorrentInfo> result = engine.filter(List.of(torrentWithQuality(null, List.of())),
                qualityCriteria(List.of(), List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 必需标签全部具备_放行() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithQuality("WEBDL", List.of("HDR10", "ATMOS", "10BIT"))),
                qualityCriteria(List.of(), List.of("HDR10", "ATMOS"), List.of()), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 必需标签是AND语义_只具备其中一个也淘汰() {
        // 想表达"任选其一"要用标题包含词，那一项本就是 OR 语义
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithQuality("WEBDL", List.of("HDR10"))),
                qualityCriteria(List.of(), List.of("HDR10", "ATMOS"), List.of()), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 必需标签淘汰原因带上已解析到的标签_便于排查() {
        TorrentInfo t = torrentWithQuality("WEBDL", List.of("HDR10"));
        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                qualityCriteria(List.of(), List.of("ATMOS"), List.of()), TorrentBlacklist.EMPTY, null);

        assertFalse(verdicts.get(0).accepted());
        assertTrue(verdicts.get(0).rejectReason().contains("ATMOS"));
        assertTrue(verdicts.get(0).rejectReason().contains("HDR10"));
    }

    @Test
    void 一个标签都没解析出来_必需标签非空时淘汰且原因说明为无() {
        TorrentInfo t = torrentWithQuality("WEBDL", List.of());
        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                qualityCriteria(List.of(), List.of("HDR10"), List.of()), TorrentBlacklist.EMPTY, null);

        assertFalse(verdicts.get(0).accepted());
        assertTrue(verdicts.get(0).rejectReason().contains("无"));
    }

    @Test
    void 命中排除标签_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithQuality("BluRay", List.of("REMUX"))),
                qualityCriteria(List.of(), List.of(), List.of("REMUX")), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 标签整词相等而非子串包含_HDR不该命中HDR10() {
        // 子串包含会让配了 "HDR" 的用户连 HDR10 一起排除掉，那不是他要的
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithQuality("WEBDL", List.of("HDR10"))),
                qualityCriteria(List.of(), List.of(), List.of("HDR")), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 标签列表为null_不抛异常且按无标签处理() {
        // 引擎是纯函数，得扛住调用方塞进来的任何输入
        TorrentInfo t = torrentWithQuality("WEBDL", List.of());
        t.setParsedTags(null);

        assertTrue(engine.filter(List.of(t), qualityCriteria(List.of(), List.of("HDR10"), List.of()),
                TorrentBlacklist.EMPTY, null).isEmpty());
        assertEquals(1, engine.filter(List.of(t), qualityCriteria(List.of(), List.of(), List.of("HDR10")),
                TorrentBlacklist.EMPTY, null).size());
    }

    @Test
    void 未配置任何画质规则_行为与改动前一致() {
        // 新维度默认全空 = 不限，对既有部署必须是行为无变化的
        List<TorrentInfo> result = engine.filter(List.of(torrentWithQuality(null, List.of())),
                criteria(1, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
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
                criteria(1, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 做种数低于下限_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("t", 2, 5_000_000_000L, false)),
                criteria(3, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 做种数等于下限_保留() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("t", 3, 5_000_000_000L, false)),
                criteria(3, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 体积小于下限_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("t", 10, 500L, false)),
                criteria(0, 1_000L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 体积大于上限_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("t", 10, 90_000_000_000L, false)),
                criteria(0, 0L, 50_000_000_000L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 体积上下限为0_表示不限() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("t", 10, 1L, false), torrent("t2", 10, 999_999_999_999L, false)),
                criteria(0, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertEquals(2, result.size());
    }

    @Test
    void 仅要免费_非免费种被淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("free", 10, 100L, true), torrent("paid", 10, 100L, false)),
                criteria(0, 0L, 0L, true, List.of(), List.of()), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
        assertEquals("free", result.get(0).getTitle());
    }

    @Test
    void 命中排除词_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("Some.Show.预告片.1080p", 10, 100L, false)),
                criteria(0, 0L, 0L, false, List.of(), List.of("预告", "花絮")), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 排除词大小写不敏感() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("Some.Show.SAMPLES.1080p", 10, 100L, false)),
                criteria(0, 0L, 0L, false, List.of(), List.of("samples")), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 包含词非空_一个都没命中则淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("Some.Show.1080p.WEB-DL", 10, 100L, false)),
                criteria(0, 0L, 0L, false, List.of("中字", "国语"), List.of()), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 包含词命中其一即保留() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("Some.Show.1080p.中字", 10, 100L, false)),
                criteria(0, 0L, 0L, false, List.of("中字", "国语"), List.of()), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 排除优先于包含_同时命中两者时淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("Some.Show.中字.预告", 10, 100L, false)),
                criteria(0, 0L, 0L, false, List.of("中字"), List.of("预告")), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 多个候选_只保留合格的() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrent("good.1080p", 10, 5_000_000_000L, false),
                        torrent("低做种.1080p", 1, 5_000_000_000L, false),
                        torrent("预告.1080p", 10, 5_000_000_000L, false),
                        torrent("good2.1080p", 20, 5_000_000_000L, false)),
                criteria(5, 0L, 0L, false, List.of(), List.of("预告")), TorrentBlacklist.EMPTY, null);

        assertEquals(List.of("good.1080p", "good2.1080p"),
                result.stream().map(TorrentInfo::getTitle).toList());
    }

    @Test
    void 输入为空列表_返回空列表() {
        assertTrue(engine.filter(List.of(), criteria(0, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null).isEmpty());
    }

    @Test
    void 标题为null的候选_被淘汰而非抛异常() {
        TorrentInfo t = torrent(null, 10, 100L, false);

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(0, 0L, 0L, false, List.of(), List.of("预告")), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 结果列表不含原列表引用_不会被调用方修改() {
        List<TorrentInfo> result = engine.filter(List.of(ok()),
                criteria(0, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null);

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
                    criteria(10, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null);

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
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 白名单未命中_淘汰() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithResolution("t", "720p")),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), TorrentBlacklist.EMPTY, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 白名单大小写不敏感() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithResolution("t", "1080P")),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), TorrentBlacklist.EMPTY, null);

        assertEquals(1, result.size());
    }

    @Test
    void 白名单非空_解析不出分辨率则淘汰() {
        // 无法判定是否在白名单内的一律不放行，而不是当作"无所谓"直接通过
        List<TorrentInfo> resultNull = engine.filter(
                List.of(torrentWithResolution("t", (String) null)),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), TorrentBlacklist.EMPTY, null);
        List<TorrentInfo> resultBlank = engine.filter(
                List.of(torrentWithResolution("t2", "   ")),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), TorrentBlacklist.EMPTY, null);

        assertTrue(resultNull.isEmpty());
        assertTrue(resultBlank.isEmpty());
    }

    @Test
    void 白名单为空_不限制分辨率() {
        List<TorrentInfo> result = engine.filter(
                List.of(torrentWithResolution("t", "480p")),
                criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of()), TorrentBlacklist.EMPTY, null);

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
                    criteriaWithWhitelist(0, 0L, 0L, false, List.of(), List.of(), List.of("2160p", "1080p")), TorrentBlacklist.EMPTY, null);

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
                Set.of(com.osr.openliststrm.pt.indexer.GuidHasher.hash("bad-guid")), Set.of());

        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist, null);

        assertFalse(verdicts.get(0).accepted());
        assertTrue(verdicts.get(0).rejectReason().contains("拉黑"));
    }

    @Test
    void GUID未命中黑名单_不受影响_走原有判定链() {
        TorrentInfo t = ok();
        t.setGuid("good-guid");
        TorrentBlacklist blacklist = new TorrentBlacklist(
                Set.of(com.osr.openliststrm.pt.indexer.GuidHasher.hash("other-guid")), Set.of());

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist, null);

        assertEquals(1, result.size());
    }

    @Test
    void 发布组命中黑名单_大小写不一致也命中_淘汰() {
        TorrentInfo t = ok();
        t.setParsedReleaseGroup("chdweb");
        TorrentBlacklist blacklist = new TorrentBlacklist(Set.of(), Set.of("CHDWEB"));

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void 发布组未命中黑名单_不受影响() {
        TorrentInfo t = ok();
        t.setParsedReleaseGroup("someother");
        TorrentBlacklist blacklist = new TorrentBlacklist(Set.of(), Set.of("CHDWEB"));

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist, null);

        assertEquals(1, result.size());
    }

    @Test
    void 标题为空_即使parsedReleaseGroup非空_仍先被标题为空淘汰() {
        TorrentInfo t = torrent(null, 10, 100L, false);
        t.setParsedReleaseGroup("CHDWEB");
        TorrentBlacklist blacklist = new TorrentBlacklist(Set.of(), Set.of("CHDWEB"));

        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                criteria(0, 0L, 0L, false, List.of(), List.of()), blacklist, null);

        assertTrue(verdicts.get(0).rejectReason().contains("标题为空"));
    }

    @Test
    void 同时命中GUID和做种数不足_淘汰原因是GUID命中() {
        TorrentInfo t = torrent("t", 1, 100L, false);
        t.setGuid("bad-guid");
        TorrentBlacklist blacklist = new TorrentBlacklist(
                Set.of(com.osr.openliststrm.pt.indexer.GuidHasher.hash("bad-guid")), Set.of());

        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                criteria(10, 0L, 0L, false, List.of(), List.of()), blacklist, null);

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
                criteria(1, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY, null);
        List<TorrentInfo> withoutBlacklist = engine.filter(List.of(ok()),
                criteria(1, 0L, 0L, false, List.of(), List.of()));

        assertEquals(withoutBlacklist.size(), withEmpty.size());
    }
}
