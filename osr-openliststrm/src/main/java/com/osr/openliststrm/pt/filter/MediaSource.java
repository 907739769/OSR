package com.osr.openliststrm.pt.filter;

import com.osr.common.utils.StringUtils;

import java.util.List;

/**
 * 媒介来源的 PT 口径：把 REMUX 从「标签」提升为「来源」，并让它向下兼容 BluRay。
 * <p>
 * 重命名解析器（{@code SourceAndGroupExtractor}）把 REMUX 识别成<b>标签</b>，来源一栏只会是
 * BluRay——{@code Show.2160p.BluRay.REMUX} 解析出来是 {@code source=BluRay, tags=[REMUX]}。
 * 而来源白名单、来源优先级、洗版目标来源这三处的界面说明和默认值（{@code REMUX,BluRay}）
 * 都把 REMUX 当成一个来源，于是写进去的 {@code REMUX} 一条都命中不了：只配
 * {@code REMUX} 的白名单淘汰全部种子，{@code REMUX,BluRay} 的优先级里 REMUX 与压制版并列，
 * 洗版目标只写 REMUX 时永远找不到"更好"的候选。全部静默。
 * </p>
 * <p>
 * 刻意<b>不改解析器本身</b>：它同时服务于重命名，{@code {{source}}} 进了文件名模板，
 * 改它会让存量媒体库的命名口径分叉。归一化只发生在 PT 这一侧。
 * </p>
 * <p>
 * 兼容规则：REMUX 本来就是蓝光原盘的无损封装，列表里<b>没写 REMUX 时按 BluRay 对待</b>。
 * 这保证了升级前后行为不变——此前 REMUX 种子一直被当成 BluRay，白名单写 BluRay 的用户
 * 升级后照样收得到 REMUX；而写了 REMUX 的列表从此能把两者区分开。
 * </p>
 *
 * @author Jack
 */
public final class MediaSource {

    public static final String REMUX = "REMUX";
    public static final String BLURAY = "BluRay";

    private MediaSource() {
    }

    /**
     * PT 侧生效的来源：标签里有 REMUX、且解析出的来源为空或是 BluRay 时，来源记为 REMUX。
     * 来源是 WEBDL 之类却带 REMUX 标签的属于标题写乱了，保留解析结果不去猜。
     */
    public static String effective(String parsedSource, List<String> tags) {
        if (!hasRemuxTag(tags)) {
            return parsedSource;
        }
        if (StringUtils.isBlank(parsedSource) || BLURAY.equalsIgnoreCase(parsedSource.trim())) {
            return REMUX;
        }
        return parsedSource;
    }

    /**
     * 来源是否在列表里（大小写不敏感）。列表没写 REMUX 时，REMUX 按 BluRay 判。
     */
    public static boolean in(List<String> list, String source) {
        if (StringUtils.isBlank(source) || list == null) {
            return false;
        }
        String trimmed = source.trim();
        if (containsIgnoreCase(list, trimmed)) {
            return true;
        }
        return REMUX.equalsIgnoreCase(trimmed) && !containsIgnoreCase(list, REMUX)
                && containsIgnoreCase(list, BLURAY);
    }

    /**
     * 来源在优先级列表里的名次，口径同 {@link PriorityRanker#rankOf}。
     * 列表没写 REMUX 时，REMUX 取 BluRay 的名次。
     */
    public static int rank(String source, List<String> priority) {
        if (StringUtils.isNotBlank(source) && REMUX.equalsIgnoreCase(source.trim())
                && priority != null && !containsIgnoreCase(priority, REMUX)) {
            return PriorityRanker.rankOf(BLURAY, priority);
        }
        return PriorityRanker.rankOf(source, priority);
    }

    private static boolean hasRemuxTag(List<String> tags) {
        return tags != null && containsIgnoreCase(tags, REMUX);
    }

    private static boolean containsIgnoreCase(List<String> list, String value) {
        for (String item : list) {
            if (item != null && item.trim().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
