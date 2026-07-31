package com.osr.openliststrm.tg;

import com.osr.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

/**
 * @Author Jack
 * @Date 2025/7/20 17:44
 * @Version 1.0.0
 */
@Slf4j
@Component
public class TgBotRegister implements CommandLineRunner {

    @Autowired
    private OpenlistConfig config;

    @Override
    public void run(String... args) throws Exception {
        if (StringUtils.isAnyBlank(config.getOpenListTgToken(), config.getOpenListTgUserId())) {
            log.info("tg参数未设置，不初始化tg机器人");
            return;
        }
        TelegramBotsApi telegramBotsApi;
        try {
            telegramBotsApi = new TelegramBotsApi(DefaultBotSession.class);
        } catch (TelegramApiException e) {
            log.error("初始化TelegramBotsApi失败", e);
            return;
        }
        try {
            StrmBot bot = new StrmBot(config.getOpenListTgToken(), config.getOpenListTgUserId());
            telegramBotsApi.registerBot(bot);
        } catch (TelegramApiException e) {
            log.error("注册Telegram Bot失败", e);
        }
    }
}
