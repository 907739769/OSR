package com.osr.openliststrm.rename.rule;

import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;
import com.osr.openliststrm.rename.CategoryRule;
import com.osr.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoryRuleConverterTest {

    @Test
    void toCategoryRule_全部条件字段都为空_转换为无条件规则() {
        RenameCategoryRulePlus row = new RenameCategoryRulePlus();
        row.setTargetDir("未分类");
        CategoryRule rule = CategoryRuleConverter.toCategoryRule(row);
        assertEquals("未分类", rule.getName());
        MediaInfo info = new MediaInfo("x.mkv");
        assertTrue(rule.matches(info));
    }

    @Test
    void toCategoryRule_逗号分隔的genreIds_正确拆分并匹配() {
        RenameCategoryRulePlus row = new RenameCategoryRulePlus();
        row.setTargetDir("综艺");
        row.setGenreIds("10764,10767");
        CategoryRule rule = CategoryRuleConverter.toCategoryRule(row);

        MediaInfo matched = new MediaInfo("x.mkv");
        matched.setGenreIds(Arrays.asList("10767"));
        assertTrue(rule.matches(matched));

        MediaInfo notMatched = new MediaInfo("y.mkv");
        notMatched.setGenreIds(Arrays.asList("99"));
        assertFalse(rule.matches(notMatched));
    }

    @Test
    void toCategoryRule_逗号分隔的originCountries_正确拆分并匹配() {
        RenameCategoryRulePlus row = new RenameCategoryRulePlus();
        row.setTargetDir("国产剧");
        row.setOriginCountries("CN,TW,HK");
        CategoryRule rule = CategoryRuleConverter.toCategoryRule(row);

        MediaInfo matched = new MediaInfo("x.mkv");
        matched.setOriginCountries(Arrays.asList("TW"));
        assertTrue(rule.matches(matched));
    }

    @Test
    void toCategoryRules_按输入顺序转换整个列表() {
        RenameCategoryRulePlus row1 = new RenameCategoryRulePlus();
        row1.setTargetDir("A");
        RenameCategoryRulePlus row2 = new RenameCategoryRulePlus();
        row2.setTargetDir("B");
        List<CategoryRule> rules = CategoryRuleConverter.toCategoryRules(Arrays.asList(row1, row2));
        assertEquals(2, rules.size());
        assertEquals("A", rules.get(0).getName());
        assertEquals("B", rules.get(1).getName());
    }

    @Test
    void toCategoryRules_空列表_返回空列表() {
        assertTrue(CategoryRuleConverter.toCategoryRules(Collections.emptyList()).isEmpty());
    }
}
