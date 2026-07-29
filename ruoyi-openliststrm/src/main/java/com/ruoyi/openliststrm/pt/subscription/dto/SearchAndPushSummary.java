package com.ruoyi.openliststrm.pt.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * {@link com.ruoyi.openliststrm.pt.subscription.SearchSupplementService#searchAndPushMissing}
 * 的结果摘要，供 {@code SubscriptionSearchOnCreateTrigger}（建订阅补搜）与
 * {@code AutoSearchService}（定期自动补搜）复用同一套"单次搜索、季包优先、
 * 未命中则本地逐集匹配"逻辑后各自决定是否需要通知用户。
 *
 * @author Jack
 */
@Data
@AllArgsConstructor
public class SearchAndPushSummary {

    /** 订阅不存在/未订阅中/当前没有缺集，本次直接跳过未发起搜索 */
    private boolean skipped;

    /** 整季包是否推送成功（剧集）或唯一目标是否推送成功（电影） */
    private boolean seasonPushed;

    /** 从候选池本地匹配并推送成功的散集数量（电影恒为 0） */
    private int episodesPushed;

    public static SearchAndPushSummary skip() {
        return new SearchAndPushSummary(true, false, 0);
    }

    public boolean anyPushed() {
        return seasonPushed || episodesPushed > 0;
    }
}
