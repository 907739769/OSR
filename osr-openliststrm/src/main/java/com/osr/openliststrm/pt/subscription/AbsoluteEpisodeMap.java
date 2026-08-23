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

    /** 本地集号对应的绝对号；不是本订阅的集、或该剧不用绝对编号时返回 null */
    public Integer toAbsolute(Integer localEpisode) {
        if (localEpisode == null) {
            return null;
        }
        for (Map.Entry<Integer, Integer> entry : absoluteToLocal.entrySet()) {
            if (entry.getValue().equals(localEpisode)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 一个种子按绝对编号解释后落在本季的哪几集。单集时 {@code end == start}。
     */
    public record LocalRange(int start, int end) {

        /** 是不是一个真区间（跨多集），单集返回 false */
        public boolean isRange() {
            return end > start;
        }
    }

    /**
     * 把种子解析出的季/集号按绝对编号解释成本地集号，解释不通返回 {@code null}。
     * <p>
     * <b>「怎么解释绝对号」的判据只有这一份</b>，RSS 匹配（{@code SubscriptionMatcher#matchByAbsolute}）
     * 与搜索补集（{@code SearchSupplementService#filterByTarget}）都调它。两边曾各写一份，
     * 而搜索那份只转换单集、不认区间——于是 {@code S01E51-E66} 这种绝对号区间在 RSS 上能展开成
     * 本季 1~16 集，用户手动搜第 5 集却被判成不匹配，同一个种子两条链路给出相反结论。
     * </p>
     * <p>
     * 三个约束，缺一不可：
     * </p>
     * <ol>
     *   <li><b>该订阅确实使用绝对编号</b>（映射非空）。普通剧集绝不走这条路，
     *       否则 S01E05 会被任意一季的第 5 集认领</li>
     *   <li><b>种子季号缺失或为 1</b>。绝对编号发布的约定俗成写法就这两种；
     *       写着 S02 却想按绝对号解释的，多半是真的第 2 季，不该猜</li>
     *   <li><b>该绝对号确实属于本订阅这一季</b>。映射里只有本季的集，别季的绝对号查不到，
     *       自然落空</li>
     * </ol>
     * <p>
     * 区间要求<b>两端都能落回本季</b>，否则跨季的绝对号区间会被截成一段假区间。
     * 集号缺失（有季无集 = 季包）一律返回 null：一个标着 S01 的季包对绝对编号的剧来说是
     * 「整部剧」，认成本季季包会让下载器去拉一千多集。
     * </p>
     *
     * @param season     种子解析出的季号，可为 null
     * @param episode    种子解析出的集号（绝对号），为 null 时直接判解释不通
     * @param episodeEnd 种子解析出的集数区间结尾（绝对号），非区间时为 null
     */
    public LocalRange toLocalRange(Integer season, Integer episode, Integer episodeEnd) {
        if (isEmpty() || episode == null) {
            return null;
        }
        if (season != null && season != 1) {
            return null;
        }
        Integer local = toLocal(episode);
        if (local == null) {
            return null;
        }
        if (episodeEnd == null || episodeEnd <= episode) {
            return new LocalRange(local, local);
        }
        Integer localEnd = toLocal(episodeEnd);
        return localEnd != null && localEnd > local ? new LocalRange(local, localEnd) : null;
    }

    /**
     * 把一个「来源不明的集号」归一化成本地集号：认得出的绝对号转成本地号，认不出的原样返回。
     * <p>
     * 用于种子内文件名解析出的集号——同一个包里的文件未必用同一套编号，而且普通剧集
     * 本来就用本地号。转不动就保持原值，让调用方按原有逻辑处理（多半是排除该文件）。
     * </p>
     */
    public Integer toLocalOrSelf(Integer number) {
        Integer local = toLocal(number);
        return local != null ? local : number;
    }
}
