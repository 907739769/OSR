package com.osr.openliststrm.pt.autoadd;

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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 热门自动订阅心跳：每 30 分钟检查一次哪些规则到期（到期周期由各规则的 interval_hours 决定）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class AutoAddPopularTask {

    @Autowired
    private AutoAddPopularService autoAddPopularService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过心跳间隔时，避免重叠触发重复执行 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::run), Instant.now().plusSeconds(120), Duration.ofMinutes(30));
        log.info("AutoAddPopularTask started, 心跳间隔=30min（各规则的实际执行周期由规则自身的 interval_hours 决定）");
    }

    @PreDestroy
    public void stop() {
        log.info("AutoAddPopularTask stopped");
        MDC.clear();
    }

    /** 无变化时最多半小时报一次平安：不打的话「一切正常」和「调度器死了」在日志上一模一样 */
    private final RoundHeartbeat heartbeat = new RoundHeartbeat();

    private void run() {
        if (!running.compareAndSet(false, true)) {
            log.debug("AutoAddPopularTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            int ran = autoAddPopularService.runDueRules();
            if (ran > 0) {
                // 具体新订了什么由 AutoAddPopularService 逐条记，这里只负责证明这一轮跑过
                heartbeat.active();
                log.info("热门自动订阅完成：本轮执行了 {} 条规则", ran);
            } else {
                RoundHeartbeat.Beat beat = heartbeat.quiet();
                if (beat.shouldReport()) {
                    log.info("热门自动订阅完成：没有到期的规则（最近 {} 轮均无）", beat.quietRounds());
                }
            }
        } catch (Exception e) {
            log.error("AutoAddPopularTask run error", e);
        } finally {
            running.set(false);
        }
    }
}
