package com.osr.openliststrm.notify;

import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;
import com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService;
import com.osr.openliststrm.wecom.WeComApiClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    public String channelKey() {
        return "WECOM";
    }

    @Override
    public String displayName() {
        return "企业微信";
    }

    /** 企微是目前唯一能按人投递的渠道：touser 支持用 | 分隔多个成员 */
    @Override
    public boolean supportsDirectDelivery() {
        return true;
    }

    @Override
    public boolean isConfigured() {
        return apiClient.isConfigured();
    }

    @Override
    public void send(NotificationType type, String message) {
        send(type, message, NotifyTarget.BROADCAST);
    }

    @Override
    public void send(NotificationType type, String message, NotifyTarget target) {
        if (!apiClient.isConfigured() || StringUtils.isBlank(message)) {
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
     * <p>
     * 无归属、归属人未绑定企微、或绑定全部停用时，一律回退默认接收人——
     * 归属人收不到不代表这条通知该消失。
     * </p>
     * <p>
     * {@code includeDefaultRecipient}（路由的 BOTH 档）会把默认接收人一并拼进 touser，
     * 归属人恰好就是默认接收人时靠 distinct 去重，不会在同一个会话里出现两条。
     * </p>
     */
    private String resolveToUser(NotifyTarget target) {
        if (target == null || !target.isDirected()) {
            return config.getWeComToUser();
        }
        List<WecomUserPlus> binds = wecomUserService.listEnabledBySysUserId(target.ownerUserId());
        Stream<String> owners = binds.stream()
                .map(WecomUserPlus::getWecomUserid)
                .filter(StringUtils::isNotBlank);
        if (target.includeDefaultRecipient()) {
            owners = Stream.concat(owners, Stream.of(config.getWeComToUser()).filter(StringUtils::isNotBlank));
        }
        String toUser = owners.distinct().collect(Collectors.joining("|"));
        if (StringUtils.isBlank(toUser)) {
            log.debug("OSR 用户[{}]未绑定可用的企微成员，本条通知回退给默认接收人", target.ownerUserId());
            return config.getWeComToUser();
        }
        return toUser;
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
