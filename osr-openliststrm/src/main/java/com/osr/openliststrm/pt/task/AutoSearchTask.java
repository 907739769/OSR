package com.osr.openliststrm.pt.task;

import com.osr.common.utils.RoundHeartbeat;
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
        log.info("AutoSearchTask started, 心跳间隔=30min（各订阅的实际补搜周期由 pt_filter_config.auto_search_interval_hours 决定）");
    }

    @PreDestroy
    public void stop() {
        log.info("AutoSearchTask stopped");
        MDC.clear();
    }

    /** 无变化时最多半小时报一次平安：不打的话「一切正常」和「调度器死了」在日志上一模一样 */
    private final RoundHeartbeat heartbeat = new RoundHeartbeat();

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
            AutoSearchService.RoundOutcome outcome = autoSearchService.run();
            if (outcome.changed()) {
                heartbeat.active();
                log.info("自动补搜完成：{} 个候选订阅中 {} 个到期并已检索{}",
                        outcome.candidates(), outcome.searched(),
                        outcome.failed() > 0 ? "，" + outcome.failed() + " 个失败" : "");
            } else {
                // 每条订阅默认 24 小时才到期一次，而心跳 30 分钟一次——绝大多数轮次
                // 一个订阅都不到期，这是设计如此，不是"补搜没在跑"
                RoundHeartbeat.Beat beat = heartbeat.quiet();
                if (beat.shouldReport()) {
                    log.info("自动补搜完成：{} 个候选订阅均未到期（最近 {} 轮均无）",
                            outcome.candidates(), beat.quietRounds());
                }
            }
        } catch (Exception e) {
            log.error("AutoSearchTask poll error", e);
        } finally {
            running.set(false);
        }
    }
}
