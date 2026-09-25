package com.osr.openliststrm.chat;

import java.util.function.Consumer;

/**
 * 发指令的人，由各渠道在调用 {@link PtChatCommandService} 前解析好。
 *
 * @param sessionKey  多轮会话的键，带渠道前缀（{@code wecom:zhangsan}、{@code tg:123456}）
 * @param sysUserId   对应的 OSR 用户，决定能看到哪些订阅、新建订阅归属谁
 * @param accountText 「我的账号」开头那几行，由渠道给出（企微 UserId / TG 用户 id 各不相同）
 * @param laterReply  事后补发一条消息给此人，给「补搜」这类要跑几分钟的指令回结果用
 */
public record ChatUser(String sessionKey, Long sysUserId, String accountText, Consumer<String> laterReply) {
}
