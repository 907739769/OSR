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

    /**
     * targetDir 最终落在 targetRoot.resolve(category) 上：带 / 会静默多建一层目录，
     * 而 .. 直接把整个媒体库写到 targetRoot 外面去。接口可以被直接调，
     * 前端那道校验挡的只是误操作，真正的边界在这里。
     */
    @Test
    void validate_目标目录名带路径字符_抛异常() {
        for (String bad : new String[]{"外语/电影", "外语\\电影", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b"}) {
            List<RenameCategoryRulePlus> rules = new ArrayList<>();
            rules.add(rule(bad, false));
            rules.add(rule("未分类", true));
            assertThrows(IllegalArgumentException.class, () -> CategoryRuleValidator.validate(rules), bad);
        }
    }

    @Test
    void validate_目标目录名是点或双点_抛异常() {
        for (String bad : new String[]{".", "..", "  ..  "}) {
            List<RenameCategoryRulePlus> rules = new ArrayList<>();
            rules.add(rule(bad, false));
            rules.add(rule("未分类", true));
            assertThrows(IllegalArgumentException.class, () -> CategoryRuleValidator.validate(rules), bad);
        }
    }

    @Test
    void validate_目标目录名超过列宽_抛异常() {
        List<RenameCategoryRulePlus> rules = new ArrayList<>();
        rules.add(rule("a".repeat(CategoryRuleValidator.MAX_TARGET_DIR_LENGTH + 1), false));
        rules.add(rule("未分类", true));
        assertThrows(IllegalArgumentException.class, () -> CategoryRuleValidator.validate(rules));

        List<RenameCategoryRulePlus> justFits = new ArrayList<>();
        justFits.add(rule("a".repeat(CategoryRuleValidator.MAX_TARGET_DIR_LENGTH), false));
        justFits.add(rule("未分类", true));
        assertDoesNotThrow(() -> CategoryRuleValidator.validate(justFits));
    }
}
