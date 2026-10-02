package com.osr.openliststrm.pt.task;

import com.osr.common.utils.RoundHeartbeat;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.pt.downloader.DownloaderSpaceService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定期读各下载器的剩余空间（{@link DownloaderSpaceService}），供首页待办、下载器卡片与空间告警使用。
 *
 * @author Jack
 */
@Slf4j
@Component
public class DownloaderSpaceTask {

    @Autowired
    private DownloaderSpaceService spaceService;

    @Autowired
    @Qualifier("virtualScheduledExecutor")
    private TaskScheduler scheduler;

    /**
     * 检查间隔。qBittorrent 只能从 {@code sync/maindata} 取剩余空间，那个接口连带全部种子、响应不小，
     * 所以不往下压；磁盘也不会在 15 分钟里从「够」变成「满」。
     */
    @Value("${pt.downloader.space-check-minutes:15}")
    private int intervalMinutes;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 无变化时最多半小时报一次平安 */
    private final RoundHeartbeat heartbeat = new RoundHeartbeat();

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        long minutes = Math.max(1, intervalMinutes);
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(90), Duration.ofMinutes(minutes));
        log.info("DownloaderSpaceTask started, interval={}min", minutes);
    }

    @PreDestroy
    public void stop() {
        log.info("DownloaderSpaceTask stopped");
        MDC.clear();
    }

    void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("DownloaderSpaceTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            DownloaderSpaceService.CheckOutcome outcome = spaceService.checkAll();
            // 判据是「有下载器刚进入或离开低位」，不是读到了几台——后者每轮都一样
            if (outcome.changed() > 0) {
                heartbeat.active();
                log.info("剩余空间检查完成：{} 台，{} 台低于告警线", outcome.checked(), outcome.low());
            } else {
                RoundHeartbeat.Beat beat = heartbeat.quiet();
                if (beat.shouldReport()) {
                    log.info("剩余空间检查完成：{} 台，{} 台低于告警线（最近 {} 轮无变化）",
                            outcome.checked(), outcome.low(), beat.quietRounds());
                }
            }
        } catch (Exception e) {
            log.error("DownloaderSpaceTask poll error：{}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }
}
