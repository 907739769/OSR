package com.osr.openliststrm.pt.task;

import com.osr.common.utils.Threads;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.spring.SpringUtils;
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
 * RSS 轮询心跳：每 60 秒检查一次哪些索引器到期，到期的才真正拉取
 * （拉取周期由各索引器的 poll_interval 决定）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class RssPollTask {

    @Autowired
    private RssPollService rssPollService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过心跳间隔时，避免重叠触发重复拉取所有索引器 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(60), Duration.ofSeconds(60));
        log.info("RssPollTask started");
    }

    @PreDestroy
    public void stop() {
        log.info("RssPollTask stopped");
        MDC.clear();
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("RssPollTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            rssPollService.poll();
        } catch (Exception e) {
            log.error("RssPollTask poll error", e);
        } finally {
            running.set(false);
        }
    }
}
