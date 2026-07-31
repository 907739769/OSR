package com.osr.openliststrm.pt.media;

import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;

import java.io.IOException;
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
     */
    boolean testConnection(PtMediaServerPlus config);

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
     * 查询某电影是否已在库中。
     *
     * @param tmdbId TMDb 电影 ID
     * @throws IOException 网络异常或服务器返回非 2xx
     */
    boolean hasMovie(PtMediaServerPlus config, String tmdbId) throws IOException;
}
