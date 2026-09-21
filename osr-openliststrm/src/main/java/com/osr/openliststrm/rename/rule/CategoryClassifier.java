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
        int index = classifyIndex(rules, info);
        return index < 0 ? null : rules.get(index).getName();
    }

    /**
     * 同 {@link #classify}，但返回命中的是第几条（0 基，未命中返回 -1）。
     * 「重命名测试」要告诉用户命中的是哪一条规则——只有目录名的话，两条规则配了同一个目标目录时分不出来。
     */
    public static int classifyIndex(List<CategoryRule> rules, MediaInfo info) {
        if (rules == null) {
            return -1;
        }
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).matches(info)) {
                return i;
            }
        }
        return -1;
    }
}
