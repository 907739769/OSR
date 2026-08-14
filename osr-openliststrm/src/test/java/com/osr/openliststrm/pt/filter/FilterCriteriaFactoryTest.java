package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterCriteriaFactoryTest {

    private PtFilterConfigPlus globalConfig() {
        PtFilterConfigPlus c = new PtFilterConfigPlus();
        c.setMinSeeders(3);
        c.setMinSize(1_000L);
        c.setMaxSize(90_000_000_000L);
        c.setFreeOnly("0");
        c.setIncludeKeywords(null);
        c.setExcludeKeywords("预告,花絮");
        c.setResolutionPriority("2160p,1080p,720p");
        c.setResolutionWhitelist(null);
        c.setSortPriority("RESOLUTION,FREE,SEEDERS,SIZE");
        c.setPreferredSize(0L);
        return c;
    }

    // ---------- 画质维度 ----------

    @Test
    void 画质维度_沿用全局配置() {
        PtFilterConfigPlus global = globalConfig();
        global.setSourceWhitelist("REMUX,BluRay");
        global.setSourcePriority("REMUX,BluRay,WEBDL");
        global.setRequiredTags("HDR10");
        global.setExcludeTags("CAM");
        global.setReleaseGroupPriority("CHDBits,FRDS");

        FilterCriteria c = FilterCriteriaFactory.build(global, null);

        assertEquals(List.of("REMUX", "BluRay"), c.sourceWhitelist());
        assertEquals(List.of("REMUX", "BluRay", "WEBDL"), c.sourcePriority());
        assertEquals(List.of("HDR10"), c.requiredTags());
        assertEquals(List.of("CAM"), c.excludeTags());
        assertEquals(List.of("CHDBits", "FRDS"), c.releaseGroupPriority());
    }

    @Test
    void 画质维度_订阅级覆盖生效() {
        PtFilterConfigPlus global = globalConfig();
        global.setSourceWhitelist("REMUX");
        global.setRequiredTags("HDR10");

        FilterCriteria c = FilterCriteriaFactory.build(global,
                "{\"sourceWhitelist\":\"WEBDL,HDTV\",\"requiredTags\":\"\"}");

        assertEquals(List.of("WEBDL", "HDTV"), c.sourceWhitelist());
        // 显式传空串意味着"这部剧不设该项"，是有效覆盖而非"未覆盖"
        assertTrue(c.requiredTags().isEmpty());
    }

    @Test
    void 画质维度_全局未配置_归一为空列表表示不限() {
        // 新列对既有部署是 NULL，必须落到"不限"而不是把所有候选都淘汰掉
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), null);

        assertTrue(c.sourceWhitelist().isEmpty());
        assertTrue(c.sourcePriority().isEmpty());
        assertTrue(c.requiredTags().isEmpty());
        assertTrue(c.excludeTags().isEmpty());
        assertTrue(c.releaseGroupPriority().isEmpty());
    }

    @Test
    void 描述排除词_全局未配置_归一为空列表表示不限() {
        // 新列对既有部署是 NULL，必须落到"不限"——若反过来当成"描述判不出就淘汰"会清光整站候选
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), null);

        assertTrue(c.descriptionExcludeKeywords().isEmpty());
    }

    @Test
    void 描述排除词_可按订阅覆盖() {
        // 典型场景：全局不收原盘，但这部剧只有原盘资源，单独放开
        PtFilterConfigPlus global = globalConfig();
        global.setDescriptionExcludeKeywords("原盘,BDMV");

        assertEquals(List.of("原盘", "BDMV"),
                FilterCriteriaFactory.build(global, null).descriptionExcludeKeywords());
        assertTrue(FilterCriteriaFactory.build(global, "{\"descriptionExcludeKeywords\":\"\"}")
                .descriptionExcludeKeywords().isEmpty());
    }

    @Test
    void 无覆盖_全部沿用全局配置() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), null);

        assertEquals(3, c.minSeeders());
        assertEquals(1_000L, c.minSize());
        assertEquals(90_000_000_000L, c.maxSize());
        assertFalse(c.freeOnly());
        assertEquals(List.of("预告", "花絮"), c.excludeKeywords());
        assertEquals(List.of("2160p", "1080p", "720p"), c.resolutionPriority());
        assertTrue(c.resolutionWhitelist().isEmpty());
        assertEquals(FilterCriteria.DEFAULT_SORT_PRIORITY, c.sortPriority());
    }

    @Test
    void 空字符串覆盖_等同于无覆盖() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), "   ");

        assertEquals(3, c.minSeeders());
        assertEquals(List.of("2160p", "1080p", "720p"), c.resolutionPriority());
    }

    @Test
    void 部分覆盖_只有出现的键被替换_其余沿用全局() {
        // 典型场景：这部剧我要 4K，其余保持默认
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), "{\"resolutionPriority\":\"2160p\"}");

        assertEquals(List.of("2160p"), c.resolutionPriority());
        assertEquals(3, c.minSeeders(), "未出现在覆盖中的键必须沿用全局值");
        assertEquals(List.of("预告", "花絮"), c.excludeKeywords());
    }

    @Test
    void 覆盖数值型字段() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(),
                "{\"minSeeders\":10,\"minSize\":2000,\"maxSize\":3000,\"preferredSize\":2500}");

        assertEquals(10, c.minSeeders());
        assertEquals(2_000L, c.minSize());
        assertEquals(3_000L, c.maxSize());
        assertEquals(2_500L, c.preferredSize());
    }

    @Test
    void 覆盖仅免费开关() {
        assertTrue(FilterCriteriaFactory.build(globalConfig(), "{\"freeOnly\":\"1\"}").freeOnly());
        assertFalse(FilterCriteriaFactory.build(globalConfig(), "{\"freeOnly\":\"0\"}").freeOnly());
    }

    @Test
    void 覆盖分辨率白名单() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), "{\"resolutionWhitelist\":\"2160p,1080p\"}");

        assertEquals(List.of("2160p", "1080p"), c.resolutionWhitelist());
    }

    @Test
    void 覆盖排序维度顺序() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), "{\"sortPriority\":\"FREE,SEEDERS\"}");

        assertEquals(List.of(SortDimension.FREE, SortDimension.SEEDERS), c.sortPriority());
    }

    @Test
    void 覆盖关键词_可以把全局排除词清空() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), "{\"excludeKeywords\":\"\"}");

        // 显式传空串意味着「这部剧不排除任何关键词」，不能被当成"没覆盖"
        assertTrue(c.excludeKeywords().isEmpty());
    }

    @Test
    void 覆盖JSON非法_记警告并整体退回全局配置() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), "{这不是合法JSON");

        assertEquals(3, c.minSeeders());
        assertEquals(List.of("2160p", "1080p", "720p"), c.resolutionPriority());
    }

    @Test
    void 覆盖JSON是数组而非对象_退回全局配置() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), "[1,2,3]");

        assertEquals(3, c.minSeeders());
    }

    @Test
    void 全局配置字段为null_使用安全默认值而非NPE() {
        PtFilterConfigPlus empty = new PtFilterConfigPlus();

        FilterCriteria c = FilterCriteriaFactory.build(empty, null);

        assertEquals(0, c.minSeeders());
        assertEquals(0L, c.minSize());
        assertEquals(0L, c.maxSize());
        assertFalse(c.freeOnly());
        assertTrue(c.includeKeywords().isEmpty());
        assertTrue(c.excludeKeywords().isEmpty());
        assertTrue(c.resolutionPriority().isEmpty());
        assertTrue(c.resolutionWhitelist().isEmpty());
        assertEquals(FilterCriteria.DEFAULT_SORT_PRIORITY, c.sortPriority());
        assertEquals(0L, c.preferredSize());
    }

    @Test
    void 体积字段填成带单位的字符串_不抛异常且回退全局值() {
        // 用户最可能的误填形态：以为体积字段支持 "5GB" 这种写法
        FilterCriteria c = assertDoesNotThrow(
                () -> FilterCriteriaFactory.build(globalConfig(), "{\"minSize\":\"5GB\"}"));

        assertEquals(1_000L, c.minSize(), "取值失败时应回退全局值，而不是 0 或抛异常");
    }

    @Test
    void 做种数字段填成非数字字符串_不抛异常且回退全局值() {
        FilterCriteria c = assertDoesNotThrow(
                () -> FilterCriteriaFactory.build(globalConfig(), "{\"minSeeders\":\"abc\"}"));

        assertEquals(3, c.minSeeders());
    }

    @Test
    void 做种数字段类型为JSON数组_不抛异常且回退全局值() {
        FilterCriteria c = assertDoesNotThrow(
                () -> FilterCriteriaFactory.build(globalConfig(), "{\"minSeeders\":[]}"));

        assertEquals(3, c.minSeeders());
    }

    @Test
    void 覆盖freeOnly为布尔true_视为仅要免费() {
        // 表单/前端提交的 JSON 里 freeOnly 可能是原生布尔值而非字符串 "1"
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), "{\"freeOnly\":true}");

        assertTrue(c.freeOnly(), "freeOnly:true 不应被静默当成\"否\"，否则会下到收费种");
    }

    @Test
    void 覆盖freeOnly为字符串true_大小写不敏感() {
        assertTrue(FilterCriteriaFactory.build(globalConfig(), "{\"freeOnly\":\"TRUE\"}").freeOnly());
        assertTrue(FilterCriteriaFactory.build(globalConfig(), "{\"freeOnly\":\"True\"}").freeOnly());
        assertFalse(FilterCriteriaFactory.build(globalConfig(), "{\"freeOnly\":\"false\"}").freeOnly());
    }

    @Test
    void global参数为null_不抛异常且使用安全默认值() {
        FilterCriteria c = assertDoesNotThrow(() -> FilterCriteriaFactory.build(null, null));

        assertEquals(0, c.minSeeders());
        assertEquals(0L, c.minSize());
        assertEquals(0L, c.maxSize());
        assertFalse(c.freeOnly());
        assertTrue(c.includeKeywords().isEmpty());
        assertTrue(c.excludeKeywords().isEmpty());
        assertTrue(c.resolutionPriority().isEmpty());
        assertTrue(c.resolutionWhitelist().isEmpty());
        assertEquals(FilterCriteria.DEFAULT_SORT_PRIORITY, c.sortPriority());
        assertEquals(0L, c.preferredSize());
        assertFalse(c.requireChineseSubtitle());
    }

    @Test
    void 全局requireChineseSubtitle默认关闭() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), null);
        assertFalse(c.requireChineseSubtitle(), "默认应为 false");
    }

    @Test
    void 全局配置启用requireChineseSubtitle() {
        PtFilterConfigPlus config = globalConfig();
        config.setRequireChineseSubtitle("1");
        FilterCriteria c = FilterCriteriaFactory.build(config, null);
        assertTrue(c.requireChineseSubtitle());
    }

    @Test
    void 订阅覆盖关闭requireChineseSubtitle() {
        PtFilterConfigPlus config = globalConfig();
        config.setRequireChineseSubtitle("1");
        FilterCriteria c = FilterCriteriaFactory.build(config, "{\"requireChineseSubtitle\":\"0\"}");
        assertFalse(c.requireChineseSubtitle(), "订阅覆盖应能关掉全局开启的中字要求");
    }

    @Test
    void 订阅覆盖启用requireChineseSubtitle() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), "{\"requireChineseSubtitle\":\"1\"}");
        assertTrue(c.requireChineseSubtitle());
    }

    @Test
    void 覆盖requireChineseSubtitle为布尔true() {
        FilterCriteria c = FilterCriteriaFactory.build(globalConfig(), "{\"requireChineseSubtitle\":true}");
        assertTrue(c.requireChineseSubtitle(), "布尔 true 应被识别为开启");
    }

    @Test
    void 覆盖requireChineseSubtitle为字符串true_大小写不敏感() {
        assertTrue(FilterCriteriaFactory.build(globalConfig(), "{\"requireChineseSubtitle\":\"TRUE\"}").requireChineseSubtitle());
        assertTrue(FilterCriteriaFactory.build(globalConfig(), "{\"requireChineseSubtitle\":\"True\"}").requireChineseSubtitle());
        assertFalse(FilterCriteriaFactory.build(globalConfig(), "{\"requireChineseSubtitle\":\"false\"}").requireChineseSubtitle());
    }
}
