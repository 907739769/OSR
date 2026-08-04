package com.osr.openliststrm.pt.upgrade;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 洗版判定的纯逻辑用例。这一层判错的代价是"把好版本换成差版本"或"无限来回洗"，
 * 因此每条规则都单独钉死。
 *
 * @author Jack
 */
class UpgradeEvaluatorTest {

    private final UpgradeEvaluator evaluator = new UpgradeEvaluator();

    private UpgradeCriteria criteria(List<UpgradeDimension> dims, String targetResolution,
                                     List<String> targetSources, List<String> targetTags) {
        return UpgradeCriteria.builder()
                .enabled(true)
                .qualityPriority(dims)
                .resolutionPriority(List.of("2160p", "1080p", "720p"))
                .sourcePriority(List.of("REMUX", "BluRay", "WEBDL", "HDTV"))
                .releaseGroupPriority(List.of("CHDBits", "FRDS"))
                .targetResolution(targetResolution)
                .targetSources(targetSources)
                .targetTags(targetTags)
                .maxConcurrent(2)
                .scanIntervalHours(6)
                .build();
    }

    private UpgradeCriteria defaultCriteria() {
        return criteria(List.of(UpgradeDimension.RESOLUTION, UpgradeDimension.SOURCE,
                UpgradeDimension.TAG, UpgradeDimension.RELEASE_GROUP),
                "2160p", List.of("REMUX", "BluRay"), List.of());
    }

    private QualityProfile profile(String resolution, String source, String group, String... tags) {
        return new QualityProfile(resolution, source, group, List.of(tags));
    }

    // ---------- 严格优于 ----------

    @Test
    void 分辨率更高_判为升级() {
        assertTrue(evaluator.isUpgrade(profile("2160p", "WEBDL", null),
                profile("1080p", "WEBDL", null), defaultCriteria()));
    }

    @Test
    void 分辨率更低_不判为升级() {
        assertFalse(evaluator.isUpgrade(profile("720p", "REMUX", null),
                profile("1080p", "WEBDL", null), defaultCriteria()));
    }

    @Test
    void 分辨率相同来源更好_判为升级() {
        assertTrue(evaluator.isUpgrade(profile("1080p", "REMUX", null),
                profile("1080p", "WEBDL", null), defaultCriteria()));
    }

    @Test
    void 完全相同_不判为升级() {
        // 「严格」优于：并列时不换，否则同质量版本会互相替换个没完
        assertFalse(evaluator.isUpgrade(profile("1080p", "WEBDL", "CHDBits"),
                profile("1080p", "WEBDL", "CHDBits"), defaultCriteria()));
    }

    @Test
    void 字典序_高优先维度压倒低优先维度() {
        // RESOLUTION 排在 SOURCE 前面：2160p HDTV 胜过 1080p REMUX
        assertTrue(evaluator.isUpgrade(profile("2160p", "HDTV", null),
                profile("1080p", "REMUX", null), defaultCriteria()));
        // 反向必须不成立，否则就有环
        assertFalse(evaluator.isUpgrade(profile("1080p", "REMUX", null),
                profile("2160p", "HDTV", null), defaultCriteria()));
    }

    @Test
    void 比较关系无环_任意两个画像至多一个方向成立() {
        // 这是"不会来回洗"的数学保证：维度取值都来自有限集合，字典序构成全预序
        List<QualityProfile> all = List.of(
                profile("2160p", "REMUX", "CHDBits"),
                profile("2160p", "WEBDL", "FRDS"),
                profile("1080p", "REMUX", null),
                profile("1080p", "HDTV", "CHDBits"),
                profile(null, null, null));
        UpgradeCriteria c = defaultCriteria();
        for (QualityProfile a : all) {
            for (QualityProfile b : all) {
                assertFalse(evaluator.isUpgrade(a, b, c) && evaluator.isUpgrade(b, a, c),
                        "存在互相判为升级的一对，会导致无限来回洗：" + a.describe() + " vs " + b.describe());
            }
        }
    }

    @Test
    void 无参与比较的维度_一律不判为升级() {
        UpgradeCriteria none = criteria(List.of(), "2160p", List.of(), List.of());
        assertFalse(evaluator.isUpgrade(profile("2160p", "REMUX", null),
                profile("720p", "HDTV", null), none));
    }

    @Test
    void 任一方为null_不判为升级() {
        // 无基线的集不该被盲目升级
        assertFalse(evaluator.isUpgrade(profile("2160p", "REMUX", null), null, defaultCriteria()));
        assertFalse(evaluator.isUpgrade(null, profile("720p", "HDTV", null), defaultCriteria()));
    }

    @Test
    void 解析不出的取值与不在优先级列表中的取值_判同级() {
        // 两者都表示"没有已知偏好"，区别对待会凭空造出优劣关系
        assertFalse(evaluator.isUpgrade(profile("480p", "WEBDL", null),
                profile(null, "WEBDL", null), defaultCriteria()));
        assertFalse(evaluator.isUpgrade(profile(null, "WEBDL", null),
                profile("480p", "WEBDL", null), defaultCriteria()));
    }

    // ---------- 目标标签维度 ----------

    @Test
    void 目标标签欠缺更少_判为升级() {
        UpgradeCriteria c = criteria(List.of(UpgradeDimension.TAG), null, List.of(), List.of("HDR10", "ATMOS"));
        assertTrue(evaluator.isUpgrade(profile("1080p", "WEBDL", null, "HDR10", "ATMOS"),
                profile("1080p", "WEBDL", null, "HDR10"), c));
    }

    @Test
    void 标签维度只看目标标签_塞满无关标签不算更优() {
        UpgradeCriteria c = criteria(List.of(UpgradeDimension.TAG), null, List.of(), List.of("HDR10"));
        assertFalse(evaluator.isUpgrade(profile("1080p", "WEBDL", null, "10BIT", "60FPS", "IMAX"),
                profile("1080p", "WEBDL", null, "HDR10"), c));
    }

    @Test
    void 未配置目标标签_标签维度失效() {
        UpgradeCriteria c = criteria(List.of(UpgradeDimension.TAG), null, List.of(), List.of());
        assertFalse(evaluator.isUpgrade(profile("1080p", "WEBDL", null, "HDR10"),
                profile("1080p", "WEBDL", null), c));
    }

    // ---------- cutoff ----------

    @Test
    void 达到目标分辨率与来源_判为达标() {
        assertTrue(evaluator.reachedTarget(profile("2160p", "REMUX", null), defaultCriteria()));
    }

    @Test
    void 分辨率超过目标_也判为达标() {
        // 用名次比较而不是字符串相等：目标 1080p 时 2160p 显然也够好了
        UpgradeCriteria c = criteria(List.of(UpgradeDimension.RESOLUTION), "1080p", List.of(), List.of());
        assertTrue(evaluator.reachedTarget(profile("2160p", "WEBDL", null), c));
    }

    @Test
    void 来源不在目标集合内_不达标() {
        assertFalse(evaluator.reachedTarget(profile("2160p", "WEBDL", null), defaultCriteria()));
    }

    @Test
    void 缺少目标标签_不达标() {
        UpgradeCriteria c = criteria(List.of(UpgradeDimension.TAG), null, List.of(), List.of("HDR10", "ATMOS"));
        assertFalse(evaluator.reachedTarget(profile("2160p", "REMUX", null, "HDR10"), c));
        assertTrue(evaluator.reachedTarget(profile("2160p", "REMUX", null, "HDR10", "ATMOS"), c));
    }

    @Test
    void 未配置任何目标_恒达标即等价于关闭洗版() {
        // 安全默认：用户没想清楚要什么质量时，系统不该自作主张开始搜
        UpgradeCriteria c = criteria(List.of(UpgradeDimension.RESOLUTION), null, List.of(), List.of());
        assertFalse(c.hasTarget());
        assertFalse(c.active());
        assertTrue(evaluator.reachedTarget(profile("480p", "HDTV", null), c));
    }

    @Test
    void 画像为null_不判为达标() {
        assertFalse(evaluator.reachedTarget(null, defaultCriteria()));
    }

    @Test
    void 解析不出分辨率_不判为达标() {
        assertFalse(evaluator.reachedTarget(profile(null, "REMUX", null), defaultCriteria()));
    }

    // ---------- active ----------

    @Test
    void 总开关关闭_不激活() {
        UpgradeCriteria c = UpgradeCriteria.builder()
                .enabled(false)
                .qualityPriority(List.of(UpgradeDimension.RESOLUTION))
                .targetResolution("2160p")
                .build();
        assertFalse(c.active());
    }

    @Test
    void 开关开着但没配目标_不激活() {
        assertFalse(criteria(List.of(UpgradeDimension.RESOLUTION), null, List.of(), List.of()).active());
    }

    @Test
    void 开关开着且配了目标与维度_激活() {
        assertTrue(defaultCriteria().active());
    }

    // ---------- pickBest ----------

    @Test
    void pickBest_挑出最优候选() {
        List<QualityProfile> candidates = List.of(
                profile("1080p", "REMUX", null),
                profile("2160p", "WEBDL", null),
                profile("2160p", "REMUX", null));

        assertEquals(profile("2160p", "REMUX", null),
                evaluator.pickBest(candidates, p -> p, defaultCriteria()));
    }

    @Test
    void pickBest_全部并列时返回第一个() {
        QualityProfile first = profile("1080p", "WEBDL", "CHDBits");
        List<QualityProfile> candidates = List.of(first, profile("1080p", "WEBDL", "CHDBits"));

        assertTrue(first == evaluator.pickBest(candidates, p -> p, defaultCriteria()));
    }

    @Test
    void pickBest_空列表返回null() {
        assertEquals(null, evaluator.pickBest(List.<QualityProfile>of(), p -> p, defaultCriteria()));
    }

    // ---------- 升级说明 ----------

    @Test
    void 升级说明_逐维度列出变化项() {
        String desc = evaluator.describeUpgrade(profile("2160p", "REMUX", null),
                profile("1080p", "WEBDL", null), defaultCriteria());

        assertTrue(desc.contains("1080p"));
        assertTrue(desc.contains("2160p"));
        assertTrue(desc.contains("WEBDL"));
        assertTrue(desc.contains("REMUX"));
    }

    @Test
    void 升级说明_无变化时给出明确说法() {
        assertEquals("质量相当", evaluator.describeUpgrade(profile("1080p", "WEBDL", null),
                profile("1080p", "WEBDL", null), defaultCriteria()));
    }
}
