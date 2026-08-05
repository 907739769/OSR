package com.osr.openliststrm.helper;

import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.notify.NotifierManager;
import com.osr.openliststrm.notify.NotifyTarget;

/**
 * 兼容门面：历史遗留的静态调用入口。{@link #sendMsg(String)} 现在会转发给
 * {@link NotifierManager}，由其分发到所有已启用的通知渠道（不再只是 Telegram），
 * 类型固定为 {@link NotificationType#GENERAL}（未分类）。
 * <p>
 * 之所以保留这个静态方法而不是让调用方直接注入 {@link NotifierManager}，是因为
 * {@code DownloadTrackServiceTest}、{@code SearchSupplementServiceTest}、
 * {@code RssPollServiceTest} 三个测试文件里共 14 处
 * {@code Mockito.mockStatic(TgHelper.class)} 断言依赖这个类名和方法签名不变。
 * <p>
 * 新代码需要区分通知类型（以便渠道按类型路由）时用 {@link #sendMsg(NotificationType, String)}；
 * 不关心类型区分的旧调用点可以继续用 {@link #sendMsg(String)}。更推荐的方式仍是直接注入
 * {@link NotifierManager} 并调用其 {@code send(type, msg)}，这样新代码天然可测
 * （不需要 {@code mockStatic}）。
 *
 * @Author Jack
 * @Date 2025/7/20 18:49
 * @Version 1.0.0
 */
public class TgHelper {

    public static void sendMsg(String msg) {
        sendMsg(NotificationType.GENERAL, msg);
    }

    public static void sendMsg(NotificationType type, String msg) {
        SpringUtils.getBean(NotifierManager.class).send(type, msg);
    }

    /**
     * 带投递目标的发送：支持分人投递的渠道（企业微信）只发给 {@code target} 指向的人，
     * 其余渠道按广播处理。订阅相关的通知点用这个重载把订阅归属人带下去，
     * 这样 A 的订阅动态不会推到 B 的企微上。
     */
    public static void sendMsg(NotificationType type, String msg, NotifyTarget target) {
        SpringUtils.getBean(NotifierManager.class).send(type, msg, target);
    }

}
