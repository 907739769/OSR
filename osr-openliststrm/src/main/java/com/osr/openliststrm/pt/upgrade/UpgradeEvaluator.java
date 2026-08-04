package com.osr.openliststrm.pt.upgrade;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.pt.filter.PriorityRanker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 洗版判定引擎：回答两个问题——「这一集够好了吗」与「这个候选比现有的更好吗」。
 * 纯逻辑，不读数据库、不发网络请求，生效条件由调用方通过 {@link UpgradeCriteria} 传入。
 * 与 {@code TorrentFilterEngine} 同一角色。
 *
 * @author Jack
 */
@Slf4j
@Component
public class UpgradeEvaluator {

    /**
     * 这份画像是否已达到目标质量（cutoff），达到则该集不再参与洗版扫描。
     * <p>
     * 三项是<b>与</b>的关系：配了的项全部满足才算达标。没配的项不构成约束。
     * 全都没配时恒返回 true（等价于关闭洗版）——见 {@link UpgradeCriteria#hasTarget()}。
     * </p>
     */
    public boolean reachedTarget(QualityProfile profile, UpgradeCriteria criteria) {
        if (profile == null) {
            // 没有基线就谈不上"够好了"，但也不该被判成"不够好"而触发盲目升级。
            // 调用方应该在此之前就用 NO_BASELINE 把这类集挡掉，这里只是防御。
            return false;
        }
        if (StringUtils.isNotBlank(criteria.targetResolution())) {
            // 用名次比较而不是字符串相等：目标 1080p 时，2160p 也应算达标（比目标更好）
            int own = PriorityRanker.rankOf(profile.resolution(), criteria.resolutionPriority());
            int target = PriorityRanker.rankOf(criteria.targetResolution(), criteria.resolutionPriority());
            if (own > target) {
                return false;
            }
        }
        if (!criteria.targetSources().isEmpty() && !containsIgnoreCase(criteria.targetSources(), profile.source())) {
            return false;
        }
        for (String tag : criteria.targetTags()) {
            if (!profile.hasTag(tag)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 候选是否<b>严格优于</b>现有版本。
     * <p>
     * 按 {@link UpgradeCriteria#qualityPriority()} 的维度顺序做字典序比较：
     * 第一个名次不同的维度说了算，全部维度都并列则不算更优（不换）。
     * </p>
     * <p>
     * 「严格」二字是这个功能不会失控的关键：并列时返回 false，因此同质量的版本不会
     * 触发替换。配上 {@link UpgradeDimension} 那套有限取值的维度，比较关系构成全预序，
     * 不存在 A 优于 B 且 B 优于 A 的环，也就不会来回洗。
     * </p>
     */
    public boolean isUpgrade(QualityProfile candidate, QualityProfile current, UpgradeCriteria criteria) {
        if (candidate == null || current == null) {
            return false;
        }
        for (UpgradeDimension dimension : criteria.qualityPriority()) {
            int candidateRank = dimension.rank(candidate, criteria);
            int currentRank = dimension.rank(current, criteria);
            if (candidateRank != currentRank) {
                return candidateRank < currentRank;
            }
        }
        return false;
    }

    /**
     * 从候选里挑出最优的一个（必须先经 {@link #isUpgrade} 筛过）。
     * <p>
     * 用与 {@link #isUpgrade} 完全相同的维度顺序两两比较，保证"被选中的那个"
     * 一定也是"严格优于现有版本"的那批里最好的。全部并列时返回第一个。
     * </p>
     *
     * @return 最优候选；候选为空时返回 null
     */
    public <T> T pickBest(List<T> candidates, java.util.function.Function<T, QualityProfile> toProfile,
                          UpgradeCriteria criteria) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        T best = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            T candidate = candidates.get(i);
            if (isUpgrade(toProfile.apply(candidate), toProfile.apply(best), criteria)) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 说清这次升级好在哪，用于通知与日志。
     * 逐维度列出发生变化的项，如 {@code 分辨率 1080p → 2160p、来源 WEBDL → REMUX}。
     */
    public String describeUpgrade(QualityProfile candidate, QualityProfile current, UpgradeCriteria criteria) {
        List<String> changes = new ArrayList<>();
        for (UpgradeDimension dimension : criteria.qualityPriority()) {
            if (dimension.rank(candidate, criteria) == dimension.rank(current, criteria)) {
                continue;
            }
            changes.add(switch (dimension) {
                case RESOLUTION -> "分辨率 " + display(current.resolution()) + " → " + display(candidate.resolution());
                case SOURCE -> "来源 " + display(current.source()) + " → " + display(candidate.source());
                case RELEASE_GROUP -> "发布组 " + display(current.releaseGroup()) + " → " + display(candidate.releaseGroup());
                case TAG -> "标签 " + displayTags(current) + " → " + displayTags(candidate);
            });
        }
        return changes.isEmpty() ? "质量相当" : String.join("、", changes);
    }

    private String display(String value) {
        return StringUtils.isBlank(value) ? "未知" : value;
    }

    private String displayTags(QualityProfile profile) {
        return profile.tags().isEmpty() ? "无" : String.join("+", profile.tags());
    }

    private boolean containsIgnoreCase(List<String> candidates, String value) {
        if (StringUtils.isBlank(value)) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && candidate.equalsIgnoreCase(value.trim())) {
                return true;
            }
        }
        return false;
    }
}
