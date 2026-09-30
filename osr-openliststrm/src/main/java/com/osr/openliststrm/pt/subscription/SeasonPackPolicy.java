package com.osr.openliststrm.pt.subscription;

/**
 * 订阅级的季包策略：补搜时季包与单集谁先上（{@code pt_subscription.season_pack_policy}）。
 * <p>
 * 只影响<b>补搜</b>（{@code SearchSupplementService#searchAndPushMissing}）里的先后顺序，
 * 季包在任何策略下都保留兜底资格——「候选池里没有精确的单集」时一个整季包仍远比什么都不下强。
 * RSS 认领不受影响：RSS 里新出现的季包是站上真实发布的新资源，不存在「反复推同一批」的问题。
 * </p>
 *
 * @author Jack
 */
public enum SeasonPackPolicy {

    /** 按 {@code pt.search.season-pack-min-missing} 判：缺得多先试季包，缺得少先试单集 */
    AUTO,

    /** 用户手动设为单集优先。长篇动画、已知季包都是半季切法的剧直接设这个，省掉第一次白跑 */
    EPISODE,

    /**
     * 系统自动转成的单集优先：这条订阅推过的季包被下载器文件列表证实不含（或不全含）目标集。
     * <p>
     * 与 {@link #EPISODE} 分开存，是为了页面上能说清「为什么是单集优先」——用户没设过却变了，
     * 不写明来源会被当成 bug。<b>不会自己清掉</b>：换季在本系统里是新建一条订阅，
     * 同一条订阅的季包切法不会变好；自动恢复只会绕回「推包 → 包里没有 → 退回」的老路。
     * 用户在卡片上改回「季包优先」时才清。
     * </p>
     */
    EPISODE_LEARNED;

    /** 库里的值翻成枚举；null、空串与认不出的值一律按 {@link #AUTO}（存量行与默认值同义） */
    public static SeasonPackPolicy of(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        for (SeasonPackPolicy policy : values()) {
            if (policy.name().equals(value)) {
                return policy;
            }
        }
        return AUTO;
    }

    /** 是否不论缺几集都先单集 */
    public boolean episodeFirst() {
        return this != AUTO;
    }
}
