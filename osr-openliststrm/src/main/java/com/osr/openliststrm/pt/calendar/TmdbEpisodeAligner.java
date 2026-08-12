package com.osr.openliststrm.pt.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 TMDb 的季内条目对齐到 OSR 本地的集号。
 * <p>
 * 两边不总是同一套编号：OSR 按<b>季内相对集号</b>存（1..N，见 {@code SubscriptionService#episodeNumbers}），
 * 而 TMDb 对长篇动画用的是<b>绝对集号</b>——《航海王》第 23 季的 episode_number 是 1156 起，不是 1 起。
 * 直接按集号取会整季取不到（实测订阅 84 就是这么全空的）。种子标题自己印证了这个对应关系：
 * {@code One Piece S23E13 Episode 1168}，本地第 13 集 = TMDb 的 1168。
 * </p>
 * <p>
 * 先按集号精确对，一个都对不上再退到按位置对；<b>位置兜底只在两边集数完全相等时启用</b>，
 * 数量对不上就宁可留空——错位的对应关系会同时污染播出日期和入库判定，比没有更糟。
 * </p>
 *
 * @author Jack
 */
public final class TmdbEpisodeAligner {

    /**
     * 本地某一集对应的 TMDb 条目。
     *
     * @param episodeNumber TMDb 侧的集号。普通剧集与本地集号相同；长篇动画是绝对集号，
     *                      也正是媒体库按绝对编号组织时用的那个号
     * @param airDate       播出日期，TMDb 未录入时为 null
     */
    public record TmdbEpisodeRef(int episodeNumber, LocalDate airDate) {
    }

    private TmdbEpisodeAligner() {
    }

    /**
     * @param localEpisodes 本地集号，顺序不限（内部按升序处理）
     * @param tmdbEpisodes  TMDb 的集号 → 播出日期，需按集号升序（{@code TmdbSearchService} 用 TreeMap 保证）
     * @return 本地集号 → TMDb 条目，只含对齐成功的项
     */
    public static Map<Integer, TmdbEpisodeRef> align(List<Integer> localEpisodes,
                                                     Map<Integer, LocalDate> tmdbEpisodes) {
        Map<Integer, TmdbEpisodeRef> aligned = new LinkedHashMap<>();
        if (localEpisodes == null || localEpisodes.isEmpty() || tmdbEpisodes == null || tmdbEpisodes.isEmpty()) {
            return aligned;
        }
        List<Integer> sorted = new ArrayList<>(localEpisodes);
        sorted.sort(Integer::compareTo);

        for (Integer episode : sorted) {
            if (tmdbEpisodes.containsKey(episode)) {
                aligned.put(episode, new TmdbEpisodeRef(episode, tmdbEpisodes.get(episode)));
            }
        }
        if (!aligned.isEmpty()) {
            return aligned;
        }

        // 一个都没对上 = 两边用的不是同一套编号。集数相等时按位置一一对应，否则放弃
        if (sorted.size() != tmdbEpisodes.size()) {
            return aligned;
        }
        List<Map.Entry<Integer, LocalDate>> byPosition = new ArrayList<>(tmdbEpisodes.entrySet());
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<Integer, LocalDate> entry = byPosition.get(i);
            aligned.put(sorted.get(i), new TmdbEpisodeRef(entry.getKey(), entry.getValue()));
        }
        return aligned;
    }
}
