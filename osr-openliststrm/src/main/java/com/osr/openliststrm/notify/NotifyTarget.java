package com.osr.openliststrm.notify;

/**
 * 通知投递目标。用于把「这条通知是谁的事」告诉渠道，让支持分人投递的渠道
 * （目前只有企业微信）只发给相关的人，而不是把所有人的下载动态推给所有人。
 * <p>
 * 渠道<b>可以忽略</b>本对象：Telegram / Webhook 都只有一个全局收件人，
 * {@link INotifier#send(NotificationType, String, NotifyTarget)} 的默认实现直接退化为广播。
 * <p>
 * {@code ownerUserId} 为 null 表示「无归属」——系统级告警，或归属人未知的订阅
 * （历史数据的 owner_user_id 全是 NULL）。这种通知按广播处理，发给渠道的默认接收人。
 *
 * @param ownerUserId 归属人的 OSR 用户ID(sys_user.user_id)，null 表示无归属
 * @author Jack
 */
public record NotifyTarget(Long ownerUserId) {

    /** 无归属：发给渠道配置的默认接收人 */
    public static final NotifyTarget BROADCAST = new NotifyTarget(null);

    /**
     * 定向到某个 OSR 用户。传 null 等价于 {@link #BROADCAST}——调用方常常是
     * {@code NotifyTarget.owner(sub.getOwnerUserId())} 这种写法，订阅无归属时
     * 不该逼调用方自己判空。
     */
    public static NotifyTarget owner(Long ownerUserId) {
        return ownerUserId == null ? BROADCAST : new NotifyTarget(ownerUserId);
    }

    /** 是否是定向通知（有明确归属人） */
    public boolean isDirected() {
        return ownerUserId != null;
    }
}
