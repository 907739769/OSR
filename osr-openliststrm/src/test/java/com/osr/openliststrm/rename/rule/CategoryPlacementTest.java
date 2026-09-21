package com.osr.openliststrm.rename.rule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CategoryPlacementTest {

    @Test
    void topLevelOf_只有movie走电影其余都走电视剧() {
        assertEquals("电影", CategoryPlacement.topLevelOf("movie"));
        assertEquals("电视剧", CategoryPlacement.topLevelOf("tv"));
        assertEquals("电视剧", CategoryPlacement.topLevelOf(null));
    }

    @Test
    void categoryOrDefault_空值退回兜底目录() {
        assertEquals("未分类", CategoryPlacement.categoryOrDefault(null));
        assertEquals("未分类", CategoryPlacement.categoryOrDefault("   "));
        assertEquals("日番", CategoryPlacement.categoryOrDefault("日番"));
    }

    /**
     * 这条守的是「重命名测试」与真正重命名的判定一致性：
     * 只看季号的话，解析出集号却没解析出季号的文件（S 缺失、只有 E03 / 绝对集号）
     * 会在预览里被判成电影、落进「电影/...」，而实际执行时判的是剧集——
     * 预览与落盘不一致，正是这个预览存在的意义被抵消掉的那种失败。
     */
    @Test
    void inferMediaType_只有集号没有季号也算剧集() {
        assertEquals("tv", CategoryPlacement.inferMediaType("1", "3"));
        assertEquals("tv", CategoryPlacement.inferMediaType(null, "3"));
        assertEquals("tv", CategoryPlacement.inferMediaType("1", null));
        assertEquals("movie", CategoryPlacement.inferMediaType(null, null));
    }
}
