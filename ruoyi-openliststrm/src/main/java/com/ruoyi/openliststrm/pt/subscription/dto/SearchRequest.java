package com.ruoyi.openliststrm.pt.subscription.dto;

import lombok.Data;

/**
 * 搜索补集请求体。
 *
 * @author Jack
 */
@Data
public class SearchRequest {

    /** 目标集号：-1(SubscriptionMatcher.SEASON_PACK)=季包/整部，电影恒为0，剧集单集传具体集号 */
    private int episode;

    /** 搜索关键词，前端按标题/季集号预填，用户可编辑 */
    private String keyword;

    /** 是否启用手动选择模式：true=返回候选列表供用户挑选，false（默认）=自动推送最优结果 */
    private boolean manualSelect;
}
