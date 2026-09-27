package com.osr.openliststrm.tg;

import com.osr.common.core.domain.event.SysConfigChangedEvent;
import com.osr.openliststrm.config.OpenlistConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.BotSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TgBotRegisterTest {

    private OpenlistConfig config;
    private TaskScheduler scheduler;
    private FakeRegister register;

    /** 不真连 Telegram：launch 只记录调用并返回 mock 会话 */
    static class FakeRegister extends TgBotRegister {
        final List<String> launches = new ArrayList<>();
        final List<BotSession> sessions = new ArrayList<>();
        boolean failNext;

        FakeRegister(OpenlistConfig config, TaskScheduler scheduler) {
            super(config, scheduler);
        }

        @Override
        BotSession launch(String token, String userId) throws TelegramApiException {
            launches.add(token + "/" + userId);
            if (failNext) {
                failNext = false;
                throw new TelegramApiException("Unauthorized");
            }
            BotSession s = mock(BotSession.class);
            when(s.isRunning()).thenReturn(true);
            sessions.add(s);
            return s;
        }
    }

    @BeforeEach
    void setUp() {
        config = mock(OpenlistConfig.class);
        scheduler = mock(TaskScheduler.class);
        register = new FakeRegister(config, scheduler);
    }

    private void configure(String token, String userId) {
        when(config.getOpenListTgToken()).thenReturn(token);
        when(config.getOpenListTgUserId()).thenReturn(userId);
    }

    @Test
    void 启动时未配置不注册() {
        configure("", "");
        register.run();
        assertTrue(register.launches.isEmpty());
    }

    @Test
    void 配置未变时重复reload不重建() {
        configure("t1", "100");
        register.run();
        register.reload();
        register.reload();
        assertEquals(List.of("t1/100"), register.launches);
    }

    @Test
    void 配置变化时先停旧会话再按新配置启动() {
        configure("t1", "100");
        register.reload();
        BotSession old = register.sessions.get(0);

        configure("t2", "100");
        register.reload();

        verify(old).stop();
        assertEquals(List.of("t1/100", "t2/100"), register.launches);
    }

    @Test
    void 只改userId也会重建() {
        configure("t1", "100");
        register.reload();
        configure("t1", "200");
        register.reload();
        verify(register.sessions.get(0)).stop();
        assertEquals(List.of("t1/100", "t1/200"), register.launches);
    }

    @Test
    void 配置清空时只停不起() {
        configure("t1", "100");
        register.reload();
        configure("t1", " ");
        register.reload();
        verify(register.sessions.get(0)).stop();
        assertEquals(1, register.launches.size());
    }

    @Test
    void 注册失败后原样再保存会重试() {
        configure("bad", "100");
        register.failNext = true;
        register.reload();
        register.reload();
        assertEquals(List.of("bad/100", "bad/100"), register.launches);
    }

    @Test
    void 会话已自行停止时不再调用stop() {
        configure("t1", "100");
        register.reload();
        BotSession old = register.sessions.get(0);
        when(old.isRunning()).thenReturn(false);
        configure("t2", "100");
        register.reload();
        verify(old, never()).stop();
    }

    @Test
    void shutdown停掉当前会话() {
        configure("t1", "100");
        register.reload();
        register.shutdown();
        verify(register.sessions.get(0)).stop();
    }

    @Test
    void 无关参数变更不触发重载() {
        register.onConfigChanged(new SysConfigChangedEvent(Set.of("openlist.api.refresh")));
        verify(scheduler, never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void tg参数或刷新缓存会触发延迟重载且合并连续变更() {
        ScheduledFuture<?> first = mock(ScheduledFuture.class);
        ScheduledFuture<?> second = mock(ScheduledFuture.class);
        doReturn(first).doReturn(second).when(scheduler).schedule(any(Runnable.class), any(Instant.class));

        register.onConfigChanged(new SysConfigChangedEvent(Set.of("openlist.tg.token")));
        register.onConfigChanged(SysConfigChangedEvent.all());

        verify(scheduler, times(2)).schedule(any(Runnable.class), any(Instant.class));
        verify(first).cancel(false);
        verify(second, never()).cancel(false);
    }

    @Test
    void 事件键集合容忍null() {
        SysConfigChangedEvent event = new SysConfigChangedEvent(new HashSet<>(Arrays.asList(null, "openlist.tg.userid")));
        assertTrue(event.affectsPrefix(TgBotRegister.CONFIG_PREFIX));
        assertTrue(new SysConfigChangedEvent(null).keys().isEmpty());
        assertFalse(event.keys().isEmpty());
    }
}
