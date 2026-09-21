package com.osr.openliststrm.rename.rule;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;

import java.util.List;

/**
 * 分类规则集合的纯校验逻辑：不依赖Spring/DB，方便单测。
 * 校验规则：每条 targetDir 非空、合法、不超长；恰好一条 isFallback=1；该条必须排在列表最后一位。
 */
public final class CategoryRuleValidator {

    /** 与 rename_category_rule.target_dir 的列宽一致，超了 MySQL 会截断或报错 */
    public static final int MAX_TARGET_DIR_LENGTH = 128;

    /** Windows/Linux 下的路径非法字符，外加路径分隔符本身 */
    private static final String ILLEGAL_CHARS = "\\/:*?\"<>|";

    private CategoryRuleValidator() {
    }

    public static void validate(List<RenameCategoryRulePlus> rules) {
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("规则列表不能为空");
        }
        for (int i = 0; i < rules.size(); i++) {
            validateTargetDir(rules.get(i).getTargetDir(), i + 1);
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

    /**
     * 目标目录名必须是**一层**目录名，不是一段路径。
     * <p>
     * 它最终落在 {@code MediaRenameProcessor#buildDestPath} 的
     * {@code targetRoot.resolve(topLevel).resolve(category)} 上：带 {@code /} 会静默多建一层目录，
     * 而 {@code ..} 直接把整个媒体库写到 targetRoot 外面去。前端也拦了一道，但那层挡的是误操作，
     * 真正的边界在这里——接口是可以直接调的。
     */
    private static void validateTargetDir(String targetDir, int seq) {
        String prefix = "第" + seq + "条规则的目标目录名";
        if (StringUtils.isBlank(targetDir)) {
            throw new IllegalArgumentException(prefix + "不能为空");
        }
        String value = targetDir.trim();
        if (value.length() > MAX_TARGET_DIR_LENGTH) {
            throw new IllegalArgumentException(prefix + "最多 " + MAX_TARGET_DIR_LENGTH + " 个字符");
        }
        for (int i = 0; i < value.length(); i++) {
            if (ILLEGAL_CHARS.indexOf(value.charAt(i)) >= 0) {
                throw new IllegalArgumentException(prefix + "不能包含 \\ / : * ? \" < > | 这些字符（它是一层目录名，不是一段路径）");
            }
        }
        if (".".equals(value) || "..".equals(value)) {
            throw new IllegalArgumentException(prefix + "不能是 . 或 ..（会把文件写到媒体库之外）");
        }
    }
}
