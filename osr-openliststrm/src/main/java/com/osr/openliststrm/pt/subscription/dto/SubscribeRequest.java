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

    /**
     * 订阅归属人(sys_user.user_id)，可空（＝公共订阅）。
     * <b>由服务端填充</b>：Web 端取当前登录用户，企微端取发指令成员绑定的 OSR 用户。
     * 前端请求体里带的值会被覆盖，否则任何人都能把订阅挂到别人名下。
     */
    private Long ownerUserId;
}
