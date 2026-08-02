package com.osr.openliststrm.notify;

/**
 * 通知类型：用于渠道按类型过滤（见 {@link TgNotifier}/{@link WebhookNotifier} 的
 * {@code openlist.notify.*.types} 配置），以便后续不同类型路由到不同渠道。
 *
 * @author Jack
 */
public enum NotificationType {

    /** 未分类的历史通知（索引器告警、复制任务失败/超时等旧调用点），过渡期默认值 */
    GENERAL,
    /** 订阅命中候选种子，已推送下载器 */
    SUBSCRIPTION_HIT,
    /** 下载完成 */
    DOWNLOAD_COMPLETE,
    /** 下载失败 */
    DOWNLOAD_FAILED,
    /** Emby/Jellyfin 对账检测到新集数入库 */
    EMBY_LIBRARY_SYNC
}
