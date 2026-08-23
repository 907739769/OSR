package com.osr.openliststrm.pt.calendar;

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
 * 播出日期同步心跳。首轮兼做存量回填，之后跟进改档。
 *
 * @author Jack
 */
@Slf4j
@Component
public class EpisodeAirDateSyncTask {

    /** 12 小时一轮：播出日期以天为粒度，再密集也换不来更准的结果，只是白烧 TMDb 配额 */
    private static final Duration INTERVAL = Duration.ofHours(12);

    /** 首次延迟。比其他 PT 任务都靠后，让订阅/下载这些实时性更强的链路先跑起来 */
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(240);

    private final EpisodeAirDateSyncService syncService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    private final AtomicBoolean running = new AtomicBoolean(false);

    public EpisodeAirDateSyncTask(EpisodeAirDateSyncService syncService) {
        this.syncService = syncService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::run), Instant.now().plus(INITIAL_DELAY), INTERVAL);
        log.info("EpisodeAirDateSyncTask started, interval={}", INTERVAL);
    }

    @PreDestroy
    public void stop() {
        log.info("EpisodeAirDateSyncTask stopped");
        MDC.clear();
    }

    /** 无变化时最多半小时报一次平安：不打的话「一切正常」和「调度器死了」在日志上一模一样 */
    private final RoundHeartbeat heartbeat = new RoundHeartbeat();

    private void run() {
        if (!running.compareAndSet(false, true)) {
            log.debug("EpisodeAirDateSyncTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            int updated = syncService.syncAll();
            if (updated > 0) {
                // 有更新时 EpisodeAirDateSyncService 自己已经打过明细，这里只重置心跳
                heartbeat.active();
            } else {
                RoundHeartbeat.Beat beat = heartbeat.quiet();
                if (beat.shouldReport()) {
                    log.info("播出日期同步完成：无日期变化（最近 {} 轮均无）", beat.quietRounds());
                }
            }
        } catch (Exception e) {
            log.error("EpisodeAirDateSyncTask run error", e);
        } finally {
            running.set(false);
        }
    }
}
