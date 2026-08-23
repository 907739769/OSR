package com.osr.openliststrm.tmdb;

import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.RoundHeartbeat;
import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * tmdb_cache 过期行清理心跳。
 * <p>
 * 缓存表只靠「同 key 再次请求时 upsert 覆盖」自然回收，刮完一次就不再访问的 key 会永久留存，
 * 表随刮削量单调增长。这里每 6 小时扫一次，把已过期的行分批删掉。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class TmdbCachePurgeTask {

    /** 清理周期。TTL 默认 24 小时，6 小时一轮足够让过期行及时下线，又不至于频繁扫表 */
    private static final Duration INTERVAL = Duration.ofHours(6);

    /** 首次延迟：错开启动期的 DDL 迁移与各业务任务的首轮拉取 */
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(300);

    private final TmdbCacheService tmdbCacheService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过周期时，避免重叠触发 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public TmdbCachePurgeTask(TmdbCacheService tmdbCacheService) {
        this.tmdbCacheService = tmdbCacheService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::run), Instant.now().plus(INITIAL_DELAY), INTERVAL);
        log.info("TmdbCachePurgeTask started, interval={}", INTERVAL);
    }

    @PreDestroy
    public void stop() {
        log.info("TmdbCachePurgeTask stopped");
        MDC.clear();
    }

    /** 无变化时最多半小时报一次平安：不打的话「一切正常」和「调度器死了」在日志上一模一样 */
    private final RoundHeartbeat heartbeat = new RoundHeartbeat();

    private void run() {
        if (!running.compareAndSet(false, true)) {
            log.debug("TmdbCachePurgeTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            int purged = tmdbCacheService.purgeExpired();
            if (purged > 0) {
                // 真删了东西时 TmdbCacheService 自己已经打过一条，这里只需重置心跳
                heartbeat.active();
            } else {
                RoundHeartbeat.Beat beat = heartbeat.quiet();
                if (beat.shouldReport()) {
                    log.info("TMDb 缓存清理完成：无过期记录（最近 {} 轮均无）", beat.quietRounds());
                }
            }
        } catch (Exception e) {
            log.error("TmdbCachePurgeTask run error", e);
        } finally {
            running.set(false);
        }
    }
}
