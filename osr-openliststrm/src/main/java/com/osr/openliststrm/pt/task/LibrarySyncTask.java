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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每 10 分钟遍历订阅中的订阅，与 Emby 对账（补齐总集数、推进已入库、重算状态）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class LibrarySyncTask {

    @Autowired
    private LibrarySyncService librarySyncService;
    @Autowired
    private StuckEpisodeSweepService stuckEpisodeSweepService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过心跳间隔时，避免重叠触发重复对账所有订阅 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 无变化时最多半小时报一次平安，避免 10 分钟一轮刷出 144 行/天的「无变化」 */
    private final RoundHeartbeat heartbeat = new RoundHeartbeat();

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(120), Duration.ofMinutes(10));
        log.info("LibrarySyncTask started, interval=10min");
    }

    @PreDestroy
    public void stop() {
        log.info("LibrarySyncTask stopped");
        MDC.clear();
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("LibrarySyncTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            // 只对有缺集的订阅执行对账，跳过全部已入库的 ACTIVE 订阅。
            // 订阅之间并发（见 LibrarySyncService 的并发度注释），refreshAll 会等齐才返回
            long startedAt = System.currentTimeMillis();
            LibrarySyncService.SyncOutcome outcome = librarySyncService.refreshAll();
            long cost = System.currentTimeMillis() - startedAt;
            if (outcome.changed()) {
                heartbeat.active();
                log.info("对账完成：{} 条订阅，{} 集入库{}，耗时 {}ms",
                        outcome.scanned(), outcome.episodesIn(),
                        outcome.failed() > 0 ? "，" + outcome.failed() + " 条失败" : "", cost);
            } else {
                // 无变化是常态（一天里绝大多数轮次都没有新集入库），每轮都打就是 144 行/天；
                // 但完全不打的话，「一切正常」和「调度器死了」在日志上完全一样
                RoundHeartbeat.Beat beat = heartbeat.quiet();
                if (beat.shouldReport()) {
                    log.info("对账完成：{} 条订阅无变化（最近 {} 轮均无变化），耗时 {}ms",
                            outcome.scanned(), beat.quietRounds(), cost);
                }
            }
            // 对账之后再清扫：这一轮刚被推进 IN_LIBRARY 的集不该再被当成卡死。
            // 清扫失败不能影响对账结果，单独兜一层异常
            try {
                stuckEpisodeSweepService.sweep();
            } catch (Exception e) {
                log.warn("清扫卡死在途集失败：{}", e.getMessage());
            }
        } catch (Exception e) {
            log.error("LibrarySyncTask poll error", e);
        } finally {
            running.set(false);
        }
    }
}
