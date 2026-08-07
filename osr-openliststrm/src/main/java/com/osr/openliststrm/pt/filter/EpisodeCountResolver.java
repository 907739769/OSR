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
            return capByFileCount(torrent, end - start + 1);
        }
        if (start == null && torrent.getParsedSeason() != null) {
            // 有季无集 = 整季包，标题里没有任何集数信息，只能按订阅总集数估算。
            // 估不出来（订阅刚建、TMDb 没给出总集数）时退回 1：宁可不折算，
            // 也不能凭空编一个集数把体积判定引向另一个错误的方向
            int estimated = (subTotalEpisodes != null && subTotalEpisodes > 0) ? subTotalEpisodes : 1;
            return capByFileCount(torrent, estimated);
        }
        return 1;
    }

    /**
     * 用 {@code files}（种子内文件总数）给集数估算<b>收口</b>——包内集数不可能超过文件总数。
     * <p>
     * 标题声称的覆盖范围（"整季"、"[01-26]"）只是发布者这么写。「按季包命名、实际只含 1 集」
     * 的种子在标题上与真季包完全一致，体积也分不开——8GB 可能是 8 集 × 1GB，也可能是 1 集
     * Remux。盲信标题会把这种种子的每集体积折算成实际值的 1/N，任何体积下限都拦不住它。
     * </p>
     * <p>
     * 只做<b>上界</b>收口，不反向放大：真季包常带 nfo/字幕/封面，files 大于集数是常态
     * （8 集的包可能有 20 个文件），拿它当集数会把每集体积折算得过小，方向与本方法的目的相反。
     * files 为 null（索引器未提供该属性）时原样返回估算值，维持既有行为。
     * </p>
     *
     * @return 恒 &gt;= 1
     */
    private static int capByFileCount(TorrentInfo torrent, int estimated) {
        Integer files = torrent.getFiles();
        if (files == null || files <= 0) {
            return estimated;
        }
        return Math.max(1, Math.min(estimated, files));
    }
}
