package com.osr.openliststrm.rename.rule;

import com.osr.openliststrm.rename.CategoryRule;
import com.osr.openliststrm.rename.model.MediaInfo;

import java.util.List;

/**
 * 从 MediaRenameProcessor 抽出的纯匹配逻辑：按顺序遍历规则列表，第一条 matches 命中的即为结果。
 * 不依赖Spring/DB，方便直接用规则数据回归验证。
 */
public final class CategoryClassifier {

    private CategoryClassifier() {
    }

    public static String classify(List<CategoryRule> rules, MediaInfo info) {
        if (rules == null) {
            return null;
        }
        for (CategoryRule rule : rules) {
            if (rule.matches(info)) {
                return rule.getName();
            }
        }
        return null;
    }
}
