package com.osr.openliststrm.rename.rule;

import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CategoryRuleValidatorTest {

    private RenameCategoryRulePlus rule(String targetDir, boolean fallback) {
        RenameCategoryRulePlus r = new RenameCategoryRulePlus();
        r.setTargetDir(targetDir);
        r.setIsFallback(fallback ? "1" : "0");
        return r;
    }

    @Test
    void validate_合法列表_不抛异常() {
        List<RenameCategoryRulePlus> rules = new ArrayList<>();
        rules.add(rule("国漫", false));
        rules.add(rule("未分类", true));
        assertDoesNotThrow(() -> CategoryRuleValidator.validate(rules));
    }

    @Test
    void validate_列表为空_抛异常() {
        assertThrows(IllegalArgumentException.class, () -> CategoryRuleValidator.validate(new ArrayList<>()));
    }

    @Test
    void validate_存在目标目录为空的规则_抛异常() {
        List<RenameCategoryRulePlus> rules = new ArrayList<>();
        rules.add(rule("", false));
        rules.add(rule("未分类", true));
        assertThrows(IllegalArgumentException.class, () -> CategoryRuleValidator.validate(rules));
    }

    @Test
    void validate_没有兜底规则_抛异常() {
        List<RenameCategoryRulePlus> rules = new ArrayList<>();
        rules.add(rule("国漫", false));
        rules.add(rule("日番", false));
        assertThrows(IllegalArgumentException.class, () -> CategoryRuleValidator.validate(rules));
    }

    @Test
    void validate_存在两条兜底规则_抛异常() {
        List<RenameCategoryRulePlus> rules = new ArrayList<>();
        rules.add(rule("国漫", true));
        rules.add(rule("未分类", true));
        assertThrows(IllegalArgumentException.class, () -> CategoryRuleValidator.validate(rules));
    }

    @Test
    void validate_兜底规则不在最后一位_抛异常() {
        List<RenameCategoryRulePlus> rules = new ArrayList<>();
        rules.add(rule("未分类", true));
        rules.add(rule("国漫", false));
        assertThrows(IllegalArgumentException.class, () -> CategoryRuleValidator.validate(rules));
    }
}
