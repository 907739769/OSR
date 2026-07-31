package com.osr.openliststrm.rename.rule;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;

import java.util.List;

/**
 * 分类规则集合的纯校验逻辑：不依赖Spring/DB，方便单测。
 * 校验规则：每条 targetDir 非空；恰好一条 isFallback=1；该条必须排在列表最后一位。
 */
public final class CategoryRuleValidator {

    private CategoryRuleValidator() {
    }

    public static void validate(List<RenameCategoryRulePlus> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("规则列表不能为空");
        }
        for (int i = 0; i < rules.size(); i++) {
            RenameCategoryRulePlus rule = rules.get(i);
            if (StringUtils.isBlank(rule.getTargetDir())) {
                throw new IllegalArgumentException("第" + (i + 1) + "条规则的目标目录名不能为空");
            }
        }
        long fallbackCount = rules.stream().filter(r -> "1".equals(r.getIsFallback())).count();
        if (fallbackCount != 1) {
            throw new IllegalArgumentException("必须保留且只能保留一条兜底规则");
        }
        RenameCategoryRulePlus last = rules.get(rules.size() - 1);
        if (!"1".equals(last.getIsFallback())) {
            throw new IllegalArgumentException("兜底规则必须排在最后一位");
        }
    }
}
