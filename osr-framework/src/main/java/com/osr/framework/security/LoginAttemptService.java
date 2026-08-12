package com.osr.framework.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.osr.system.service.ISysConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 登录失败计数与临时锁定。
 * <p>
 * 计数分两个互不干扰的桶：
 * </p>
 * <ul>
 *   <li><b>按账号</b>（阈值 {@code sys.login.maxRetryCount}，默认 5）——主力防线。
 *       账号名是攻击者绕不开的输入，这条拦得住撞库。代价是知道用户名的人可以持续把该账号锁住，
 *       这是这类锁定机制固有的取舍，锁定会自动到期，且可把阈值配成 0 关掉。</li>
 *   <li><b>按来源 IP</b>（阈值 {@code sys.login.ipMaxRetryCount}，默认 30）——补充防线，
 *       拦的是换着用户名喷的扫描器。阈值刻意放宽：同一出口 IP 后面可能是一家人或一间办公室，
 *       正常人手滑几次不该被连坐。<b>它是尽力而为的</b>：IP 取自
 *       {@code IpUtils#getIpAddr}，该方法读 X-Forwarded-For 的第一段，而项目自带的 nginx 用的是
 *       {@code $proxy_add_x_forwarded_for}（在客户端传来的值后面追加），所以客户端能伪造这一段来换桶。
 *       改成覆盖写会让「OSR 前面还套了一层反代」的部署里所有人共用一个 IP 桶，连坐面更大，
 *       两害相权保留现状——真正靠得住的是上面那条按账号的。</li>
 * </ul>
 * <p>
 * 锁定时长共用 {@code sys.login.lockMinutes}（默认 10 分钟），同时也是失败计数的滑动窗口：
 * 距上次失败超过这个时长，计数从头开始。
 * </p>
 * <p>
 * 计数放进程内（Caffeine），不落库也不依赖 Redis：这是单实例部署的应用，重启后计数清零可以接受
 * ——重启需要宿主机权限，能重启的人本来就不需要爆破。
 * </p>
 *
 * @author Jack
 */
@Component
public class LoginAttemptService {

    /** 账号维度的失败次数上限，0 或负数 = 关闭账号锁定 */
    public static final String KEY_MAX_RETRY = "sys.login.maxRetryCount";

    /** IP 维度的失败次数上限，0 或负数 = 关闭 IP 锁定 */
    public static final String KEY_IP_MAX_RETRY = "sys.login.ipMaxRetryCount";

    /** 锁定时长（分钟），同时是失败计数窗口；0 或负数 = 关闭全部锁定 */
    public static final String KEY_LOCK_MINUTES = "sys.login.lockMinutes";

    static final int DEFAULT_MAX_RETRY = 5;
    static final int DEFAULT_IP_MAX_RETRY = 30;
    static final int DEFAULT_LOCK_MINUTES = 10;

    /** 被跟踪的桶上限。攻击者用随机用户名喷时靠它兜住内存，超出按 LRU 淘汰 */
    private static final int MAX_TRACKED_KEYS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final ISysConfigService sysConfigService;

    private final Cache<String, Attempt> attempts;

    /** 当前时间源，测试用它推进时间而不必真的等待 */
    private final LongSupplier clock;

    /**
     * @implNote {@code @Autowired} 不能省：这个类有两个构造器（另一个是测试用的注时钟版本），
     * 多构造器且都没标注时 Spring 会去找默认构造器，找不到就在装配阶段整个应用启动失败。
     * 单元测试直接 new 目标类，绕开了 Spring 装配，测试全绿也照样炸。
     */
    @Autowired
    public LoginAttemptService(ISysConfigService sysConfigService) {
        this(sysConfigService, System::currentTimeMillis);
    }

    LoginAttemptService(ISysConfigService sysConfigService, LongSupplier clock) {
        this.sysConfigService = sysConfigService;
        this.clock = clock;
        // TTL 只是兜底回收，真正的过期判断在 Attempt.lockedUntil 上；
        // 取值远大于任何合理的 lockMinutes，避免锁定还没到期条目先被清掉
        this.attempts = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_KEYS)
                .expireAfterWrite(Duration.ofHours(24))
                .build();
    }

    /**
     * 查询当前是否处于锁定中。
     * <p>
     * 账号桶和 IP 桶命中哪个都返回同一种结果，调用方也只给同一句提示：
     * 两者提示不同等于告诉攻击者「这个用户名存在且已被我锁住」。
     * </p>
     *
     * @return 剩余锁定秒数；0 表示未锁定
     */
    public long lockedSecondsRemaining(String username, String ip) {
        long now = clock.getAsLong();
        long remaining = remainingFor(userKey(username), now);
        if (remaining > 0) {
            return remaining;
        }
        return remainingFor(ipKey(ip), now);
    }

    /**
     * 记录一次登录失败。
     * <p>
     * 「密码错误」和「用户不存在」都要调用：只对存在的账号计数，会让攻击者通过「有没有被锁」
     * 判断出哪些用户名是真的。
     * </p>
     */
    public void recordFailure(String username, String ip) {
        int lockMinutes = configInt(KEY_LOCK_MINUTES, DEFAULT_LOCK_MINUTES);
        if (lockMinutes <= 0) {
            return;
        }
        long lockMillis = lockMinutes * 60_000L;
        long now = clock.getAsLong();
        bump(userKey(username), configInt(KEY_MAX_RETRY, DEFAULT_MAX_RETRY), lockMillis, now, "账号", username);
        bump(ipKey(ip), configInt(KEY_IP_MAX_RETRY, DEFAULT_IP_MAX_RETRY), lockMillis, now, "IP", ip);
    }

    /** 登录成功，清空该账号与该 IP 的失败计数 */
    public void recordSuccess(String username, String ip) {
        attempts.invalidate(userKey(username));
        attempts.invalidate(ipKey(ip));
    }

    private void bump(String key, int threshold, long lockMillis, long now, String scopeName, String scopeValue) {
        if (threshold <= 0) {
            return;
        }
        boolean[] justLocked = new boolean[1];
        attempts.asMap().compute(key, (k, current) -> {
            Attempt attempt = current != null ? current : new Attempt();
            if (attempt.lockedUntil > now) {
                // 锁定期内的失败不再累加，否则一边锁着一边攒计数，解锁瞬间就又满了
                return attempt;
            }
            if (now - attempt.lastFailureAt > lockMillis) {
                attempt.failures = 0;
            }
            attempt.failures++;
            attempt.lastFailureAt = now;
            if (attempt.failures >= threshold) {
                attempt.lockedUntil = now + lockMillis;
                attempt.failures = 0;
                justLocked[0] = true;
            }
            return attempt;
        });
        if (justLocked[0]) {
            log.warn("[login] {}[{}] 连续失败{}次，锁定{}分钟", scopeName, scopeValue, threshold, lockMillis / 60_000L);
        }
    }

    private long remainingFor(String key, long now) {
        Attempt attempt = attempts.getIfPresent(key);
        if (attempt == null || attempt.lockedUntil <= now) {
            return 0L;
        }
        return (attempt.lockedUntil - now + 999L) / 1000L;
    }

    private int configInt(String configKey, int defaultValue) {
        String raw = sysConfigService.selectConfigByKey(configKey);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            // 配置被填成非数字时退回默认值而不是关闭保护——填错字不该等于关掉防护
            log.warn("[login] 配置项 {} 的值 \"{}\" 不是整数，按默认值 {} 处理", configKey, raw, defaultValue);
            return defaultValue;
        }
    }

    /** 账号大小写不敏感：登录查询按 login_name 匹配，大小写变体不该各算一个桶 */
    private static String userKey(String username) {
        return "u:" + (username == null ? "" : username.trim().toLowerCase());
    }

    private static String ipKey(String ip) {
        return "i:" + (ip == null || ip.isBlank() ? "unknown" : ip);
    }

    /** 单个桶的状态。只在 {@code attempts.asMap().compute} 的原子区间内被修改 */
    private static final class Attempt {
        private int failures;
        private long lastFailureAt;
        private long lockedUntil;
    }
}
