package com.osr.openliststrm.pt.calendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 TMDb 的「集号 → 播出日期」对齐到 OSR 本地的集号。
 * <p>
 * 两者不总是同一套编号：OSR 按<b>季内相对集号</b>存（1..N，见 {@code SubscriptionService#episodeNumbers}），
 * 而 TMDb 对长篇动画用的是<b>绝对集号</b>——《航海王》第 23 季的 episode_number 是 1156 起，
 * 不是 1 起。直接按集号取会整季取不到日期（实测订阅 84 就是这么全空的）。
 * </p>
 * <p>
 * 因此先按集号精确对，一个都对不上再退到按位置对；<b>位置兜底只在两边集数完全相等时启用</b>，
 * 数量对不上就宁可留空——错位的播出日期比没有日期更糟，用户会照着它去等一集根本不在那天播的剧。
 * </p>
 *
 * @author Jack
 */
public final class AirDateResolver {

    private AirDateResolver() {
    }

    /**
     * @param localEpisodes 本地集号，顺序不限（内部按升序处理）
     * @param tmdbAirDates  TMDb 的集号 → 播出日期，需按集号升序（{@code TmdbSearchService} 保证）
     * @return 本地集号 → 播出日期，只含解析成功的项
     */
    public static Map<Integer, LocalDate> resolve(List<Integer> localEpisodes, Map<Integer, LocalDate> tmdbAirDates) {
        Map<Integer, LocalDate> resolved = new LinkedHashMap<>();
        if (localEpisodes == null || localEpisodes.isEmpty() || tmdbAirDates == null || tmdbAirDates.isEmpty()) {
            return resolved;
        }
        List<Integer> sorted = new ArrayList<>(localEpisodes);
        sorted.sort(Integer::compareTo);

        for (Integer episode : sorted) {
            LocalDate date = tmdbAirDates.get(episode);
            if (date != null) {
                resolved.put(episode, date);
            }
        }
        if (!resolved.isEmpty()) {
            return resolved;
        }

        // 一个都没对上 = 两边用的不是同一套编号。集数相等时按位置一一对应，否则放弃
        if (sorted.size() != tmdbAirDates.size()) {
            return resolved;
        }
        List<LocalDate> byPosition = new ArrayList<>(tmdbAirDates.values());
        for (int i = 0; i < sorted.size(); i++) {
            resolved.put(sorted.get(i), byPosition.get(i));
        }
        return resolved;
    }
}
