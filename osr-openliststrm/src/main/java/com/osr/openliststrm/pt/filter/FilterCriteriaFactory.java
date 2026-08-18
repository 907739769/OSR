package com.osr.openliststrm.pt.filter;

import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 把全局过滤配置与订阅级覆盖合并成一份生效的 {@link FilterCriteria}。
 * <p>
 * 覆盖以 JSON 存在 pt_subscription.filter_override，键名与 {@link PtFilterConfigPlus}
 * 的字段名一致，值的形态也与数据库一致（逗号分隔串、"0"/"1"、数字）。
 * <b>只有出现在 JSON 里的键才覆盖</b>，没出现的沿用全局值。
 * </p>
 *
 * @author Jack
 */
@Slf4j
public final class FilterCriteriaFactory {

    /**
     * 全局配置读不到做种数下限时的兜底值。必须与
     * {@code PtFilterConfigPlusServiceImpl#getConfig()} 的整行缺失兜底一致（两处都是 1）。
     * <p>
     * <b>绝不能兜底成 0</b>：0 对这个字段的语义恰好是「关掉做种数过滤」，把"读不到配置"
     * 兜底成它，等于让一个读不出来的配置静默放行所有<b>无人做种</b>的种子——而这类种子推给
     * 下载器后一动不动，占着并发名额直到 24 小时僵尸超时，用户从现象上只看到「下载不动」，
     * 完全想不到是配置没读到。同构的坑在 {@code PushSelectedRequest#downloadVolumeFactor}
     * 上踩过一次：那里原始类型的缺省值 {@code 0.0} 恰好是"免费种"，让所有不带该字段的请求
     * 整批绕过了 freeOnly 过滤。<b>兜底值必须是安全的那一侧，而不是类型的零值。</b>
     * </p>
     * <p>
     * 只对「配置缺失」生效。用户在界面或订阅覆盖里<b>显式</b>填 0 仍然是 0——那是"不限"的
     * 正当用法（部分索引器不返回 seeders 属性，这些站点的候选全解析成 0 做种）。
     * </p>
     */
    static final int DEFAULT_MIN_SEEDERS = 1;

    private FilterCriteriaFactory() {
    }

    /**
     * @param global   全局配置，允许整体为 null 或字段为 null（均使用安全默认值）
     * @param override 订阅级覆盖 JSON，允许为 null / 空白 / 格式损坏
     */
    public static FilterCriteria build(PtFilterConfigPlus global, String override) {
        if (global == null) {
            log.warn("全局过滤配置为 null，已使用安全默认值兜底");
            global = new PtFilterConfigPlus();
        }
        JSONObject patch = parseOverride(override);

        return FilterCriteria.builder()
                .minSeeders(intOf(patch, "minSeeders", minSeedersFallback(global)))
                .minSize(longOf(patch, "minSize", global.getMinSize()))
                .maxSize(longOf(patch, "maxSize", global.getMaxSize()))
                .freeOnly(isTruthy(strOf(patch, "freeOnly", global.getFreeOnly())))
                .includeKeywords(csvOf(patch, "includeKeywords", global.getIncludeKeywords()))
                .excludeKeywords(csvOf(patch, "excludeKeywords", global.getExcludeKeywords()))
                .descriptionExcludeKeywords(csvOf(patch, "descriptionExcludeKeywords",
                        global.getDescriptionExcludeKeywords()))
                .resolutionPriority(csvOf(patch, "resolutionPriority", global.getResolutionPriority()))
                .resolutionWhitelist(csvOf(patch, "resolutionWhitelist", global.getResolutionWhitelist()))
                .sourceWhitelist(csvOf(patch, "sourceWhitelist", global.getSourceWhitelist()))
                .sourcePriority(csvOf(patch, "sourcePriority", global.getSourcePriority()))
                .requiredTags(csvOf(patch, "requiredTags", global.getRequiredTags()))
                .excludeTags(csvOf(patch, "excludeTags", global.getExcludeTags()))
                .releaseGroupPriority(csvOf(patch, "releaseGroupPriority", global.getReleaseGroupPriority()))
                .sortPriority(SortDimension.parseCsv(strOf(patch, "sortPriority", global.getSortPriority())))
                .preferredSize(longOf(patch, "preferredSize", global.getPreferredSize()))
                .sizePerEpisode(isTruthy(strOf(patch, "sizePerEpisode", global.getSizePerEpisode())))
                .requireChineseSubtitle(
                        isTruthy(strOf(patch, "requireChineseSubtitle", global.getRequireChineseSubtitle())))
                .avoidHitAndRun(isTruthy(strOf(patch, "avoidHitAndRun", global.getAvoidHitAndRun())))
                .build();
    }

    /** 取值 + 切分的组合，逗号分隔的列表型字段全部走这里，省掉一层嵌套读起来更整齐 */
    private static List<String> csvOf(JSONObject patch, String key, String fallback) {
        return FilterCriteria.splitCsv(strOf(patch, key, fallback));
    }

    /**
     * 开关类字段的真值判定。数据库里存的是 "0"/"1"，但订阅级覆盖是用户手填的 JSON，
     * 前端表单很可能提交原生布尔值 true/false —— 那种情况下必须也认作「是」，
     * 否则「只要免费种」「外语电影需中字」这类开关会被静默关掉，结果下到不想要的种。
     */
    private static boolean isTruthy(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }

    /**
     * 解析覆盖 JSON。为 null/空白、格式非法、或不是 JSON 对象时一律返回空 patch，
     * 使全部字段退回全局配置——这份配置是用户手填的，一条坏数据不该让整轮轮询挂掉。
     */
    private static JSONObject parseOverride(String override) {
        if (StringUtils.isBlank(override)) {
            return new JSONObject();
        }
        try {
            JSONObject parsed = JSONObject.parseObject(override);
            return parsed == null ? new JSONObject() : parsed;
        } catch (Exception e) {
            log.warn("订阅级过滤覆盖不是合法的 JSON 对象，已整体退回全局配置：{}", e.getMessage());
            return new JSONObject();
        }
    }

    private static String strOf(JSONObject patch, String key, String fallback) {
        // 注意用 containsKey 而非判空：显式传 "" 意味着「这部剧不设该项」，是有效覆盖
        if (!patch.containsKey(key)) {
            return fallback;
        }
        try {
            // 实测 fastjson2 的 getString 对数组/对象/布尔/数字值都不会抛异常，
            // 而是转成其 JSON 字面量的字符串形式，因此这里不属于「取值异常」的高危路径；
            // 仍包一层 try/catch 是防御性收尾，与 intOf/longOf 保持同样的容错承诺。
            return patch.getString(key);
        } catch (Exception e) {
            log.warn("订阅级过滤覆盖字段 {} 的值 {} 无法解析为字符串，已回退全局配置：{}",
                    key, patch.get(key), e.getMessage());
            return fallback;
        }
    }

    /**
     * 全局配置里的做种数下限，读不到时退回 {@link #DEFAULT_MIN_SEEDERS} 并 warn——
     * 这条日志是"配置没读到"与"用户自己配成了 0"的唯一区分点，两者的现象完全一样。
     */
    private static int minSeedersFallback(PtFilterConfigPlus global) {
        Integer configured = global.getMinSeeders();
        if (configured != null) {
            return configured;
        }
        log.warn("全局过滤配置未给出做种数下限（pt_filter_config.min_seeders 为空），"
                + "已按默认值 {} 兜底；若按 0 处理会关掉这条过滤，无人做种的种子将被放行",
                DEFAULT_MIN_SEEDERS);
        return DEFAULT_MIN_SEEDERS;
    }

    private static int intOf(JSONObject patch, String key, Integer fallback) {
        int fallbackValue = fallback == null ? 0 : fallback;
        if (!patch.containsKey(key)) {
            return fallbackValue;
        }
        try {
            Integer value = patch.getInteger(key);
            return value == null ? fallbackValue : value;
        } catch (Exception e) {
            // 典型场景：体积/做种数字段被用户填成 "abc"、[] 等不是整数的值。
            // 取值阶段的异常同样要回退全局值，不能让它从 build() 逃出去。
            log.warn("订阅级过滤覆盖字段 {} 的值 {} 不是合法整数，已回退全局配置：{}",
                    key, patch.get(key), e.getMessage());
            return fallbackValue;
        }
    }

    private static long longOf(JSONObject patch, String key, Long fallback) {
        long fallbackValue = fallback == null ? 0L : fallback;
        if (!patch.containsKey(key)) {
            return fallbackValue;
        }
        try {
            Long value = patch.getLong(key);
            return value == null ? fallbackValue : value;
        } catch (Exception e) {
            // 典型场景：体积字段被用户填成 "5GB" 这种带单位的字符串
            log.warn("订阅级过滤覆盖字段 {} 的值 {} 不是合法数字，已回退全局配置：{}",
                    key, patch.get(key), e.getMessage());
            return fallbackValue;
        }
    }
}
