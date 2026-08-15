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
            apiClient.sendText(resolveToUser(target), toPlainText(message));
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
     * 把按 Telegram HTML parse_mode 写的文案还原成纯文本，供不解析 HTML 的渠道
     * （企微 / Bark / Gotify）使用。两步缺一不可：
     * <ol>
     *   <li><b>去标签</b>：文案里 {@code <b>复制任务失败</b>} 这类字面量标签，原样发过去
     *       用户看到的是一堆尖括号。只处理简单标签即可——这些文案都是代码里写死的字面量，
     *       不存在属性、嵌套引号里带 {@code >} 之类需要正经解析器的情况。</li>
     *   <li><b>解实体</b>：所有动态内容（种子标题、剧名、索引器名）都先过了
     *       {@link com.osr.common.utils.StringUtils#escapeHtml}，{@code & < >} 已经变成实体。
     *       TG 那边由 parse_mode 还原，这些渠道没人还原，于是 {@code Tom & Jerry} 会显示成
     *       {@code Tom &amp; Jerry}——而 {@code &} 在 PT 种子标题里相当常见（片名、组名皆有）。</li>
     * </ol>
     * <p>
     * <b>两步的先后顺序不能换</b>：先去标签、后解实体。反过来的话，动态内容里字面的
     * {@code &lt;b&gt;} 会先被还原成 {@code <b>}，紧接着被去标签那步当成真标签删掉——
     * 用户输入的文本凭空少一截。
     * </p>
     * <p>
     * 解实体这步内部也是有序的：{@code escapeHtml} 先转 {@code &} 再转 {@code < >}，
     * 还原就必须先 {@code < >} 后 {@code &}，否则 {@code &amp;lt;} 会被解成 {@code <}，
     * 而它本该还原成字面的 {@code &lt;}。
     * </p>
     */
    static String toPlainText(String message) {
        String text = message;
        if (text.indexOf('<') >= 0) {
            text = text.replaceAll("</?[a-zA-Z][a-zA-Z0-9]*\\s*/?>", "");
        }
        if (text.indexOf('&') < 0) {
            return text;
        }
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
