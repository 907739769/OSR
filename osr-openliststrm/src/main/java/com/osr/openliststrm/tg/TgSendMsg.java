package com.osr.openliststrm.tg;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

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
        if (StringUtils.isBlank(adminUserId) || StringUtils.isBlank(botToken)) {
            return;
        }
        SendMessage message = new SendMessage();
        message.setChatId(adminUserId);
        message.setText(msg);
        message.setParseMode(ParseMode.HTML); // HTML 解析模式：仅 & < > 需要转义，比 MarkdownV2 更不容易被动态内容炸掉

        try {
            execute(message);
        } catch (TelegramApiException e) {
            // 不打 msg 本身：通知正文里带着剧名、路径等内容，而且可能很长。
            log.error("Telegram 消息发送失败, chatId={}", adminUserId, e);
        }
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
