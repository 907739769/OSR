package com.osr.openliststrm.tg;

import com.osr.common.core.domain.event.SysConfigChangedEvent;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.config.OpenlistConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.BotSession;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/**
 * Telegram 机器人（长轮询收指令那一侧）的生命周期管理：启动时注册，
 * {@code openlist.tg.*} 参数变更后自动停掉旧会话、按新配置重新注册，不用重启后端。
 * <p>
 * 发通知那一侧（{@code TgNotifier}）本来就每次读配置，不归这里管。
 *
 * @Author Jack
 * @Date 2025/7/20 17:44
 * @Version 1.0.0
 */
@Slf4j
@Component
public class TgBotRegister implements CommandLineRunner {

    static final String CONFIG_PREFIX = "openlist.tg.";

    /**
     * 收到变更后延迟一会儿再重载：备份恢复会逐条写 token、userId，
     * 合并成一次，避免中间拿「新 token + 旧 userId」起一个错的会话；
     * 也把注册时的网络请求挪出保存配置的那个 HTTP 请求。
     */
    static final Duration RELOAD_DELAY = Duration.ofSeconds(2);

    private final OpenlistConfig config;
    private final TaskScheduler scheduler;

    /** 串行化启停，保护下面三个字段 */
    private final Object lifecycleLock = new Object();
    private BotSession session;
    private String runningToken;
    private String runningUserId;

    private final Object scheduleLock = new Object();
    private ScheduledFuture<?> pendingReload;

    public TgBotRegister(OpenlistConfig config, @Qualifier("virtualScheduledExecutor") TaskScheduler scheduler) {
        this.config = config;
        this.scheduler = scheduler;
    }

    @Override
    public void run(String... args) {
        if (StringUtils.isAnyBlank(config.getOpenListTgToken(), config.getOpenListTgUserId())) {
            log.info("tg参数未设置，不初始化tg机器人");
            return;
        }
        reload();
    }

    @EventListener
    public void onConfigChanged(SysConfigChangedEvent event) {
        if (event.affectsPrefix(CONFIG_PREFIX)) {
            scheduleReload();
        }
    }

    void scheduleReload() {
        synchronized (scheduleLock) {
            if (pendingReload != null) {
                pendingReload.cancel(false);
            }
            pendingReload = scheduler.schedule(Threads.wrap(this::reload), Instant.now().plus(RELOAD_DELAY));
        }
    }

    /** 按当前配置对齐机器人状态；配置与正在运行的一致时什么都不做 */
    void reload() {
        String token = StringUtils.trimToNull(config.getOpenListTgToken());
        String userId = StringUtils.trimToNull(config.getOpenListTgUserId());
        synchronized (lifecycleLock) {
            if (Objects.equals(token, runningToken) && Objects.equals(userId, runningUserId)) {
                return;
            }
            boolean hadSession = session != null;
            stopCurrent();
            if (token == null || userId == null) {
                if (hadSession) {
                    log.info("tg参数已清空，Telegram 机器人已停止");
                }
                return;
            }
            try {
                session = launch(token, userId);
                runningToken = token;
                runningUserId = userId;
                log.info("Telegram 机器人已{}，管理员 userId={}", hadSession ? "按新配置重启" : "启动", userId);
            } catch (Exception e) {
                // 不记下 running*：用户改对配置、或原样再保存一次都会重试
                log.error("注册Telegram Bot失败, userId={}：{}", userId, e.getMessage(), e);
            }
        }
    }

    /**
     * 构造并注册机器人。注册失败时负责关掉已构造的实例：StrmBot 一构造就打开了
     * 名为 "bot" 的 MapDB 文件，不关的话下次重试会因文件被锁而失败。包内可见供测试替换。
     */
    BotSession launch(String token, String userId) throws TelegramApiException {
        StrmBot bot = new StrmBot(token, userId);
        try {
            return new TelegramBotsApi(DefaultBotSession.class).registerBot(bot);
        } catch (TelegramApiException | RuntimeException e) {
            bot.onClosing();
            throw e;
        }
    }

    /**
     * 停掉当前会话。{@code stop()} 会回调 {@link StrmBot#onClosing()} 关本地库；
     * 旧会话挂着的 getUpdates 长轮询要等超时才真正返回，token 不变时新会话
     * 在这段时间里可能拿到 409 Conflict，属于正常现象。
     */
    private void stopCurrent() {
        if (session != null) {
            try {
                if (session.isRunning()) {
                    session.stop();
                }
            } catch (Exception e) {
                log.warn("停止 Telegram 机器人会话失败：{}", e.getMessage(), e);
            }
        }
        session = null;
        runningToken = null;
        runningUserId = null;
    }

    @PreDestroy
    public void shutdown() {
        synchronized (scheduleLock) {
            if (pendingReload != null) {
                pendingReload.cancel(false);
            }
        }
        synchronized (lifecycleLock) {
            stopCurrent();
        }
    }
}
