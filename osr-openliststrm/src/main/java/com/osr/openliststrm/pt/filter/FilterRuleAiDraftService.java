package com.osr.openliststrm.pt.filter;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.openai.AiChatService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自然语言 → 过滤规则<b>草稿</b>。
 * <p>
 * 只产出「要改哪几个字段、改成什么」，<b>不落库</b>：前端把它合进表单，用户看过（可以再跑一次历史回放）
 * 之后自己点保存。AI 给的东西一律当不可信输入逐字段校验——只认白名单里的字段，枚举类字段只保留
 * {@link FilterVocabulary} 里有的值（后端按全等比对，词表外的值存进去永远命不中，比不改还糟），
 * 体积让 AI 按 GB 给、这里换成字节。
 * </p>
 *
 * @author Jack
 */
@Service
public class FilterRuleAiDraftService {

    private static final long GB = 1024L * 1024 * 1024;

    /** 开关类字段，取值 "0"/"1" */
    private static final Set<String> FLAG_KEYS = Set.of("freeOnly", "sizePerEpisode", "requireChineseSubtitle", "avoidHitAndRun");

    /** 自由文本的逗号分隔串 */
    private static final Set<String> FREE_LIST_KEYS = Set.of("includeKeywords", "excludeKeywords",
            "descriptionExcludeKeywords", "releaseGroupPriority");

    /** 体积字段：AI 给 GB，存字节 */
    private static final Set<String> SIZE_KEYS = Set.of("minSize", "maxSize", "preferredSize");

    private final AiChatService ai;

    public FilterRuleAiDraftService(AiChatService ai) {
        this.ai = ai;
    }

    /**
     * @param changes     校验通过、可直接合进表单的字段（体积已是字节）
     * @param explanation AI 对改动的一句话说明
     * @param dropped     AI 给了但被丢弃的字段及原因，前端要展示出来，免得用户以为「那条要求已经生效」
     */
    public record Draft(Map<String, Object> changes, String explanation, List<String> dropped) {
    }

    public boolean available() {
        return ai.available();
    }

    /** AI 未配置或调用失败时返回 null */
    public Draft draft(String text, PtFilterConfigPlus current) {
        Object reply = ai.chatJson("自然语言建过滤规则", buildPrompt(text, current), 800);
        if (!(reply instanceof JSONObject json)) {
            return null;
        }
        return validate(json);
    }

    static String buildPrompt(String text, PtFilterConfigPlus current) {
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("minSeeders", current.getMinSeeders());
        now.put("minSize", toGb(current.getMinSize()));
        now.put("maxSize", toGb(current.getMaxSize()));
        now.put("preferredSize", toGb(current.getPreferredSize()));
        now.put("sizePerEpisode", current.getSizePerEpisode());
        now.put("freeOnly", current.getFreeOnly());
        now.put("includeKeywords", current.getIncludeKeywords());
        now.put("excludeKeywords", current.getExcludeKeywords());
        now.put("descriptionExcludeKeywords", current.getDescriptionExcludeKeywords());
        now.put("resolutionWhitelist", current.getResolutionWhitelist());
        now.put("resolutionPriority", current.getResolutionPriority());
        now.put("sourceWhitelist", current.getSourceWhitelist());
        now.put("sourcePriority", current.getSourcePriority());
        now.put("requiredTags", current.getRequiredTags());
        now.put("excludeTags", current.getExcludeTags());
        now.put("releaseGroupPriority", current.getReleaseGroupPriority());
        now.put("requireChineseSubtitle", current.getRequireChineseSubtitle());
        now.put("avoidHitAndRun", current.getAvoidHitAndRun());

        return "你在帮用户修改 PT 种子的全局过滤规则。字段含义：\n"
                + "minSeeders 最低做种数；minSize/maxSize 体积下限/上限（单位 GB，0 为不限）；preferredSize 偏好体积（GB，0 为不参与排序）；"
                + "sizePerEpisode 体积是否按每集折算 \"0\"/\"1\"；freeOnly 只下免费种 \"0\"/\"1\"；"
                + "includeKeywords 标题须命中其一的关键词；excludeKeywords 标题命中即淘汰；descriptionExcludeKeywords 种子描述命中即淘汰；"
                + "resolutionWhitelist 分辨率白名单（硬过滤）；resolutionPriority 分辨率优先级（只排序）；"
                + "sourceWhitelist 片源白名单；sourcePriority 片源优先级；requiredTags 必须全部具备的标签；excludeTags 命中即淘汰的标签；"
                + "releaseGroupPriority 发布组优先级；requireChineseSubtitle 外语电影需要中文字幕 \"0\"/\"1\"；avoidHitAndRun 直接淘汰 H&R 站点的种子 \"0\"/\"1\"。\n"
                + "列表类字段用 JSON 字符串数组表示，空数组表示不限。\n"
                + "分辨率只能从这些里选：" + FilterVocabulary.RESOLUTIONS + "\n"
                + "片源只能从这些里选：" + FilterVocabulary.SOURCES + "\n"
                + "标签只能从这些里选：" + FilterVocabulary.TAGS + "\n"
                + "当前规则：" + JSON.toJSONString(now) + "\n"
                + "用户的要求：" + text + "\n"
                + "只输出需要修改的字段。只回复一个 JSON 对象，不要任何解释，形如 "
                + "{\"changes\":{\"maxSize\":30,\"excludeTags\":[\"DTS\"]},\"explanation\":\"一句中文说明改了什么\"}。"
                + "用户的要求无法用这些字段表达的部分，写进 explanation 说明做不到。";
    }

    static Draft validate(JSONObject json) {
        JSONObject raw = json.getJSONObject("changes");
        Map<String, Object> changes = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();
        if (raw != null) {
            for (String key : raw.keySet()) {
                Object v = raw.get(key);
                String error = accept(key, v, changes);
                if (error != null) {
                    dropped.add(key + "：" + error);
                }
            }
        }
        return new Draft(changes, StringUtils.trimToEmpty(json.getString("explanation")), dropped);
    }

    /** 校验单个字段，合规则写进 changes 并返回 null，否则返回丢弃原因 */
    private static String accept(String key, Object v, Map<String, Object> changes) {
        if ("minSeeders".equals(key)) {
            Double n = number(v);
            if (n == null || n < 0 || n > 10000) {
                return "不是合理的做种数";
            }
            changes.put(key, n.intValue());
            return null;
        }
        if (SIZE_KEYS.contains(key)) {
            Double gb = number(v);
            if (gb == null || gb < 0 || gb > 2048) {
                return "不是合理的体积";
            }
            changes.put(key, Math.round(gb * GB));
            return null;
        }
        if (FLAG_KEYS.contains(key)) {
            String s = v == null ? null : String.valueOf(v);
            if ("true".equalsIgnoreCase(s) || "1".equals(s)) {
                changes.put(key, "1");
            } else if ("false".equalsIgnoreCase(s) || "0".equals(s)) {
                changes.put(key, "0");
            } else {
                return "开关只能是开或关";
            }
            return null;
        }
        if (FREE_LIST_KEYS.contains(key)) {
            List<String> items = strings(v);
            if (items == null) {
                return "不是关键词列表";
            }
            changes.put(key, String.join(",", items));
            return null;
        }
        List<String> vocabulary = switch (key) {
            case "resolutionWhitelist", "resolutionPriority" -> FilterVocabulary.RESOLUTIONS;
            case "sourceWhitelist", "sourcePriority" -> FilterVocabulary.SOURCES;
            case "requiredTags", "excludeTags" -> FilterVocabulary.TAGS;
            default -> null;
        };
        if (vocabulary == null) {
            return "不是可修改的字段";
        }
        List<String> items = strings(v);
        if (items == null) {
            return "不是列表";
        }
        List<String> kept = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String item : items) {
            String canonical = vocabulary.stream().filter(w -> w.equalsIgnoreCase(item)).findFirst().orElse(null);
            if (canonical == null) {
                unknown.add(item);
            } else if (!kept.contains(canonical)) {
                kept.add(canonical);
            }
        }
        if (!unknown.isEmpty() && kept.isEmpty()) {
            return "取值 " + unknown + " 不在可选范围内";
        }
        changes.put(key, String.join(",", kept));
        return unknown.isEmpty() ? null : "已忽略不在可选范围内的 " + unknown;
    }

    private static Double number(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** 接受 JSON 数组或逗号分隔串 */
    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof JSONArray arr) {
            for (Object o : arr) {
                if (o != null && StringUtils.isNotBlank(o.toString())) {
                    out.add(o.toString().trim());
                }
            }
            return out;
        }
        if (v instanceof String s) {
            for (String part : s.split("[,，]")) {
                if (StringUtils.isNotBlank(part)) {
                    out.add(part.trim());
                }
            }
            return out;
        }
        return null;
    }

    private static Double toGb(Long bytes) {
        return bytes == null ? null : Math.round(bytes * 100.0 / GB) / 100.0;
    }
}
