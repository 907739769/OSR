package com.osr.openliststrm.rename.rule;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;
import com.osr.openliststrm.rename.CategoryRule;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据库行 (RenameCategoryRulePlus) 与运行时匹配对象 (CategoryRule) 之间的纯转换。
 */
public final class CategoryRuleConverter {

    private CategoryRuleConverter() {
    }

    public static CategoryRule toCategoryRule(RenameCategoryRulePlus row) {
        CategoryRule rule = new CategoryRule(row.getTargetDir());
        if (StringUtils.isNotBlank(row.getGenreIds())) {
            rule.withGenreIds(row.getGenreIds().split(","));
        }
        if (StringUtils.isNotBlank(row.getOriginalLanguages())) {
            rule.withOriginalLanguage(row.getOriginalLanguages().split(","));
        }
        if (StringUtils.isNotBlank(row.getOriginCountries())) {
            rule.withOriginCountry(row.getOriginCountries().split(","));
        }
        return rule;
    }

    public static List<CategoryRule> toCategoryRules(List<RenameCategoryRulePlus> rows) {
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream().map(CategoryRuleConverter::toCategoryRule).collect(Collectors.toList());
    }
}
