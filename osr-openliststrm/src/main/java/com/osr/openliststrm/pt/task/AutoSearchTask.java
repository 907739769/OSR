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
 * 自动补搜心跳：每 30 分钟检查一次哪些订阅开启了自动补搜且到期，到期的发起一次搜索
 * （具体周期由 pt_filter_config.auto_search_interval_hours 决定，默认 24 小时）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class AutoSearchTask {

    @Autowired
    private AutoSearchService autoSearchService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过心跳间隔时，避免重叠触发重复扫描所有订阅 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(180), Duration.ofMinutes(30));
        log.info("AutoSearchTask started");
    }

    @PreDestroy
    public void stop() {
        log.info("AutoSearchTask stopped");
        MDC.clear();
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("AutoSearchTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            autoSearchService.run();
        } catch (Exception e) {
            log.error("AutoSearchTask poll error", e);
        } finally {
            running.set(false);
        }
    }
}
