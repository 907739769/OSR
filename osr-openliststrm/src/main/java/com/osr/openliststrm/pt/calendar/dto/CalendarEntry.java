package com.osr.openliststrm.pt.calendar.dto;

/**
 * 追剧日历里的一集。前端按 airDate 分组塞进日期格子。
 *
 * @param airDate     播出日期 yyyy-MM-dd
 * @param subId       订阅ID，点进去看详情用
 * @param tmdbId      TMDb ID
 * @param title       剧名
 * @param posterPath  海报路径（TMDb 相对路径）
 * @param season      季号
 * @param episode     集号
 * @param state       该集状态 MISSING/IN_FLIGHT/IN_LIBRARY/UPGRADING/BLOCKED
 *
 * @author Jack
 */
public record CalendarEntry(String airDate, Integer subId, String tmdbId, String title,
                            String posterPath, Integer season, Integer episode, String state) {
}
