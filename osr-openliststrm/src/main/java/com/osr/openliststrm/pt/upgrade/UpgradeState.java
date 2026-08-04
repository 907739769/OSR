package com.osr.openliststrm.pt.upgrade;

/**
 * 一集的洗版评估结果，落库到 {@code pt_subscription_episode.upgrade_state}。
 * <p>
 * 列为 {@code null} 表示尚未评估过（老数据，或刚入库还没轮到扫描）。
 * 风格与 {@link com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState} 一致。
 * </p>
 *
 * @author Jack
 */
public enum UpgradeState {

    /** 有质量基线且尚未达到目标质量，参与洗版扫描 */
    PENDING("PENDING"),
    /** 已达到目标质量(cutoff)，不再参与扫描。没有这个终止态，每一集都会永远搜下去 */
    REACHED("REACHED"),
    /**
     * 没有质量基线，不参与洗版。
     * <p>
     * 典型场景：订阅创建时这一集就已经在 Emby 里了，{@code download_id} 为空，
     * OSR 不知道库里躺的是什么货色，也就无从判断"新的是不是更好"。
     * 盲目洗版可能把好版本换成差版本，宁可不动。
     * </p>
     */
    NO_BASELINE("NO_BASELINE");

    private final String value;

    UpgradeState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
