package com.osr.openliststrm.notify;

/**
 * 通知渠道抽象。新增一个通知渠道时，只需新增一个实现本接口的 {@code @Component}，
 * Spring 会自动被 {@link NotifierManager} 收集，不需要改动任何分发逻辑或调用点。
 * <p>
 * 契约：实现类必须自行判断"是否已配置"（未配置时 no-op，不发送）以及"该类型是否被
 * 本渠道启用"（见各实现类的 {@code openlist.notify.*.types} 配置），
 * 且绝不能向外抛出异常——发送失败只记录日志，不能影响调用方，也不能影响其余渠道。
 *
 * @author Jack
 */
public interface INotifier {

    /**
     * 发送一条通知消息。
     * 实现类必须保证：未配置（如 token/url 为空）或该类型未被本渠道启用时静默跳过；
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
}
