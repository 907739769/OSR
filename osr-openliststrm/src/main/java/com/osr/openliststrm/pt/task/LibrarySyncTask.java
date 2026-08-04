package com.osr.openliststrm.pt.task;

import com.osr.common.utils.Threads;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
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
import java.util.List;
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
    private IPtSubscriptionPlusService subscriptionService;
    @Autowired
    private SubscriptionService subscriptionBiz;
    @Autowired
    private StuckEpisodeSweepService stuckEpisodeSweepService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过心跳间隔时，避免重叠触发重复对账所有订阅 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(120), Duration.ofMinutes(10));
        log.info("LibrarySyncTask started");
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
            // 只对有缺集的订阅执行对账，跳过全部已入库的 ACTIVE 订阅
            List<PtSubscriptionPlus> active = subscriptionService.listActiveWithMissing();
            for (PtSubscriptionPlus sub : active) {
                try {
                    subscriptionBiz.refresh(sub.getId());
                } catch (Exception e) {
                    log.warn("对账订阅[{}]失败：{}", sub.getId(), e.getMessage());
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
