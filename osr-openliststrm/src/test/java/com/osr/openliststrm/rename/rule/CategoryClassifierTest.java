package com.osr.openliststrm.rename.rule;

import com.osr.openliststrm.rename.CategoryRule;
import com.osr.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CategoryClassifierTest {

    // 与 20260720-rename-category-rule.sql 种子数据等价的规则集合，
    // 用于验证新匹配逻辑与原 MediaRenameProcessor.defaultRules() 的分类结果完全一致（回归保证）。
    private List<CategoryRule> movieRules() {
        return Arrays.asList(
                new CategoryRule("动画电影").withGenreIds("16"),
                new CategoryRule("华语电影").withOriginalLanguage("zh", "cn", "bo", "za"),
                new CategoryRule("外语电影")
        );
    }

    private List<CategoryRule> tvRules() {
        return Arrays.asList(
                new CategoryRule("国漫").withGenreIds("16").withOriginCountry("CN", "TW", "HK"),
                new CategoryRule("日番").withGenreIds("16").withOriginCountry("JP"),
                new CategoryRule("纪录片").withGenreIds("99"),
                new CategoryRule("儿童").withGenreIds("10762"),
                new CategoryRule("综艺").withGenreIds("10764", "10767"),
                new CategoryRule("国产剧").withOriginCountry("CN", "TW", "HK"),
                new CategoryRule("欧美剧").withOriginCountry("US", "FR", "GB", "DE", "ES", "IT", "NL", "PT", "RU", "UK"),
                new CategoryRule("日韩剧").withOriginCountry("JP", "KP", "KR", "TH", "IN", "SG"),
                new CategoryRule("未分类")
        );
    }

    @Test
    void classify_国漫_同时命中动画和CN应归为国漫而非动画电影() {
        MediaInfo info = new MediaInfo("x.mkv");
        info.setGenreIds(Arrays.asList("16"));
        info.setOriginCountries(Arrays.asList("CN"));
        assertEquals("国漫", CategoryClassifier.classify(tvRules(), info));
    }

    @Test
    void classify_日番_动画且日本() {
        MediaInfo info = new MediaInfo("x.mkv");
        info.setGenreIds(Arrays.asList("16"));
        info.setOriginCountries(Arrays.asList("JP"));
        assertEquals("日番", CategoryClassifier.classify(tvRules(), info));
    }

    @Test
    void classify_欧美剧_美国出品且非动画() {
        MediaInfo info = new MediaInfo("x.mkv");
        info.setOriginCountries(Arrays.asList("US"));
        assertEquals("欧美剧", CategoryClassifier.classify(tvRules(), info));
    }

    @Test
    void classify_未分类_什么条件都不满足时落到兜底() {
        MediaInfo info = new MediaInfo("x.mkv");
        info.setOriginCountries(Arrays.asList("BR"));
        assertEquals("未分类", CategoryClassifier.classify(tvRules(), info));
    }

    @Test
    void classify_动画电影_电影类型且genre为动画() {
        MediaInfo info = new MediaInfo("x.mkv");
        info.setGenreIds(Arrays.asList("16"));
        assertEquals("动画电影", CategoryClassifier.classify(movieRules(), info));
    }

    @Test
    void classify_华语电影_原始语言为zh() {
        MediaInfo info = new MediaInfo("x.mkv");
        info.setOriginalLanguage("zh");
        assertEquals("华语电影", CategoryClassifier.classify(movieRules(), info));
    }

    @Test
    void classify_外语电影_什么条件都不满足时落到兜底() {
        MediaInfo info = new MediaInfo("x.mkv");
        info.setOriginalLanguage("en");
        assertEquals("外语电影", CategoryClassifier.classify(movieRules(), info));
    }

    @Test
    void classify_规则列表为空_返回null由调用方自行兜底() {
        MediaInfo info = new MediaInfo("x.mkv");
        assertNull(CategoryClassifier.classify(List.of(), info));
    }
}
