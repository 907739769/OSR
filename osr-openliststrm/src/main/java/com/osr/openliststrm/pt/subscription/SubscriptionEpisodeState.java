package com.osr.openliststrm.pt.subscription;

/**
 * pt_subscription_episode.state 取值的唯一权威定义。之前 {@code SubscriptionService}、
 * {@code SubscriptionEngine}、{@code AutoSearchService} 里各自维护一份同值的私有字符串常量，
 * 拼写漂移不会被编译期发现，统一到这里后其余类只引用 {@link #value()}。
 *
 * @author Jack
 */
public enum SubscriptionEpisodeState {

    /** 尚未匹配到资源 */
    MISSING("MISSING"),
    /** 已占位并推送给下载器，等待完成 */
    IN_FLIGHT("IN_FLIGHT"),
    /** 已在媒体服务器确认入库 */
    IN_LIBRARY("IN_LIBRARY"),
    /** 连续失败达到熔断阈值，不再被 RSS/补搜自动捞回，需人工在下载记录管理页重试 */
    BLOCKED("BLOCKED");

    private final String value;

    SubscriptionEpisodeState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
