package com.osr.openliststrm.pt.upgrade;

import lombok.Builder;

import java.util.List;

/**
 * 生效的洗版条件——{@code pt_upgrade_config} 与 {@code pt_filter_config} 合并后的最终结果。
 * <p>
 * 不可变。{@link UpgradeEvaluator} 只认本类型、不读数据库，因此判定逻辑是纯函数、可密集单测。
 * 与 {@code FilterCriteria} 同一角色。
 * </p>
 * <p>
 * 三个 xxxPriority 来自 {@code pt_filter_config} 而不是洗版配置自己：
 * 「2160p 比 1080p 好」这件事只该有一处定义，两份配置迟早会说法不一致。
 * </p>
 *
 * @param enabled            洗版总开关
 * @param qualityPriority    比较的维度顺序；空列表表示没有任何维度参与比较，等价于关闭洗版
 * @param resolutionPriority 分辨率优劣顺序，来自过滤配置
 * @param sourcePriority     媒介来源优劣顺序，来自过滤配置
 * @param releaseGroupPriority 发布组优劣顺序，来自过滤配置
 * @param targetResolution   目标分辨率(cutoff)，空表示该项不约束
 * @param targetSources      目标媒介来源(cutoff)，命中其一即满足；空表示不约束
 * @param targetTags         目标质量标签(cutoff)，须全部具备；空表示不约束
 * @param maxConcurrent      洗版同时在途的下载数上限
 * @param scanIntervalHours  扫描周期(小时)
 * @author Jack
 */
@Builder
public record UpgradeCriteria(
        boolean enabled,
        List<UpgradeDimension> qualityPriority,
        List<String> resolutionPriority,
        List<String> sourcePriority,
        List<String> releaseGroupPriority,
        String targetResolution,
        List<String> targetSources,
        List<String> targetTags,
        int maxConcurrent,
        int scanIntervalHours) {

    public UpgradeCriteria {
        qualityPriority = nullSafeCopy(qualityPriority);
        resolutionPriority = nullSafeCopy(resolutionPriority);
        sourcePriority = nullSafeCopy(sourcePriority);
        releaseGroupPriority = nullSafeCopy(releaseGroupPriority);
        targetSources = nullSafeCopy(targetSources);
        targetTags = nullSafeCopy(targetTags);
    }

    private static <T> List<T> nullSafeCopy(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * 是否配了任何目标质量。
     * <p>
     * 三项都空意味着 cutoff 恒成立、每一集刚评估就是 REACHED，洗版实际不会发生——
     * 这是刻意的安全默认：用户没想清楚要什么质量时，系统不该自作主张开始搜。
     * </p>
     */
    public boolean hasTarget() {
        return (targetResolution != null && !targetResolution.isBlank())
                || !targetSources.isEmpty()
                || !targetTags.isEmpty();
    }

    /** 真正会发起洗版的条件：总开关开着、配了目标质量、且至少有一个维度参与比较 */
    public boolean active() {
        return enabled && hasTarget() && !qualityPriority.isEmpty();
    }
}
