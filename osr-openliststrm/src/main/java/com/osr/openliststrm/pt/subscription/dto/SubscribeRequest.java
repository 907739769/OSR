package com.osr.openliststrm.pt.subscription.dto;

import lombok.Data;

/**
 * 建订阅入参。
 *
 * @author Jack
 */
@Data
public class SubscribeRequest {

    /** TMDb ID */
    private String tmdbId;

    /** 媒体类型 TV / MOVIE */
    private String mediaType;

    /** 季号；剧集必填，电影忽略（服务端会写成哨兵值 0） */
    private Integer season;

    /** 指定下载器，可空 */
    private Integer downloaderId;

    /** 订阅级过滤覆盖(JSON)，可空 */
    private String filterOverride;
}
