package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.pt.model.TorrentInfo;

import java.util.List;

/**
 * 算一条种子覆盖多少集，供体积判定归一化到「每集体积」。
 * <p>
 * 独立成类而不是塞进 {@link TorrentFilterEngine}：过滤引擎是纯函数，只认
 * {@link FilterCriteria}，不该知道"订阅有多少集"这种业务事实；而三条链路
 * （RSS 推送、搜索补集、洗版扫描）都要在调用引擎前算这个数，口径必须只有一份。
 * </p>
 * <p>
 * 只收原始类型入参、不引 {@code PtSubscriptionPlus}，本包因此不依赖数据层。
 * </p>
 *
 * @author Jack
 */
public final class EpisodeCountResolver {

    private EpisodeCountResolver() {
    }

    /**
     * 就地给一批候选填好 {@code episodeCount}，调用引擎前跑一遍即可。
     *
     * @param subTotalEpisodes 目标订阅的总集数，用于估算整季包；未知传 null
     * @param movie            目标订阅是否为电影
     */
    public static void apply(List<TorrentInfo> torrents, Integer subTotalEpisodes, boolean movie) {
        if (torrents == null) {
            return;
        }
        for (TorrentInfo torrent : torrents) {
            torrent.setEpisodeCount(resolve(torrent, subTotalEpisodes, movie));
        }
    }

    /**
     * @return 覆盖的集数，恒 &gt;= 1
     */
    public static int resolve(TorrentInfo torrent, Integer subTotalEpisodes, boolean movie) {
        if (movie) {
            // 电影没有集的概念，体积就是体积，不做任何折算
            return 1;
        }
        Integer start = torrent.getParsedEpisode();
        Integer end = torrent.getParsedEpisodeEnd();
        if (start != null && end != null && end > start) {
            return end - start + 1;
        }
        if (start == null && torrent.getParsedSeason() != null) {
            // 有季无集 = 整季包，标题里没有任何集数信息，只能按订阅总集数估算。
            // 估不出来（订阅刚建、TMDb 没给出总集数）时退回 1：宁可不折算，
            // 也不能凭空编一个集数把体积判定引向另一个错误的方向
            return (subTotalEpisodes != null && subTotalEpisodes > 0) ? subTotalEpisodes : 1;
        }
        return 1;
    }
}
