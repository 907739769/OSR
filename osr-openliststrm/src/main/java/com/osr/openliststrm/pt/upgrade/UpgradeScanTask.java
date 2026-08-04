package com.osr.openliststrm.pt.upgrade;

import com.osr.common.utils.Threads;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.mybatisplus.service.IPtUpgradeConfigPlusService;
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
 * 洗版扫描心跳：每小时检查一次是否到期，到期则跑一轮扫描
 * （具体周期由 {@code pt_upgrade_config.scan_interval_hours} 决定，默认 6 小时）。
 * <p>
 * 心跳频率高于扫描周期、由服务侧自己判断是否到期，是为了让用户在配置页改了周期之后
 * 不必等到下一次心跳才生效——与 {@code AutoSearchTask} 同样的结构。
 * </p>
 * <p>
 * 首次触发延迟 5 分钟：启动瞬间数据库迁移可能还没跑完，而且洗版是低优先级的后台动作，
 * 不该和启动时的 RSS 轮询、对账挤在一起。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class UpgradeScanTask {

    @Autowired
    private UpgradeScanService upgradeScanService;
    @Autowired
    private IPtUpgradeConfigPlusService upgradeConfigService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过心跳间隔时，避免重叠触发重复扫描 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 上一轮实际执行的时间戳；0 表示还没跑过，首次心跳即执行 */
    private volatile long lastRunMillis = 0L;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(300), Duration.ofHours(1));
        log.info("UpgradeScanTask started");
    }

    @PreDestroy
    public void stop() {
        log.info("UpgradeScanTask stopped");
        MDC.clear();
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("UpgradeScanTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            if (!isDue()) {
                return;
            }
            lastRunMillis = System.currentTimeMillis();
            int pushed = upgradeScanService.run();
            if (pushed > 0) {
                log.info("本轮洗版扫描推送了 {} 个升级下载", pushed);
            }
        } catch (Exception e) {
            log.error("UpgradeScanTask poll error", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 是否到期。周期取自配置，非法值（&lt;=0）回退到 6 小时——
     * 这份配置用户可改，填个 0 不该让扫描退化成每小时一次。
     */
    private boolean isDue() {
        if (lastRunMillis == 0L) {
            return true;
        }
        Integer hours = upgradeConfigService.getConfig().getScanIntervalHours();
        int interval = (hours == null || hours <= 0) ? 6 : hours;
        return System.currentTimeMillis() - lastRunMillis >= interval * 3600_000L;
    }
}
