package com.osr.openliststrm.pt.clean;

import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 定时触发自动删种。
 * <p>
 * 周期默认 60 分钟，比下载追踪（30 秒）慢两个数量级是刻意的：删种的判据是"做种够久了"，
 * 以小时计，用秒级轮询只会让每一轮都把整个保种盘的种子列表拉一遍。
 * </p>
 * <p>
 * 首次执行延后 5 分钟：应用刚起来时下载器可能还没就绪，更重要的是留一段窗口——
 * 用户升级后如果发现规则配错了，来得及在第一次真正删除之前关掉开关。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class TorrentCleanTask {

    @Autowired
    private TorrentCleanService cleanService;

    @Value("${pt.clean.interval-minutes:60}")
    private int intervalMinutes;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过周期时避免重叠触发：重叠会让同一个组被两个线程同时判成可删并各删一次 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        long minutes = Math.max(1, intervalMinutes);
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll),
                Instant.now().plusSeconds(300), Duration.ofMinutes(minutes));
        log.info("TorrentCleanTask started, interval={}min", minutes);
    }

    @PreDestroy
    public void stop() {
        log.info("TorrentCleanTask stopped");
        MDC.clear();
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("TorrentCleanTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            cleanService.cleanAll();
        } catch (Exception e) {
            log.error("TorrentCleanTask poll error", e);
        } finally {
            running.set(false);
        }
    }
}
