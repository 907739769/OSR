package com.osr.openliststrm.notify;

/**
 * 通知类型：用于渠道按类型过滤（见 {@link TgNotifier}/{@link WebhookNotifier} 的
 * {@code openlist.notify.*.types} 配置），以便后续不同类型路由到不同渠道。
 *
 * @author Jack
 */
public enum NotificationType {

    /** 未分类的历史通知（索引器告警、复制任务失败/超时等旧调用点），过渡期默认值 */
    GENERAL("系统告警"),
    /** 订阅命中候选种子，已推送下载器 */
    SUBSCRIPTION_HIT("订阅命中"),
    /** 下载完成 */
    DOWNLOAD_COMPLETE("下载完成"),
    /** 下载失败 */
    DOWNLOAD_FAILED("下载失败"),
    /** Emby/Jellyfin 对账检测到新集数入库 */
    EMBY_LIBRARY_SYNC("媒体库入库");

    private final String label;

    NotificationType(String label) {
        this.label = label;
    }

    /** 页面展示名。放在枚举上而不是前端字典：新增类型时只改一处，前端自动跟上 */
    public String getLabel() {
        return label;
    }
}
