package com.osr.openliststrm.notify;

import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;
import com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService;
import com.osr.openliststrm.wecom.WeComApiClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 企业微信通知渠道。与 Telegram/Webhook 的区别是<b>支持按人投递</b>：
 * 带归属人的通知（订阅命中、下载完成等）只发给该订阅归属人绑定的企微成员，
 * 无归属的通知（系统告警、历史订阅）才发给配置的默认接收人。
 * <p>
 * 归属人没有绑定企微、或绑定被停用时，退回默认接收人而不是丢弃：通知丢了没人知道，
 * 多发给管理员至少还有人能看见并去补绑定。
 *
 * @author Jack
 */
@Slf4j
@Component
public class WeComNotifier implements INotifier {

    private final OpenlistConfig config;
    private final WeComApiClient apiClient;
    private final IWecomUserPlusService wecomUserService;

    public WeComNotifier(OpenlistConfig config, WeComApiClient apiClient, IWecomUserPlusService wecomUserService) {
        this.config = config;
        this.apiClient = apiClient;
        this.wecomUserService = wecomUserService;
    }

    @Override
    public void send(NotificationType type, String message) {
        send(type, message, NotifyTarget.BROADCAST);
    }

    @Override
    public void send(NotificationType type, String message, NotifyTarget target) {
        if (!apiClient.isConfigured() || StringUtils.isBlank(message) || !isTypeEnabled(type)) {
            return;
        }
        try {
            apiClient.sendText(resolveToUser(target), stripHtmlTags(message));
        } catch (Exception e) {
            log.warn("企业微信通知发送失败：{}", e.getMessage());
        }
    }

    /**
     * 把投递目标翻译成企微的 touser 参数（多个成员用 | 分隔）。
     * 无归属、归属人未绑定企微、或绑定全部停用时，一律回退默认接收人。
     */
    private String resolveToUser(NotifyTarget target) {
        if (target == null || !target.isDirected()) {
            return config.getWeComToUser();
        }
        List<WecomUserPlus> binds = wecomUserService.listEnabledBySysUserId(target.ownerUserId());
        String toUser = binds.stream()
                .map(WecomUserPlus::getWecomUserid)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .collect(Collectors.joining("|"));
        if (StringUtils.isBlank(toUser)) {
            log.debug("OSR 用户[{}]未绑定可用的企微成员，本条通知回退给默认接收人", target.ownerUserId());
            return config.getWeComToUser();
        }
        return toUser;
    }

    /** {@code openlist.notify.wecom.types} 留空＝不过滤，所有类型都发（与其余渠道一致） */
    private boolean isTypeEnabled(NotificationType type) {
        String types = config.getNotifyWeComTypes();
        if (StringUtils.isBlank(types)) {
            return true;
        }
        return Arrays.stream(types.split(",")).map(String::trim).anyMatch(t -> t.equalsIgnoreCase(type.name()));
    }

    /**
     * 去掉消息里的 HTML 标签。历史通知文案是按 Telegram 的 HTML parse_mode 写的
     * （形如 {@code <b>复制任务失败</b>}），企微 text 消息不解析 HTML，
     * 原样发过去用户会看到一堆尖括号标签。
     * <p>
     * 只处理简单标签即可——这些文案都是代码里写死的字面量，不存在属性、嵌套引号里带
     * {@code >} 之类需要正经解析器的情况。
     */
    static String stripHtmlTags(String message) {
        if (message.indexOf('<') < 0) {
            return message;
        }
        return message.replaceAll("</?[a-zA-Z][a-zA-Z0-9]*\\s*/?>", "");
    }
}
