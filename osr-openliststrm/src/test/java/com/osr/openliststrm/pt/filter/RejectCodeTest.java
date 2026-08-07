package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.pt.model.TorrentInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 淘汰原因码：每条规则必须给出<b>自己的</b>码，不能塌成 OTHER——
 * 塌了统计面板上就只剩一个"其它"，等于没有统计。
 */
class RejectCodeTest {

    private final TorrentFilterEngine engine = new TorrentFilterEngine();

    private TorrentInfo torrent(String title, int seeders, long size) {
        TorrentInfo t = new TorrentInfo();
        t.setTitle(title);
        t.setSeeders(seeders);
        t.setSize(size);
        t.setGuid(title);
        return t;
    }

    private RejectCode codeOf(TorrentInfo t, FilterCriteria criteria) {
        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t), criteria);
        return verdicts.get(0).rejectCode();
    }

    @Test
    void 通过的候选_码与文案都为null() {
        FilterCriteria criteria = FilterCriteria.builder().minSeeders(0).build();

        TorrentFilterEngine.Verdict v = engine.evaluate(List.of(torrent("Some.Show.S01E01", 10, 1L)), criteria).get(0);

        assertEquals(true, v.accepted());
        assertNull(v.rejectCode());
        assertNull(v.rejectReason());
    }

    @Test
    void 做种数不足() {
        assertEquals(RejectCode.LOW_SEEDERS,
                codeOf(torrent("Some.Show.S01E01", 1, 1L), FilterCriteria.builder().minSeeders(5).build()));
    }

    @Test
    void 体积上下限各自有码() {
        assertEquals(RejectCode.SIZE_BELOW_MIN,
                codeOf(torrent("x", 10, 100L), FilterCriteria.builder().minSeeders(0).minSize(1000L).build()));
        assertEquals(RejectCode.SIZE_ABOVE_MAX,
                codeOf(torrent("x", 10, 5000L), FilterCriteria.builder().minSeeders(0).maxSize(1000L).build()));
    }

    @Test
    void 非免费种() {
        // downloadVolumeFactor 默认 1.0（索引器不返回该字段时按正常计量处理），
        // 这正是 freeOnly 最容易整站清零的原因
        assertEquals(RejectCode.NOT_FREE,
                codeOf(torrent("x", 10, 1L), FilterCriteria.builder().minSeeders(0).freeOnly(true).build()));
    }

    @Test
    void 分辨率与来源白名单各自有码_解析不出也算命中() {
        assertEquals(RejectCode.RESOLUTION_NOT_ALLOWED,
                codeOf(torrent("无分辨率信息的标题", 10, 1L),
                        FilterCriteria.builder().minSeeders(0).resolutionWhitelist(List.of("2160p")).build()));
        assertEquals(RejectCode.SOURCE_NOT_ALLOWED,
                codeOf(torrent("无来源信息的标题", 10, 1L),
                        FilterCriteria.builder().minSeeders(0).sourceWhitelist(List.of("BluRay")).build()));
    }

    @Test
    void 排除词与包含词各自有码() {
        assertEquals(RejectCode.EXCLUDED_KEYWORD,
                codeOf(torrent("Some.Show.CAM", 10, 1L),
                        FilterCriteria.builder().minSeeders(0).excludeKeywords(List.of("CAM")).build()));
        assertEquals(RejectCode.NO_INCLUDE_KEYWORD,
                codeOf(torrent("Some.Show", 10, 1L),
                        FilterCriteria.builder().minSeeders(0).includeKeywords(List.of("中字")).build()));
    }

    @Test
    void 标题为空() {
        assertEquals(RejectCode.BLANK_TITLE,
                codeOf(torrent("", 10, 1L), FilterCriteria.builder().minSeeders(0).build()));
    }

    @Test
    void 码与文案同时存在_文案带具体数值供逐条排查() {
        TorrentFilterEngine.Verdict v = engine.evaluate(
                List.of(torrent("x", 1, 1L)), FilterCriteria.builder().minSeeders(5).build()).get(0);

        assertEquals(RejectCode.LOW_SEEDERS, v.rejectCode());
        assertNotNull(v.rejectReason());
        // 文案保留实际值，否则逐条排查时看不出差多少
        assertEquals(true, v.rejectReason().contains("1") && v.rejectReason().contains("5"));
    }

    @Test
    void labelOf_未知取值原样返回_不塌成其它() {
        // 显示一个陌生取值，比显示一个错误的分类更诚实
        assertEquals("非免费种", RejectCode.labelOf("NOT_FREE"));
        assertEquals("SOME_FUTURE_CODE", RejectCode.labelOf("SOME_FUTURE_CODE"));
        assertEquals("其它", RejectCode.labelOf(null));
        assertEquals("其它", RejectCode.labelOf("  "));
    }

    @Test
    void 每个码都有非空中文标签() {
        for (RejectCode code : RejectCode.values()) {
            assertNotNull(code.label(), code.name() + " 缺少中文标签");
            assertEquals(false, code.label().isBlank(), code.name() + " 的标签是空白");
            assertEquals(code.name(), code.value());
        }
    }
}
