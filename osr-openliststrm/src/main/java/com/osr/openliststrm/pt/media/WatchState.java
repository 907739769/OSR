package com.osr.openliststrm.pt.media;

import java.util.Date;
import java.util.Set;

/**
 * 一部作品在媒体库里的观看状态。
 *
 * @param watchedEpisodes 看过的集号（电影看过时是 {@code {0}}）
 * @param lastWatched     最近一次观看的时间，没看过为 null
 * @author Jack
 */
public record WatchState(Set<Integer> watchedEpisodes, Date lastWatched) {

    public static final WatchState NONE = new WatchState(Set.of(), null);
}
