package com.osr.openliststrm.pt.upgrade;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import com.osr.openliststrm.pt.filter.FilterCriteria;
import com.osr.openliststrm.pt.filter.MediaSource;
import com.osr.openliststrm.pt.filter.PriorityRanker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 洗版规则的保存前校验与一致性诊断。纯函数，不读库。
 * <p>
 * 分两类：
 * </p>
 * <ul>
 *   <li>{@link #errors}：取值本身非法（名额为 0、周期越界……），任何时候都不许保存；</li>
 *   <li>{@link #problems}：「达标条件」与「比好坏的顺序」对不上。前者在洗版页配，后者的
 *       优先级列表在过滤规则页配，两页各改各的，很容易配出一份<b>永远不会生效</b>的洗版规则，
 *       而且不会有任何报错。洗版开着时这类问题同样拒绝保存；关着时只作为提示返回。</li>
 * </ul>
 *
 * @author Jack
 */
public final class UpgradeConfigCheck {

    public static final int MAX_CONCURRENT_LIMIT = 20;
    public static final int SCAN_INTERVAL_LIMIT = 168;
    public static final int SEARCHES_PER_ROUND_LIMIT = 500;

    private UpgradeConfigCheck() {
    }

    /** 取值合法性。@return 错误列表，空表示合法 */
    public static List<String> errors(PtUpgradeConfigPlus c) {
        List<String> errors = new ArrayList<>();
        if (!inRange(c.getMaxConcurrent(), 1, MAX_CONCURRENT_LIMIT)) {
            errors.add("同时在途洗版数须在 1~" + MAX_CONCURRENT_LIMIT + " 之间");
        }
        if (!inRange(c.getMaxSearchesPerRound(), 1, SEARCHES_PER_ROUND_LIMIT)) {
            errors.add("每轮最多搜索次数须在 1~" + SEARCHES_PER_ROUND_LIMIT + " 之间");
        }
        if (!inRange(c.getScanIntervalHours(), 1, SCAN_INTERVAL_LIMIT)) {
            errors.add("扫描周期须在 1~" + SCAN_INTERVAL_LIMIT + " 小时之间");
        }
        List<String> dimensions = FilterCriteria.splitCsv(c.getQualityPriority());
        for (String name : dimensions) {
            try {
                UpgradeDimension.valueOf(name.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add("无法识别的洗版维度：" + name);
            }
        }
        if (dimensions.isEmpty()) {
            errors.add("维度优先顺序不能为空，否则任何候选都不会被判成更好");
        }
        if ("1".equals(c.getEnabled()) && !hasTarget(c)) {
            errors.add("开启洗版前必须至少配置一项目标质量，否则不会有任何集被判定为需要升级");
        }
        return errors;
    }

    /**
     * 目标质量与过滤规则页的优先级列表是否对得上。
     * <p>
     * 典型的两种失效：目标来源 {@code REMUX,BluRay} 而来源优先级为空——所有候选来源并列，
     * 未达标的集永远找不到「来源更好」的版本，每个周期空搜一次；目标分辨率 {@code 4K} 不在
     * 分辨率优先级里——它的名次等于列表长度，任何已入库版本都不比它差，全部直接判成达标。
     * </p>
     *
     * @return 问题描述列表，空表示一致
     */
    public static List<String> problems(PtUpgradeConfigPlus upgrade, PtFilterConfigPlus filter) {
        List<String> problems = new ArrayList<>();
        List<UpgradeDimension> dimensions = UpgradeDimension.parseCsv(upgrade.getQualityPriority());

        String targetResolution = StringUtils.trimToNull(upgrade.getTargetResolution());
        if (targetResolution != null) {
            List<String> priority = FilterCriteria.splitCsv(filter.getResolutionPriority());
            if (!dimensions.contains(UpgradeDimension.RESOLUTION)) {
                problems.add("配了目标分辨率，但维度优先顺序里没有「分辨率」，不会有候选因分辨率更高而被选中");
            }
            if (priority.isEmpty()) {
                problems.add("过滤规则页的「分辨率优先级」为空：分辨率之间分不出高低，目标分辨率不起作用");
            } else if (PriorityRanker.rankOf(targetResolution, priority) == priority.size()) {
                problems.add("目标分辨率 " + targetResolution + " 不在过滤规则页的「分辨率优先级」" + priority
                        + " 里：任何已入库版本都会被判成已达标，洗版不会发生");
            }
        }

        List<String> targetSources = FilterCriteria.splitCsv(upgrade.getTargetSources());
        if (!targetSources.isEmpty()) {
            List<String> priority = FilterCriteria.splitCsv(filter.getSourcePriority());
            if (!dimensions.contains(UpgradeDimension.SOURCE)) {
                problems.add("配了目标媒介来源，但维度优先顺序里没有「媒介来源」，未达标的集找不到来源更好的候选，会一直空搜");
            }
            if (priority.isEmpty()) {
                problems.add("过滤规则页的「媒介来源优先级」为空：来源之间分不出高低，未达标的集找不到来源更好的候选，会一直空搜");
            } else {
                for (String source : targetSources) {
                    if (!MediaSource.in(priority, source)) {
                        problems.add("目标来源 " + source + " 不在过滤规则页的「媒介来源优先级」" + priority
                                + " 里：这个来源的候选不会被判成更好");
                    }
                }
            }
        }

        if (!FilterCriteria.splitCsv(upgrade.getTargetTags()).isEmpty()
                && !dimensions.contains(UpgradeDimension.TAG)) {
            problems.add("配了目标质量标签，但维度优先顺序里没有「质量标签」，缺标签的集找不到更好的候选，会一直空搜");
        }
        return problems;
    }

    public static boolean hasTarget(PtUpgradeConfigPlus c) {
        return StringUtils.isNotBlank(c.getTargetResolution())
                || !FilterCriteria.splitCsv(c.getTargetSources()).isEmpty()
                || !FilterCriteria.splitCsv(c.getTargetTags()).isEmpty();
    }

    private static boolean inRange(Integer value, int min, int max) {
        return value != null && value >= min && value <= max;
    }
}
