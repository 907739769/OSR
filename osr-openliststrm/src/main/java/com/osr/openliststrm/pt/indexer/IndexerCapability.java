package com.osr.openliststrm.pt.indexer;

/**
 * 索引器 t=caps 探测出的 ID 搜索能力：movie-search/tv-search 是否支持 imdbid/tmdbid 参数。
 *
 * @author Jack
 */
public record IndexerCapability(boolean movieImdbSupported, boolean movieTmdbSupported,
                                 boolean tvImdbSupported, boolean tvTmdbSupported) {

    /** 探测失败、响应非法、或索引器未声明任何 ID 搜索能力时的安全默认值 */
    public static final IndexerCapability NONE = new IndexerCapability(false, false, false, false);
}
