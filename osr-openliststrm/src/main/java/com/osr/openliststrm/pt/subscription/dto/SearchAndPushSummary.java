package com.osr.openliststrm.pt.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * {@link com.osr.openliststrm.pt.subscription.SearchSupplementService#searchAndPushMissing}
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

    /**
     * 本次搜索里「候选被过滤规则淘汰」的聚合说明，如
     * {@code "103 个候选被过滤规则淘汰：98 个「非免费种」、5 个「做种数不足」"}。
     * <p>
     * 只在确实有候选被规则淘汰时非空——压根没搜到候选时为 {@code null}，那种情况与过滤规则无关，
     * 调用方应退回泛化文案。让通知能说出真正的原因，而不是一律提示"检查索引器配置"
     * 把用户往错误方向引（索引器好好的，是他自己开的 freeOnly 把候选全清了）。
     * </p>
     */
    private String rejectSummary;

    /**
     * 与 {@link #rejectSummary} 同源的原因<b>种类</b>指纹（见
     * {@code SearchLogService.RejectionDigest#signature}），供调用方判断「这次落空的原因
     * 和上次是不是同一类」。
     * <p>
     * 不能用 {@link #rejectSummary} 自己去比：它带着计数，数字每轮都在变，
     * 拿它做去重等于每轮都通知。
     * </p>
     */
    private String rejectSignature;

    /** 兼容既有三参调用点，等价于没有淘汰摘要 */
    public SearchAndPushSummary(boolean skipped, boolean seasonPushed, int episodesPushed) {
        this(skipped, seasonPushed, episodesPushed, null, null);
    }

    public static SearchAndPushSummary skip() {
        return new SearchAndPushSummary(true, false, 0);
    }

    public boolean anyPushed() {
        return seasonPushed || episodesPushed > 0;
    }
}
