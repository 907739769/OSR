package com.osr.openliststrm.tg;

import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.chat.ChatReply;
import com.osr.openliststrm.chat.ChatUser;
import com.osr.openliststrm.chat.PtChatCommandService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.telegram.abilitybots.api.sender.MessageSender;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.MaybeInaccessibleMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Telegram 上的 PT 订阅指令：把斜杠命令、纯文本、按钮回调都还原成一条指令文本，
 * 交给与企业微信共用的 {@link PtChatCommandService}，再把回复（含内联按钮）发回去。
 * <p>
 * 不是 Spring bean：与 {@link StrmBot} 同生命周期，由它在构造时 new 出来。
 *
 * @author Jack
 */
@Slf4j
class TgPtCommandHandler {

    /** PT 按钮回调数据的前缀，用来与将来别的按钮区分 */
    static final String CALLBACK_PREFIX = "pt|";

    /** Telegram 对 callback_data 的硬上限（字节），超出整条消息都会被拒 */
    private static final int CALLBACK_DATA_MAX_BYTES = 64;

    /**
     * Bot 只接受配置里那一个 Telegram 用户（{@code creatorId}），他就是这套系统的管理员，
     * 映射到 OSR 超级管理员：能看全部订阅，从 TG 建的订阅也归在管理员名下。
     */
    private static final long TG_SYS_USER_ID = 1L;

    private final MessageSender sender;
    private final long creatorId;

    TgPtCommandHandler(MessageSender sender, long creatorId) {
        this.sender = sender;
        this.creatorId = creatorId;
    }

    /** 处理一条指令文本（斜杠命令已由调用方翻成中文指令，纯文本原样传入） */
    void handle(long chatId, String text) {
        try {
            ThreadTraceIdUtil.initTraceId();
            send(chatId, service().handle(user(chatId), text));
        } finally {
            MDC.clear();
        }
    }

    /** 处理按钮点击：先应答回调（否则按钮上的转圈一直不停），再按指令文本处理 */
    void handleCallback(CallbackQuery query) {
        try {
            ThreadTraceIdUtil.initTraceId();
            // 回调不走 AbilityBot 的 privacy 校验，得自己拦：按钮所在的消息可能被转发给别人
            if (query.getFrom() == null || query.getFrom().getId() != creatorId) {
                answer(query, "无权操作");
                return;
            }
            answer(query, null);
            MaybeInaccessibleMessage message = query.getMessage();
            if (message == null) {
                return;
            }
            String command = query.getData().substring(CALLBACK_PREFIX.length());
            if (command.startsWith("#")) {
                // 选片/选季是一次性的：选完就把这组按钮收起来，免得回头再点出一条重复订阅
                clearKeyboard(message);
            }
            send(message.getChatId(), service().handle(user(message.getChatId()), command));
        } finally {
            MDC.clear();
        }
    }

    static boolean isPtCallback(CallbackQuery query) {
        return query != null && query.getData() != null && query.getData().startsWith(CALLBACK_PREFIX);
    }

    private ChatUser user(long chatId) {
        String accountText = "Telegram 用户 ID：" + chatId + "\nOSR 账号：管理员（Bot 只接受配置的管理员）";
        return new ChatUser("tg:" + chatId, TG_SYS_USER_ID, accountText, later -> send(chatId, ChatReply.of(later)));
    }

    private void send(long chatId, ChatReply reply) {
        // 不设 parseMode：正文里有剧名、种子标题等任意字符，按纯文本发最不容易被转义问题炸掉
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(reply.text())
                .replyMarkup(keyboard(reply))
                .build();
        try {
            sender.execute(message);
        } catch (TelegramApiException e) {
            log.warn("Telegram 回复发送失败，chatId={}：{}", chatId, e.getMessage(), e);
        }
    }

    /** 把回复里的按钮转成内联键盘；没有按钮时返回 null（不带键盘） */
    static InlineKeyboardMarkup keyboard(ChatReply reply) {
        return keyboard(reply.buttons());
    }

    /** 按钮行转内联键盘，通知上的快捷操作（{@code TgSendMsg}）也走这里，回调前缀与超长处理只有一份 */
    static InlineKeyboardMarkup keyboard(List<List<ChatReply.Button>> buttonRows) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        for (List<ChatReply.Button> row : buttonRows) {
            List<InlineKeyboardButton> keys = new ArrayList<>();
            for (ChatReply.Button button : row) {
                String data = CALLBACK_PREFIX + button.command();
                // 超长的按钮宁可不给：正文里有同样的手打指令，而一个超长按钮会让整条消息发送失败
                if (data.getBytes(StandardCharsets.UTF_8).length > CALLBACK_DATA_MAX_BYTES) {
                    log.warn("按钮回调数据超过 {} 字节，已省略该按钮：{}", CALLBACK_DATA_MAX_BYTES, button.command());
                    continue;
                }
                keys.add(InlineKeyboardButton.builder().text(button.label()).callbackData(data).build());
            }
            if (!keys.isEmpty()) {
                rows.add(keys);
            }
        }
        return rows.isEmpty() ? null : InlineKeyboardMarkup.builder().keyboard(rows).build();
    }

    private void answer(CallbackQuery query, String text) {
        try {
            sender.execute(AnswerCallbackQuery.builder().callbackQueryId(query.getId()).text(text).build());
        } catch (TelegramApiException e) {
            // 应答失败只影响按钮上的转圈动画，不影响指令本身
            log.debug("应答 Telegram 按钮回调失败：{}", e.getMessage());
        }
    }

    private void clearKeyboard(MaybeInaccessibleMessage message) {
        try {
            sender.execute(EditMessageReplyMarkup.builder()
                    .chatId(message.getChatId())
                    .messageId(message.getMessageId())
                    .build());
        } catch (TelegramApiException e) {
            // 收不起来也无妨：旧按钮再点会带着过期的会话 id，PtChatCommandService 会认出来
            log.debug("收起 Telegram 选择按钮失败：{}", e.getMessage());
        }
    }

    private static PtChatCommandService service() {
        return SpringUtils.getBean(PtChatCommandService.class);
    }
}
