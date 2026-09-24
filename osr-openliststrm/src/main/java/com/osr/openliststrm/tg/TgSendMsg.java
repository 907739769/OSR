package com.osr.openliststrm.tg;

import com.osr.openliststrm.chat.ChatReply;
import com.osr.openliststrm.notify.NotifyAction;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author Jack
 * @Date 2024/6/4 17:17
 * @Version 1.0.0
 */
@Slf4j
public class TgSendMsg extends TelegramLongPollingBot {

    private final String botToken;
    private final String adminUserId;

    public TgSendMsg(String botToken, String adminUserId) {
        this.adminUserId = adminUserId;
        this.botToken = botToken;
    }

    public void sendMsg(String msg) {
        sendMsg(msg, List.of());
    }

    /**
     * 带快捷操作的通知：操作渲染成内联按钮，一行两个。按钮的回调由 {@link StrmBot} 接收
     * （两者用的是同一个 Bot Token，按钮点击的 update 由 StrmBot 的长轮询收到），
     * 执行走 {@code TgPtCommandHandler}，与在对话里手打指令同一条路。
     */
    public void sendMsg(String msg, List<NotifyAction> actions) {
        if (StringUtils.isBlank(adminUserId) || StringUtils.isBlank(botToken)) {
            return;
        }
        try {
            sendMsgOrThrow(msg, actions);
        } catch (TelegramApiException e) {
            // 不打 msg 本身：通知正文里带着剧名、路径等内容，而且可能很长。
            log.error("Telegram 消息发送失败, chatId={}：{}", adminUserId, e.getMessage(), e);
        }
    }

    /**
     * 与 {@link #sendMsg} 相同，但失败时把异常抛给调用方——配置页的「发送测试」
     * 要把失败原因（chat not found / Unauthorized）原样带回给用户。
     */
    public void sendMsgOrThrow(String msg) throws TelegramApiException {
        sendMsgOrThrow(msg, List.of());
    }

    private void sendMsgOrThrow(String msg, List<NotifyAction> actions) throws TelegramApiException {
        SendMessage message = new SendMessage();
        message.setChatId(adminUserId);
        message.setText(msg);
        message.setParseMode(ParseMode.HTML); // HTML 解析模式：仅 & < > 需要转义，比 MarkdownV2 更不容易被动态内容炸掉
        if (actions != null && !actions.isEmpty()) {
            message.setReplyMarkup(TgPtCommandHandler.keyboard(toRows(actions)));
        }
        execute(message);
    }

    /** 一行放两个按钮：通知里的操作一般两三个，全挤一行在手机上会被截断 */
    private static List<List<ChatReply.Button>> toRows(List<NotifyAction> actions) {
        List<List<ChatReply.Button>> rows = new ArrayList<>();
        for (int i = 0; i < actions.size(); i += 2) {
            List<ChatReply.Button> row = new ArrayList<>();
            for (NotifyAction action : actions.subList(i, Math.min(i + 2, actions.size()))) {
                row.add(new ChatReply.Button(action.label(), action.command()));
            }
            rows.add(row);
        }
        return rows;
    }

    @Override
    public String getBotUsername() {
        return "";
    }

    @Override
    public String getBotToken() {
        return this.botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {

    }
}
