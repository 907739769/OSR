package com.osr.openliststrm.pt.upgrade;

import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import com.osr.openliststrm.pt.filter.FilterCriteria;
import lombok.extern.slf4j.Slf4j;

/**
 * 把洗版配置与过滤配置合并成一份生效的 {@link UpgradeCriteria}。
 * <p>
 * 各维度内部"谁比谁好"的优先级列表取自 {@link PtFilterConfigPlus}，洗版配置只贡献
 * "哪些维度参与、按什么顺序"与目标质量。与 {@code FilterCriteriaFactory} 同一角色。
 * </p>
 *
 * @author Jack
 */
@Slf4j
public final class UpgradeCriteriaFactory {

    static final int DEFAULT_SEARCHES_PER_ROUND = 20;

    private UpgradeCriteriaFactory() {
    }

    /**
     * @param upgrade 洗版配置，允许整体为 null 或字段为 null（按未启用处理）
     * @param filter  过滤配置，允许整体为 null（优先级列表退化为空，各维度并列失效）
     */
    public static UpgradeCriteria build(PtUpgradeConfigPlus upgrade, PtFilterConfigPlus filter) {
        if (upgrade == null) {
            // 拿不到配置时绝不能擅自开始洗版：那会对全部已入库的集发起搜索
            log.warn("洗版配置为 null，按未启用处理");
            upgrade = new PtUpgradeConfigPlus();
            upgrade.setEnabled("0");
        }
        if (filter == null) {
            log.warn("过滤配置为 null，洗版的各维度优先级列表将为空");
            filter = new PtFilterConfigPlus();
        }
        return UpgradeCriteria.builder()
                .enabled("1".equals(upgrade.getEnabled()))
                .qualityPriority(UpgradeDimension.parseCsv(upgrade.getQualityPriority()))
                .resolutionPriority(FilterCriteria.splitCsv(filter.getResolutionPriority()))
                .sourcePriority(FilterCriteria.splitCsv(filter.getSourcePriority()))
                .releaseGroupPriority(FilterCriteria.splitCsv(filter.getReleaseGroupPriority()))
                .targetResolution(upgrade.getTargetResolution())
                .targetSources(FilterCriteria.splitCsv(upgrade.getTargetSources()))
                .targetTags(FilterCriteria.splitCsv(upgrade.getTargetTags()))
                .maxConcurrent(upgrade.getMaxConcurrent() == null ? 0 : upgrade.getMaxConcurrent())
                // 旧数据没有这一列时取默认 20，而不是 0：0 会让洗版静默停摆
                .maxSearchesPerRound(upgrade.getMaxSearchesPerRound() == null || upgrade.getMaxSearchesPerRound() <= 0
                        ? DEFAULT_SEARCHES_PER_ROUND : upgrade.getMaxSearchesPerRound())
                .scanIntervalHours(upgrade.getScanIntervalHours() == null ? 6 : upgrade.getScanIntervalHours())
                .build();
    }
}
