package com.osr.openliststrm.pt.task;

import com.osr.common.utils.RoundHeartbeat;
import com.osr.common.utils.Threads;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.pt.stats.SeedingStatsService;
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
 * 每小时给每台下载器拍一次保种快照（见 {@link SeedingStatsService#snapshotAll}），覆盖当天那一行。
 * <p>
 * 一天一行、每小时覆盖：看板要的是按天的上传量，一天拍一次的话赶上那一刻下载器离线，那天就整个缺了；
 * 每小时覆盖一次，当天最后一次成功的值就是收盘值。
 *
 * @author Jack
 */
@Slf4j
@Component
public class SeedSnapshotTask {

    @Autowired
    private SeedingStatsService seedingStatsService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 每小时都成功是常态，逐轮报平安没有意义；但完全不打又分不出「正常」与「调度器死了」 */
    private final RoundHeartbeat heartbeat = new RoundHeartbeat();

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(300), Duration.ofHours(1));
        log.info("SeedSnapshotTask started, interval=60min");
    }

    @PreDestroy
    public void stop() {
        log.info("SeedSnapshotTask stopped");
        MDC.clear();
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("SeedSnapshotTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            // 覆盖同一天的快照不算「业务变化」，一律按安静轮次记，只按心跳节奏报一次平安
            int written = seedingStatsService.snapshotAll();
            RoundHeartbeat.Beat beat = heartbeat.quiet();
            if (beat.shouldReport()) {
                log.info("保种快照运行正常：本轮写入 {} 台下载器（最近 {} 轮）", written, beat.quietRounds());
            }
        } catch (Exception e) {
            log.error("保种快照异常：{}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }
}
