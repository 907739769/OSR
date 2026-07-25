package com.ruoyi.openliststrm.notify;

import com.ruoyi.openliststrm.config.OpenlistConfig;
import com.ruoyi.openliststrm.tg.TgSendMsg;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Telegram 通知渠道，承接原 {@code TgHelper} 的逻辑：token/userId 均已配置才发送，
 * {@link TgSendMsg} 实例按 token/userId 缓存，配置变化时重建。
 * <p>
 * 与原 {@code TgHelper} 的差异：构造函数直接注入 {@link OpenlistConfig}（本类是
 * {@code @Component}，走正常 Spring 依赖注入，不再需要 {@code SpringUtils.getBean()}
 * 这个只有纯静态类才需要的绕行写法）；缓存字段从 {@code static volatile} 改为普通实例
 * 字段（本类本身是单例 Bean，不再需要"整个 JVM 只有一份"的静态缓存语义）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class TgNotifier implements INotifier {

    private final OpenlistConfig config;

    /** 包内可见，供 {@code TgNotifierTest} 侧面验证 no-op 分支未构造真实 Bot 实例 */
    volatile TgSendMsg cachedBot;
    private volatile String cachedToken;
    private volatile String cachedUserId;

    public TgNotifier(OpenlistConfig config) {
        this.config = config;
    }

    @Override
    public void send(String message) {
        String token = config.getOpenListTgToken();
        String userId = config.getOpenListTgUserId();
        if (StringUtils.isAnyBlank(token, userId)) {
            return;
        }
        try {
            getBot(token, userId).sendMsg(message);
        } catch (Exception e) {
            log.warn("Telegram 通知发送失败：{}", e.getMessage());
        }
    }

    /** token/userId 在后台配置变更后会重建实例，其余情况复用缓存 */
    private TgSendMsg getBot(String token, String userId) {
        TgSendMsg bot = cachedBot;
        if (bot != null && token.equals(cachedToken) && userId.equals(cachedUserId)) {
            return bot;
        }
        synchronized (this) {
            if (cachedBot == null || !token.equals(cachedToken) || !userId.equals(cachedUserId)) {
                cachedBot = new TgSendMsg(token, userId);
                cachedToken = token;
                cachedUserId = userId;
            }
            return cachedBot;
        }
    }
}
