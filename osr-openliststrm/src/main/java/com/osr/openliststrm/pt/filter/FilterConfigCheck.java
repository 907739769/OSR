package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 全局过滤规则的保存前校验。纯函数，不读库。
 * <p>
 * 只挡「存进去就等于静默失效」的值：体积下限大于上限会让全部种子被淘汰，负数的阈值在
 * 引擎里的含义是未定义的，写错的排序维度会被 {@link SortDimension#parseCsv} 悄悄忽略。
 * 这些都不报错，只表现为「怎么一个种子都下不到」或「排序好像没生效」。
 * </p>
 *
 * @author Jack
 */
public final class FilterConfigCheck {

    private FilterConfigCheck() {
    }

    /** @return 错误列表，空表示可以保存 */
    public static List<String> errors(PtFilterConfigPlus c) {
        List<String> errors = new ArrayList<>();
        if (c.getMinSeeders() == null || c.getMinSeeders() < 0) {
            errors.add("最低做种数不能为空，且不能小于 0");
        }
        if (negative(c.getMinSize()) || negative(c.getMaxSize()) || negative(c.getPreferredSize())) {
            errors.add("体积阈值不能为负数（0 表示不限）");
        }
        long min = c.getMinSize() == null ? 0 : c.getMinSize();
        long max = c.getMaxSize() == null ? 0 : c.getMaxSize();
        if (min > 0 && max > 0 && min > max) {
            errors.add("体积下限大于体积上限，所有种子都会被淘汰");
        }
        for (String name : FilterCriteria.splitCsv(c.getSortPriority())) {
            try {
                SortDimension.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add("无法识别的排序维度：" + name);
            }
        }
        return errors;
    }

    private static boolean negative(Long value) {
        return value != null && value < 0;
    }
}
