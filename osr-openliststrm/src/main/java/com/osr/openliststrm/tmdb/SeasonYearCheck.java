package com.osr.openliststrm.tmdb;

import java.util.Map;
import java.util.NavigableMap;

/**
 * 「第 N 季 + 年份」放在某部剧上说不说得通。刮削侧（{@link TMDbClient} 挑候选）与资源搜索页
 * （{@code ResourceSearchService#contradicts} 拆同名剧）共用这一份判据——各写一份的表现是
 * 「搜索页认成 A、重命名同一个文件认成 B」。
 * <p>
 * 依据：发布组在剧集种子/文件名上标的年份，要么是整部剧的首播年，要么是<b>本季</b>的播出年。
 * 起因是 {@code The Prince of Tennis II S03E02 2026}：TMDb 上《新网球王子》(2012，只有 1 季、
 * 2012 年完结) 的英文规范名与它逐字全等，而真正的答案《新网球王子 U-17世界杯篇》(2022) 只是包含命中，
 * 但后者的第 3 季正是 2026 年开播——标题分不出来的两部作品，季与年份分得出来。
 * </p>
 * <p>
 * 只在<b>有依据</b>时下结论，缺什么就不判什么，判不出一律 {@link Verdict#UNKNOWN}。
 * </p>
 */
public final class SeasonYearCheck {

    public enum Verdict {
        /** 这部剧登记了这一季，且开播年与给出的年份相差不超过 {@link #TOLERANCE} 年 */
        FITS,
        /** 年份在这部剧的这一季上说不通 */
        CONTRADICTS,
        /** 没有可核对的依据，或说得通但算不上「季对得上」（如年份只是贴近首播年） */
        UNKNOWN
    }

    /** 跨年播出、宣发年与开播年不一致，差一年以内都算对得上 */
    public static final int TOLERANCE = 1;

    private SeasonYearCheck() {
    }

    /**
     * @param season       季号，没有时为 null
     * @param year         种子/文件名上的年份，没有时为 null
     * @param firstAirYear 这部剧的首播年
     * @param lastAirYear  这部剧最近一次播出的年份
     * @param seasonYears  季号 → 该季开播年；没登记开播日期的季与特别篇（第 0 季）不在里面
     */
    public static Verdict check(Integer season, Integer year, Integer firstAirYear, Integer lastAirYear,
                                NavigableMap<Integer, Integer> seasonYears) {
        if (year == null) {
            return Verdict.UNKNOWN;
        }
        Integer seasonYear = season == null || seasonYears == null ? null : seasonYears.get(season);
        if (seasonYear != null && Math.abs(year - seasonYear) <= TOLERANCE) {
            return Verdict.FITS;
        }
        // 标的是首播年：说得通，但证明不了是这一季
        if (firstAirYear != null && Math.abs(year - firstAirYear) <= TOLERANCE) {
            return Verdict.UNKNOWN;
        }
        if (seasonYear != null) {
            // 一季可以跨好几年播（航海王第 21 季从 2019 播到 2023），上界取下一季的开播年；最新一季不设上界
            Map.Entry<Integer, Integer> next = seasonYears.higherEntry(season);
            boolean contradicts = year < seasonYear - TOLERANCE
                    || (next != null && year > next.getValue() + TOLERANCE);
            return contradicts ? Verdict.CONTRADICTS : Verdict.UNKNOWN;
        }
        // 续季只可能更晚，不可能比首播还早
        if (firstAirYear != null && year < firstAirYear - TOLERANCE) {
            return Verdict.CONTRADICTS;
        }
        // 这部剧没登记这一季，而它最后一次播出已经是好几年前：这一季不是它的。
        // 只看「没登记」不够——TMDb 常把动画各季并进第 1 季，但那种剧还在播，最近播出年跟得上
        if (season != null && lastAirYear != null && year > lastAirYear + TOLERANCE) {
            return Verdict.CONTRADICTS;
        }
        return Verdict.UNKNOWN;
    }
}
