package com.osr.openliststrm.pt.media;

import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 媒体服务器抽象接口。用于查询「某作品已入库了哪些内容」，
 * 是订阅集数追踪的权威数据来源。
 *
 * @author Jack
 */
public interface IMediaServerClient {

    /**
     * 支持的类型，与 pt_media_server.type 取值一致，如 EMBY / JELLYFIN。
     */
    String type();

    /**
     * 连通性测试。任何异常均视为不连通，不向上抛。
     * <p>
     * 返回 {@link MediaServerProbe} 而不是 boolean，是为了把「为什么不通」带回用户眼前——
     * 理由见该类的注释。实现里要把异常翻成能指导处置的中文，不要原样抛 {@code getMessage()}：
     * 那多半是一串英文的 {@code SocketTimeoutException} 之类，对用户没有意义。
     * </p>
     */
    MediaServerProbe testConnection(PtMediaServerPlus config);

    /**
     * 列出媒体服务器上的用户，供配置页选取「用户ID」。
     * <p>
     * 默认返回空表：不支持这个概念的服务器实现（将来的 Plex 等）不必为此改动，
     * 配置页那一侧会退回纯手填。
     * </p>
     *
     * @throws IOException 网络异常或服务器返回非 2xx
     */
    default List<MediaServerUser> listUsers(PtMediaServerPlus config) throws IOException {
        return List.of();
    }

    /**
     * 查询某剧某季在库中已有的集号集合。
     *
     * @param tmdbId TMDb 剧集 ID
     * @param season 季号
     * @return 已有集号集合；该剧不在库中时返回空集合
     * @throws IOException 网络异常或服务器返回非 2xx
     */
    Set<Integer> listEpisodes(PtMediaServerPlus config, String tmdbId, int season) throws IOException;

    /**
     * 查询整部剧在库中出现过的全部集号，不分季。
     * <p>
     * 给「媒体库的分季方式与订阅不一致」的情况兜底：长篇动画常被整部平铺在第 1 季、
     * 用绝对集号编号，此时按季查恒为空。调用方只在按季查不到、且该集的 TMDb 集号
     * 与本地集号不同时才会用到它，普通剧集不会触发这次请求。
     * </p>
     * <p>
     * 默认返回空集：实现该接口的服务器若没有「全剧查询」的能力，退化成原有的按季匹配即可。
     * </p>
     */
    default Set<Integer> listAllEpisodeNumbers(PtMediaServerPlus config, String tmdbId) throws IOException {
        return Set.of();
    }

    /**
     * 查询某电影是否已在库中。
     *
     * @param tmdbId TMDb 电影 ID
     * @throws IOException 网络异常或服务器返回非 2xx
     */
    boolean hasMovie(PtMediaServerPlus config, String tmdbId) throws IOException;

    /**
     * 查询观看状态：看过哪几集、最近一次什么时候看的。
     * <p>
     * 返回 <b>null 表示这台服务器读不到</b>（不支持，或 Emby/Jellyfin 没配用户 ID——观看记录是按用户的），
     * 与「读到了、一集都没看」（{@link WatchState#NONE}）必须分开：前者不能拿去覆盖别的服务器读到的结果。
     * 默认 null，新接入的服务器不实现也不影响对账。
     *
     * @param season 季号；电影传 null
     * @throws IOException 网络异常或服务器返回非 2xx
     */
    default WatchState watchState(PtMediaServerPlus config, String tmdbId, Integer season, boolean movie)
            throws IOException {
        return null;
    }

    /**
     * 查询各集（电影为集号 0）的音轨与字幕流，供字幕体检用。
     * <p>
     * 返回 <b>null 表示这台服务器不支持</b>，与「支持、但这部作品不在库里」（空 Map）分开——
     * 前者要在页面上说明「这台服务器查不了」，后者只是没东西可查。默认 null。
     *
     * @param season 季号；电影或不分季时传 null
     * @throws IOException 网络异常或服务器返回非 2xx
     */
    default Map<Integer, MediaStreamInfo> listStreamInfo(PtMediaServerPlus config, String tmdbId, Integer season,
                                                         boolean movie) throws IOException {
        return null;
    }
}
