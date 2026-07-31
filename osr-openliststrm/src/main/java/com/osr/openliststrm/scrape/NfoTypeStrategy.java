package com.osr.openliststrm.scrape;

import com.osr.openliststrm.rename.model.MediaInfo;

/**
 * NFO 类型策略接口，每种媒体类型（Movie/TvShow/Season/Episode）提供独立实现。
 */
public interface NfoTypeStrategy {

    /**
     * 生成 NFO XML 内容
     *
     * @param info 媒体信息
     * @return NFO XML 字符串
     */
    String buildNfo(MediaInfo info);
}
