package com.osr.openliststrm.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知渠道分发器：构造注入 {@code List<INotifier>}，Spring 会自动收集所有 {@link INotifier}
 * 实现类装配进这个列表（各实现类只需标注 {@code @Component}，不需要手工注册）。
 * <p>
 * {@link #send(NotificationType, String)} 对每个渠道单独 try/catch：任一渠道抛异常只记录
 * warn 日志、不影响其余渠道继续发送，也不会让本方法本身抛出异常——调用方（{@code TgHelper}
 * 门面以及未来直接注入本类的新代码）因此不需要关心通知是否发送成功。类型是否路由到某个渠道
 * 由渠道实现自己判断（见各 {@link INotifier} 实现类）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class NotifierManager {

    private final List<INotifier> notifiers;

    public NotifierManager(List<INotifier> notifiers) {
        this.notifiers = notifiers;
    }

    public void send(NotificationType type, String message) {
        send(type, message, NotifyTarget.BROADCAST);
    }

    /**
     * 带投递目标的分发。支持分人投递的渠道会只发给 {@code target} 指向的人，
     * 其余渠道按广播处理（见 {@link INotifier#send(NotificationType, String, NotifyTarget)} 的默认实现）。
     *
     * @param target 投递目标，null 按广播处理
     */
    public void send(NotificationType type, String message, NotifyTarget target) {
        NotifyTarget effective = target == null ? NotifyTarget.BROADCAST : target;
        for (INotifier notifier : notifiers) {
            try {
                notifier.send(type, message, effective);
            } catch (Exception e) {
                log.warn("通知渠道[{}]发送失败：{}", notifier.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
