package com.osr.openliststrm.notify;

import java.util.List;

/**
 * 通知渠道抽象。新增一个通知渠道时，只需新增一个实现本接口的 {@code @Component}，
 * Spring 会自动被 {@link NotifierManager} 收集，不需要改动任何分发逻辑或调用点。
 * <p>
 * 契约：实现类必须自行判断"是否已配置"（未配置时 no-op，不发送），且 {@code send}
 * 绝不能向外抛出异常——发送失败只记录日志，不能影响调用方，也不能影响其余渠道。
 * "该类型要不要走本渠道、发给谁"<b>不归渠道管</b>，由 {@link NotifierManager} 查
 * {@code notify_route} 决定（早期各渠道自己读的 {@code openlist.notify.*.types} 已删除）。
 *
 * @author Jack
 */
public interface INotifier {

    /**
     * 渠道标识，与 {@code notify_route.channel} 取值一致，如 TELEGRAM / WEBHOOK / WECOM。
     * <p>
     * 分发器用它查路由表决定「这个类型要不要走这个渠道、发给谁」，
     * 因此新增渠道时这个值一旦发布就不能再改——改了等于把用户已有的路由配置全丢了。
     * </p>
     */
    String channelKey();

    /** 页面上展示的渠道名 */
    String displayName();

    /**
     * 本渠道是否支持按人投递。
     * <p>
     * Telegram 只有一个 chat id、Webhook 只有一个 URL，它们无论如何都只能发到同一个地方，
     * 返回 false。配置页据此对这些渠道<b>不展示</b>收件人选项——给出一个不生效的开关，
     * 比缺少这个功能更糟。
     * </p>
     */
    default boolean supportsDirectDelivery() {
        return false;
    }

    /** 本渠道是否已完成配置（token/URL 等齐备）。配置页据此提示「尚未配置」 */
    boolean isConfigured();

    /**
     * 发送一条通知消息。
     * 实现类必须保证：未配置（如 token/url 为空）时静默跳过；
     * 发送失败时内部吞掉异常，只记录 warn 日志。
     */
    void send(NotificationType type, String message);

    /**
     * 发送一条<b>带投递目标</b>的通知消息。默认实现忽略 {@code target} 退化为广播，
     * 因为 Telegram / Webhook 都只有一个全局收件人，无从分人。
     * 支持分人投递的渠道（企业微信）覆写本方法。
     * <p>
     * 契约与 {@link #send(NotificationType, String)} 相同：不得抛异常。
     *
     * @param target 投递目标，null 按 {@link NotifyTarget#BROADCAST} 处理
     */
    default void send(NotificationType type, String message, NotifyTarget target) {
        send(type, message);
    }

    /**
     * 带快捷操作的发送（见 {@link NotifyAction}）。能回复聊天指令的渠道覆写本方法，
     * 默认实现丢掉操作、照常发送正文。只有 {@code actions} 非空时分发器才会调到这里。
     * <p>
     * 契约同上：不得抛异常。
     */
    default void send(NotificationType type, String message, NotifyTarget target, List<NotifyAction> actions) {
        send(type, message, target);
    }

    /**
     * 发一条测试消息，<b>如实返回结果</b>，供配置页的「发送测试」使用。
     * <p>
     * 与 {@link #send} 的区别只在失败时：{@code send} 吞掉失败只记日志（通知不能影响业务），
     * 这里要把原因带回给页面——「发送失败」四个字不帮用户判断是 token 错了还是网络不通。
     * 调用前已确认 {@link #isConfigured()}。支持分人的渠道发给默认接收人。
     * </p>
     *
     * @return null 表示发送成功，否则是给用户看的失败原因
     */
    String sendTest(String message);
}
