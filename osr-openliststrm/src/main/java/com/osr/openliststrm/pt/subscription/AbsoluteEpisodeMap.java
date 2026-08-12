package com.osr.openliststrm.pt.subscription;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 某个订阅的「绝对集号 → 本地集号」映射，用于识别按绝对编号发布的种子。
 * <p>
 * 长篇动画的发布组常按绝对集号命名：《航海王》第 23 季第 18 集在站上叫
 * {@code One Piece S01E1173}（1173 是绝对号，季号写死 1），而订阅是第 23 季第 18 集。
 * 没有这层映射，季号 1≠23 会让这类种子在匹配的第一步就被淘汰——用户看到的现象是
 * 「站上明明有资源，OSR 就是搜不到」。
 * </p>
 * <p>
 * <p>
 * <b>只覆盖带 S/E 标记的命名</b>（{@code S01E1173}、{@code 第1173集}）。字幕组常见的
 * {@code [Sakurato] One Piece - 1173 [2160p]} 这类裸数字命名当前仍匹配不上，但卡点不在这里：
 * 解析器会把标题解析成 {@code [ Sakurato ] One Piece - 1173 [ ] [ - ]}，在标题匹配那一步就被淘汰了，
 * 根本走不到集号判定。要支持得先修 {@code YearSeasonEpisodeExtractor} 对该格式的标题截断。
 * </p>
 * <p>
 * 映射来源是 {@code pt_subscription_episode.tmdb_episode_number}（由
 * {@code EpisodeAirDateSyncTask} 维护）。<b>只有该值与本地集号不同的订阅才建映射</b>：
 * 普通剧集两者相等，建了也只会放大误判面——S01E05 会被任意一季的第 5 集认领。
 * </p>
 *
 * @author Jack
 */
public final class AbsoluteEpisodeMap {

    /** 空映射：这部剧不使用绝对编号，或集号尚未同步 */
    public static final AbsoluteEpisodeMap EMPTY = new AbsoluteEpisodeMap(Map.of());

    private final Map<Integer, Integer> absoluteToLocal;

    private AbsoluteEpisodeMap(Map<Integer, Integer> absoluteToLocal) {
        this.absoluteToLocal = absoluteToLocal;
    }

    /**
     * 按订阅的集行建映射。任一集的 TMDb 集号与本地集号不同才认为这部剧用绝对编号；
     * 全部相同（普通剧集）时返回 {@link #EMPTY}。
     */
    public static AbsoluteEpisodeMap from(List<PtSubscriptionEpisodePlus> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            return EMPTY;
        }
        Map<Integer, Integer> map = new HashMap<>();
        boolean differs = false;
        for (PtSubscriptionEpisodePlus ep : episodes) {
            Integer local = ep.getEpisode();
            Integer absolute = ep.getTmdbEpisodeNumber();
            if (local == null || absolute == null) {
                continue;
            }
            if (!absolute.equals(local)) {
                differs = true;
            }
            map.putIfAbsent(absolute, local);
        }
        return differs ? new AbsoluteEpisodeMap(Map.copyOf(map)) : EMPTY;
    }

    public boolean isEmpty() {
        return absoluteToLocal.isEmpty();
    }

    /** 绝对号对应的本地集号；不是本订阅的集则返回 null */
    public Integer toLocal(Integer absolute) {
        return absolute == null ? null : absoluteToLocal.get(absolute);
    }
}
