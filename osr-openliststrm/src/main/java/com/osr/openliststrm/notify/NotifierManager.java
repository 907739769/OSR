package com.osr.openliststrm.notify;

import com.osr.openliststrm.mybatisplus.domain.NotifyRoutePlus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知渠道分发器：构造注入 {@code List<INotifier>}，Spring 会自动收集所有 {@link INotifier}
 * 实现类装配进这个列表（各实现类只需标注 {@code @Component}，不需要手工注册）。
 * <p>
 * 「这个类型要不要走这个渠道、发给谁」由 {@code notify_route} 决定（见 {@link NotifyRouteService}），
 * 渠道实现只管「怎么发」。改造前这个判断散在每个渠道自己的 {@code isTypeEnabled} 里，
 * 渠道一多就没法统一配置，也没有「发给谁」这一维。
 * </p>
 * <p>
 * 每个渠道单独 try/catch：任一渠道抛异常只记录 warn 日志、不影响其余渠道继续发送，
 * 也不会让本方法本身抛出异常——调用方不需要关心通知是否发送成功。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class NotifierManager {

    private final List<INotifier> notifiers;
    private final NotifyRouteService routeService;

    public NotifierManager(List<INotifier> notifiers, NotifyRouteService routeService) {
        this.notifiers = notifiers;
        this.routeService = routeService;
    }

    public void send(NotificationType type, String message) {
        send(type, message, NotifyTarget.BROADCAST);
    }

    /**
     * 带投递目标的分发。目标会先经过路由的收件人范围改写，再交给渠道。
     *
     * @param target 调用方给出的「这条通知是谁的事」，null 按广播处理
     */
    public void send(NotificationType type, String message, NotifyTarget target) {
        NotifyTarget origin = target == null ? NotifyTarget.BROADCAST : target;
        for (INotifier notifier : notifiers) {
            try {
                NotifyRoutePlus route = routeService.find(type, notifier.channelKey());
                // 路由缺失按「发送」处理，理由见 NotifyRouteService#find
                if (route != null && !route.enabledOn()) {
                    continue;
                }
                notifier.send(type, message, applyScope(route, origin, notifier));
            } catch (Exception e) {
                log.warn("通知渠道[{}]发送失败：{}", notifier.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * 按路由的收件人范围改写投递目标。
     * <p>
     * 不支持分人的渠道（Telegram/Webhook）一律退化为广播——它们本来就只有一个接收人，
     * 假装尊重 scope 只会让日志和行为对不上。
     * </p>
     */
    static NotifyTarget applyScope(NotifyRoutePlus route, NotifyTarget origin, INotifier notifier) {
        if (!notifier.supportsDirectDelivery()) {
            return NotifyTarget.BROADCAST;
        }
        String scope = route == null ? null : route.getRecipientScope();
        if (scope == null) {
            return origin;
        }
        return switch (scope) {
            // 忽略归属，只发默认接收人
            case NotifyRoutePlus.SCOPE_ADMIN -> NotifyTarget.BROADCAST;
            // 只发归属人；无归属时 owner() 自己退化成广播，系统级告警因此不会丢
            case NotifyRoutePlus.SCOPE_OWNER -> NotifyTarget.owner(origin.ownerUserId());
            case NotifyRoutePlus.SCOPE_BOTH -> NotifyTarget.ownerAndDefault(origin.ownerUserId());
            default -> origin;
        };
    }
}
