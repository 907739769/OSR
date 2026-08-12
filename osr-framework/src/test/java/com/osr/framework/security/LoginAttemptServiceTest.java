package com.osr.framework.security;

import com.osr.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 登录失败锁定。
 * <p>
 * 时间由 {@link AtomicLong} 驱动，测试里直接把时钟往前拨，不依赖真实等待。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginAttemptServiceTest {

    private static final String USER = "admin";
    private static final String IP = "203.0.113.7";

    @Mock
    private ISysConfigService sysConfigService;

    private AtomicLong now;
    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        now = new AtomicLong(1_000_000L);
        config(LoginAttemptService.KEY_MAX_RETRY, "3");
        config(LoginAttemptService.KEY_IP_MAX_RETRY, "10");
        config(LoginAttemptService.KEY_LOCK_MINUTES, "10");
        service = new LoginAttemptService(sysConfigService, now::get);
    }

    private void config(String key, String value) {
        when(sysConfigService.selectConfigByKey(key)).thenReturn(value);
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(USER, IP);
        }
    }

    @Test
    void 未达阈值不锁定() {
        fail(2);
        assertEquals(0L, service.lockedSecondsRemaining(USER, IP));
    }

    @Test
    void 达到账号阈值后锁定并在时长到期后自动解锁() {
        fail(3);

        long remaining = service.lockedSecondsRemaining(USER, IP);
        assertTrue(remaining > 0 && remaining <= 600, "应锁定且剩余不超过10分钟，实际=" + remaining);

        now.addAndGet(9 * 60_000L);
        assertTrue(service.lockedSecondsRemaining(USER, IP) > 0, "9分钟时仍应处于锁定");

        now.addAndGet(2 * 60_000L);
        assertEquals(0L, service.lockedSecondsRemaining(USER, IP), "超过10分钟应自动解锁");
    }

    @Test
    void 锁定期内继续失败不会延长锁定() {
        fail(3);
        long before = service.lockedSecondsRemaining(USER, IP);

        now.addAndGet(60_000L);
        fail(5);

        long after = service.lockedSecondsRemaining(USER, IP);
        assertTrue(after < before, "锁定截止时间不应被锁定期内的失败推后，before=" + before + " after=" + after);
    }

    @Test
    void 失败间隔超过统计窗口时计数清零() {
        fail(2);
        // 距上次失败超过 lockMinutes，窗口重置
        now.addAndGet(11 * 60_000L);
        fail(2);

        assertEquals(0L, service.lockedSecondsRemaining(USER, IP), "跨窗口的失败不应累加成锁定");
    }

    @Test
    void 登录成功清空计数() {
        fail(2);
        service.recordSuccess(USER, IP);
        fail(2);

        assertEquals(0L, service.lockedSecondsRemaining(USER, IP));
    }

    @Test
    void 账号大小写不同视为同一个账号() {
        service.recordFailure("Admin", IP);
        service.recordFailure("ADMIN", IP);
        service.recordFailure("admin", IP);

        assertTrue(service.lockedSecondsRemaining("aDmIn", IP) > 0);
    }

    @Test
    void 换用户名喷同一IP会触发IP锁定() {
        for (int i = 0; i < 10; i++) {
            service.recordFailure("ghost" + i, IP);
        }

        // 每个用户名各自只失败1次，远未达账号阈值，但 IP 桶已满
        assertEquals(0L, service.lockedSecondsRemaining("ghost0", "198.51.100.1"), "换个IP的同名账号不该被连累");
        assertTrue(service.lockedSecondsRemaining("someoneelse", IP) > 0, "该IP应被锁定");
    }

    @Test
    void 账号阈值配成0时关闭账号锁定但IP锁定仍生效() {
        config(LoginAttemptService.KEY_MAX_RETRY, "0");

        fail(5);
        assertEquals(0L, service.lockedSecondsRemaining(USER, "198.51.100.2"), "账号锁定应已关闭");

        fail(5);
        assertTrue(service.lockedSecondsRemaining("other", IP) > 0, "IP阈值10仍应在第10次失败后生效");
    }

    @Test
    void 锁定时长配成0时整体关闭() {
        config(LoginAttemptService.KEY_LOCK_MINUTES, "0");

        fail(20);
        assertEquals(0L, service.lockedSecondsRemaining(USER, IP));
    }

    @Test
    void 配置为非法值时回落默认阈值而不是关闭保护() {
        config(LoginAttemptService.KEY_MAX_RETRY, "abc");
        config(LoginAttemptService.KEY_LOCK_MINUTES, "");

        fail(LoginAttemptService.DEFAULT_MAX_RETRY);

        assertTrue(service.lockedSecondsRemaining(USER, IP) > 0,
                "非数字配置应退回默认值 " + LoginAttemptService.DEFAULT_MAX_RETRY + " 次");
    }
}
