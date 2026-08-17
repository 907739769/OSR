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
import java.util.concurrent.TimeUnit;
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

    /** 当前轮次的开始时刻（nanoTime），只用于重叠时报出上一轮已经跑了多久 */
    private volatile long roundStartNanos;

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
            // warn 而不是 debug：单轮跑过心跳间隔时，用户看到的现象是「自动补搜不按周期跑」，
            // 而默认日志级别下一个字都没有。AutoSearchService 的单轮预算
            // （pt.search.auto-search-round-budget-ms，默认 20 分钟）就是为了不走到这里，
            // 真走到了说明预算配得比心跳还长，或者某一步慢到连那个软上限都拦不住
            log.warn("上一轮自动补搜已运行 {} 秒仍未结束，跳过本次心跳",
                    TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - roundStartNanos));
            return;
        }
        roundStartNanos = System.nanoTime();
        try {
            autoSearchService.run();
        } catch (Exception e) {
            log.error("AutoSearchTask poll error", e);
        } finally {
            running.set(false);
        }
    }
}
