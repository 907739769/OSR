package com.osr.openliststrm.pt.subscription.dto;

import lombok.Data;

import java.util.List;

/**
 * 订阅进度，供前端展示「已入库 5/12，缺 3、7」。
 *
 * @author Jack
 */
@Data
public class SubscriptionProgress {

    private Integer subId;

    private String title;

    private String status;

    /** 总集数；电影恒为 1 */
    private int totalEpisodes;

    /** 已入库集数 */
    private int inLibraryCount;

    /** 在途（已推送下载器但尚未入库）集数 */
    private int inFlightCount;

    /** 仍缺失的集号，升序 */
    private List<Integer> missingEpisodes;
}
