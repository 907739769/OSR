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

    /**
     * 上面那批缺集里<b>尚未播出</b>的集号，升序，是 {@link #missingEpisodes} 的子集。
     * <p>
     * 单列一份而不是从 missingEpisodes 里剔除：用户打开进度弹窗就是要知道「这季还缺几集」，
     * 把未播集藏起来会让缺集数与总集数对不上。它存在只为一件事——「一键补齐全部」跳过这些集。
     * 未播出的集站上不可能有资源，为它们各打一整轮索引器请求（每集 4~6 步）必然全部落空，
     * 一部刚播到第 3 集的 12 集新番能让用户白等十几分钟。判据见
     * {@code SubscriptionService#aired}，与自动补搜侧共用一份。
     * </p>
     */
    private List<Integer> unairedEpisodes;
}
