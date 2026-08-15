package com.osr.openliststrm.pt.transfer;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存路径的前缀映射：把源下载器视角下的路径翻译成目标下载器视角下的路径。
 * <p>
 * 两个下载器把同一份数据挂在不同路径上时（qB 里是 {@code /downloads/xxx}、
 * Transmission 里是 {@code /data/downloads/xxx}）必须配这个，否则目标端会在自己的
 * 路径下找不到数据，校验必然不通过。挂载一致时留空即可。
 * </p>
 * <p>
 * 只做<b>前缀</b>替换，不做任意位置的子串替换：路径中间出现同名片段是很常见的
 * （{@code /downloads/movies/downloads-2024/}），按子串替换会把它们一起改掉，
 * 得到一个看起来对、实际不存在的路径。
 * </p>
 *
 * @author Jack
 */
@Slf4j
public final class PathMapping {

    private final List<Entry> entries;

    private PathMapping(List<Entry> entries) {
        this.entries = entries;
    }

    /**
     * 解析规则里的 JSON 配置，形如 {@code [{"from":"/downloads","to":"/data/downloads"}]}。
     * <p>
     * 解析失败<b>不抛异常</b>，退化成"不映射"并记一条 warn：配置格式错误的代价应当是
     * "路径没被翻译、校验不通过、这次转移失败"，而不是"整条规则连带别的种子一起停摆"。
     * 失败的那次转移会在记录里留下明确的进度与路径，比一条解析异常更能指出问题在哪。
     * </p>
     */
    public static PathMapping parse(String json) {
        List<Entry> entries = new ArrayList<>();
        if (StringUtils.isBlank(json)) {
            return new PathMapping(entries);
        }
        try {
            JSONArray array = JSONArray.parse(json);
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                String from = item.getString("from");
                String to = item.getString("to");
                if (StringUtils.isNotBlank(from) && StringUtils.isNotBlank(to)) {
                    entries.add(new Entry(trimTrailingSlash(from), trimTrailingSlash(to)));
                }
            }
        } catch (Exception e) {
            log.warn("路径映射配置解析失败，本次按不映射处理：{}", e.getMessage());
            return new PathMapping(List.of());
        }
        return new PathMapping(entries);
    }

    /**
     * 应用映射。取<b>第一条前缀命中</b>的规则，命中后不再往下看；一条都不命中时原样返回。
     *
     * @param path 源下载器视角下的保存路径
     */
    public String apply(String path) {
        if (StringUtils.isBlank(path)) {
            return path;
        }
        String normalized = path.replace('\\', '/');
        for (Entry entry : entries) {
            if (matches(normalized, entry.from)) {
                return entry.to + normalized.substring(entry.from.length());
            }
        }
        return path;
    }

    /**
     * 前缀是否命中。要求前缀之后要么到头、要么正好是一个路径分隔符——
     * 否则 {@code /downloads} 会命中 {@code /downloads-old/xxx}，映射出一个不存在的路径。
     */
    private boolean matches(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        return path.length() == prefix.length() || path.charAt(prefix.length()) == '/';
    }

    private static String trimTrailingSlash(String value) {
        String trimmed = value.trim().replace('\\', '/');
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** 配置了几条映射；0 表示不做任何翻译 */
    public int size() {
        return entries.size();
    }

    private record Entry(String from, String to) {
    }
}
