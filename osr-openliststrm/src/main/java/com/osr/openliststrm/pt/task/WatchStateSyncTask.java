package com.osr.openliststrm.pt.task;

import com.osr.common.utils.RoundHeartbeat;
import com.osr.common.utils.Threads;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.pt.media.WatchStateSyncService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每小时从媒体服务器同步一次观看状态（见 {@link WatchStateSyncService}）。
 * <p>
 * 一小时是刻意的慢：它只服务「卡片上显示已看几集」与「补搜时在看的剧排前面」，两者都不需要分钟级新鲜度，
 * 而每轮要对每条订阅在每台服务器上各打一两次请求。
 *
 * @author Jack
 */
@Slf4j
@Component
public class WatchStateSyncTask {

    @Autowired
    private WatchStateSyncService syncService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 没变化是常态，每小时都打「无变化」没有意义；但完全不打又分不出「正常」与「调度器死了」 */
    private final RoundHeartbeat heartbeat = new RoundHeartbeat();

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(300), Duration.ofHours(1));
        log.info("WatchStateSyncTask started, interval=60min");
    }

    @PreDestroy
    public void stop() {
        log.info("WatchStateSyncTask stopped");
        MDC.clear();
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("WatchStateSyncTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            int changed = syncService.syncAll();
            if (changed > 0) {
                heartbeat.active();
                log.info("观看状态同步完成：{} 条订阅有变化", changed);
            } else {
                RoundHeartbeat.Beat beat = heartbeat.quiet();
                if (beat.shouldReport()) {
                    log.info("观看状态同步完成：无变化（最近 {} 轮均无变化）", beat.quietRounds());
                }
            }
        } catch (Exception e) {
            log.error("观看状态同步异常：{}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }
}
