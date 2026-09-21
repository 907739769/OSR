package com.osr.openliststrm.pt.upgrade;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtUpgradeConfigPlusService;
import com.osr.openliststrm.pt.filter.FilterCriteria;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 洗版规则页背后的服务：保存（含校验与评估重置）、一致性诊断、状态概览、手动扫描。
 *
 * @author Jack
 */
@Slf4j
@Service
public class UpgradeConfigAdminService {

    private static final String STATE_IN_LIBRARY = SubscriptionEpisodeState.IN_LIBRARY.value();
    private static final String STATE_UPGRADING = SubscriptionEpisodeState.UPGRADING.value();

    private final IPtUpgradeConfigPlusService upgradeConfigService;
    private final IPtFilterConfigPlusService filterConfigService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final UpgradeScanTask scanTask;

    public UpgradeConfigAdminService(IPtUpgradeConfigPlusService upgradeConfigService,
                                     IPtFilterConfigPlusService filterConfigService,
                                     IPtSubscriptionEpisodePlusService episodeService,
                                     UpgradeScanTask scanTask) {
        this.upgradeConfigService = upgradeConfigService;
        this.filterConfigService = filterConfigService;
        this.episodeService = episodeService;
        this.scanTask = scanTask;
    }

    /**
     * 保存洗版规则。取值非法一律拒绝；洗版开着时，与过滤规则页优先级对不上的配置同样拒绝
     * （见 {@link UpgradeConfigCheck#problems}）。判定条件变了就重置已有的评估结论。
     *
     * @return 拒绝保存的原因，空表示已保存
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> save(PtUpgradeConfigPlus config) {
        config.setId(PtUpgradeConfigPlus.SINGLETON_ID);
        List<String> errors = new ArrayList<>(UpgradeConfigCheck.errors(config));
        if ("1".equals(config.getEnabled())) {
            errors.addAll(UpgradeConfigCheck.problems(config, filterConfigService.getConfig()));
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        PtUpgradeConfigPlus old = upgradeConfigService.getConfig();
        if (!upgradeConfigService.saveOrUpdate(config)) {
            return List.of("保存失败");
        }
        if (criteriaChanged(old, config)) {
            int reset = resetEvaluations();
            log.info("洗版判定条件已修改，重置 {} 集的洗版评估结论", reset);
        }
        return List.of();
    }

    /** 对一份（可能尚未保存的）洗版规则做一致性诊断，对照的是已保存的过滤规则 */
    public List<String> check(PtUpgradeConfigPlus draft) {
        return UpgradeConfigCheck.problems(draft, filterConfigService.getConfig());
    }

    /**
     * 让已入库的集按新条件重新评估：已达标（REACHED）的退回待评估，退避计数与上次搜索时间一并清零。
     * <p>
     * REACHED 是终态，扫描再也不会看它——把目标从 1080p 提到 2160p 之后，此前已达标的集
     * 一集都不会参与洗版，而用户只会以为改了配置没生效。NO_BASELINE 不动：那是「不知道库里
     * 是什么」，与判定条件无关。
     * </p>
     *
     * @return 受影响的集数
     */
    public int resetEvaluations() {
        PtSubscriptionEpisodePlus probe = new PtSubscriptionEpisodePlus();
        long affected = episodeService.count(new QueryWrapper<PtSubscriptionEpisodePlus>()
                .eq("state", STATE_IN_LIBRARY)
                .in("upgrade_state", UpgradeState.REACHED.value(), UpgradeState.PENDING.value()));
        episodeService.update(probe, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                .set("upgrade_state", UpgradeState.PENDING.value())
                .set("upgrade_searched_at", null)
                .set("upgrade_miss_count", 0)
                .eq("state", STATE_IN_LIBRARY)
                .in("upgrade_state", UpgradeState.REACHED.value(), UpgradeState.PENDING.value()));
        return (int) affected;
    }

    /**
     * 手动触发一轮扫描。
     *
     * @return null 表示已开始；否则是不能开始的原因
     */
    public String triggerScan() {
        UpgradeCriteria criteria = UpgradeCriteriaFactory.build(
                upgradeConfigService.getConfig(), filterConfigService.getConfig());
        if (!criteria.active()) {
            return "洗版未启用或未配置目标质量，保存后再扫描";
        }
        return scanTask.triggerNow() ? null : "已有一轮扫描正在进行，请稍后刷新查看结果";
    }

    /** 洗版规则页顶部的状态概览 */
    public Overview overview() {
        PtUpgradeConfigPlus upgrade = upgradeConfigService.getConfig();
        PtFilterConfigPlus filter = filterConfigService.getConfig();
        int interval = upgrade.getScanIntervalHours() == null || upgrade.getScanIntervalHours() <= 0
                ? 6 : upgrade.getScanIntervalHours();

        long unevaluated = 0;
        long reached = 0;
        long noBaseline = 0;
        List<Map<String, Object>> rows = episodeService.listMaps(new QueryWrapper<PtSubscriptionEpisodePlus>()
                .select("upgrade_state AS upgradeState", "COUNT(*) AS cnt")
                .eq("state", STATE_IN_LIBRARY)
                .inSql("sub_id", UpgradeScanService.UPGRADABLE_SUB_IDS_SQL)
                .groupBy("upgrade_state"));
        for (Map<String, Object> row : rows) {
            long count = ((Number) row.get("cnt")).longValue();
            Object state = row.get("upgradeState");
            if (state == null) {
                unevaluated += count;
            } else if (UpgradeState.REACHED.value().equals(state)) {
                reached += count;
            } else if (UpgradeState.NO_BASELINE.value().equals(state)) {
                noBaseline += count;
            }
        }

        // 待洗版的集再按退避拆成「到期」与「退避中」，只取判定要用的两列
        long pendingDue = 0;
        long pendingBackoff = 0;
        long now = System.currentTimeMillis();
        List<PtSubscriptionEpisodePlus> pending = episodeService.list(new QueryWrapper<PtSubscriptionEpisodePlus>()
                .select("upgrade_searched_at", "upgrade_miss_count")
                .eq("state", STATE_IN_LIBRARY)
                .eq("upgrade_state", UpgradeState.PENDING.value())
                .inSql("sub_id", UpgradeScanService.UPGRADABLE_SUB_IDS_SQL));
        for (PtSubscriptionEpisodePlus episode : pending) {
            if (UpgradeBackoff.isDue(episode.getUpgradeSearchedAt(), episode.getUpgradeMissCount(), interval, now)) {
                pendingDue++;
            } else {
                pendingBackoff++;
            }
        }
        long upgrading = episodeService.count(new QueryWrapper<PtSubscriptionEpisodePlus>()
                .eq("state", STATE_UPGRADING));

        UpgradeCriteria criteria = UpgradeCriteriaFactory.build(upgrade, filter);
        return new Overview(
                criteria.active(),
                new Counts(unevaluated, pendingDue, pendingBackoff, reached, noBaseline, upgrading),
                scanTask.isRunning(),
                scanTask.lastScan(),
                scanTask.nextScanAt(),
                new FilterPriorities(filter.getResolutionPriority(), filter.getSourcePriority(),
                        filter.getReleaseGroupPriority()),
                UpgradeConfigCheck.problems(upgrade, filter));
    }

    /**
     * @param unevaluated    已入库、还没评估过（刚入库或老数据）
     * @param pendingDue     未达标、本轮会搜
     * @param pendingBackoff 未达标、连续落空处于退避期
     * @param reached        已达目标质量
     * @param noBaseline     没有质量基线，不参与洗版
     * @param upgrading      洗版下载在途
     */
    public record Counts(long unevaluated, long pendingDue, long pendingBackoff, long reached, long noBaseline,
                         long upgrading) {
    }

    /** 洗版比较所沿用的、过滤规则页上的三个优先级列表 */
    public record FilterPriorities(String resolutionPriority, String sourcePriority, String releaseGroupPriority) {
    }

    public record Overview(boolean active, Counts counts, boolean running, UpgradeScanTask.LastScan lastScan,
                           Date nextScanAt, FilterPriorities filterPriorities, List<String> problems) {
    }

    /** 影响「达标没有 / 谁更好」的字段有没有变 */
    static boolean criteriaChanged(PtUpgradeConfigPlus old, PtUpgradeConfigPlus now) {
        return !Objects.equals(UpgradeDimension.parseCsv(old.getQualityPriority()),
                UpgradeDimension.parseCsv(now.getQualityPriority()))
                || !Objects.equals(norm(old.getTargetResolution()), norm(now.getTargetResolution()))
                || !Objects.equals(normList(old.getTargetSources()), normList(now.getTargetSources()))
                || !Objects.equals(normList(old.getTargetTags()), normList(now.getTargetTags()));
    }

    /** 过滤规则页上被洗版沿用的三个优先级列表有没有变 */
    public static boolean prioritiesChanged(PtFilterConfigPlus old, PtFilterConfigPlus now) {
        return !Objects.equals(normList(old.getResolutionPriority()), normList(now.getResolutionPriority()))
                || !Objects.equals(normList(old.getSourcePriority()), normList(now.getSourcePriority()))
                || !Objects.equals(normList(old.getReleaseGroupPriority()), normList(now.getReleaseGroupPriority()));
    }

    private static String norm(String value) {
        String trimmed = StringUtils.trimToNull(value);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    private static List<String> normList(String csv) {
        return FilterCriteria.splitCsv(csv).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }
}
