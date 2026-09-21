package com.osr.openliststrm.pt.upgrade;

import com.osr.common.utils.FaultThrottle;
import com.osr.common.utils.RoundHeartbeat;
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
import java.util.Date;
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
        log.info("UpgradeScanTask started, 心跳间隔=1h（实际扫描周期见洗版配置）");
    }

    @PreDestroy
    public void stop() {
        log.info("UpgradeScanTask stopped");
        MDC.clear();
    }

    /** 无变化时最多半小时报一次平安：不打的话「一切正常」和「调度器死了」在日志上一模一样 */
    private final RoundHeartbeat heartbeat = new RoundHeartbeat();

    /** 持续性故障（数据库、索引器全挂）只报开始与恢复，不按心跳每小时刷一条 */
    private final FaultThrottle faults = new FaultThrottle();
    private static final String FAULT_KEY = "upgrade-scan";

    /** 最近一次真正跑过的扫描，供洗版规则页展示；null 表示本次启动后还没跑过 */
    private volatile LastScan lastScan;

    /**
     * 最近一次扫描的快照。
     *
     * @param finishedAt 结束时间
     * @param manual     是否由页面上的「立即扫描」触发
     * @param outcome    扫描结果；出错时为 null
     * @param error      出错时的异常信息；成功时为 null
     */
    public record LastScan(Date finishedAt, boolean manual, UpgradeScanService.ScanOutcome outcome, String error) {
    }

    public LastScan lastScan() {
        return lastScan;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 立即跑一轮（不看是否到期），在后台线程执行、立即返回。
     *
     * @return false 表示已有一轮在跑，本次没有发起
     */
    public boolean triggerNow() {
        if (running.get()) {
            return false;
        }
        scheduler.schedule(Threads.wrap(() -> runGuarded(true)), Instant.now());
        return true;
    }

    private void poll() {
        runGuarded(false);
    }

    private void runGuarded(boolean manual) {
        if (!running.compareAndSet(false, true)) {
            log.debug("UpgradeScanTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            if (!manual && !isDue()) {
                // 未到期不算一轮：它不代表扫描跑过，拿它喂心跳会让「洗版其实一直没扫」
                // 看起来一切正常
                return;
            }
            lastRunMillis = System.currentTimeMillis();
            UpgradeScanService.ScanOutcome outcome = upgradeScanService.run();
            lastScan = new LastScan(new Date(), manual, outcome, null);
            if (faults.onSuccess(FAULT_KEY)) {
                log.info("洗版扫描已恢复正常");
            }
            if (outcome.pushed() > 0) {
                heartbeat.active();
                log.info("洗版扫描完成{}：搜索 {} 次，推送了 {} 个升级下载",
                        manual ? "（手动触发）" : "", outcome.searched(), outcome.pushed());
            } else {
                RoundHeartbeat.Beat beat = heartbeat.quiet();
                if (manual || beat.shouldReport()) {
                    log.info("洗版扫描完成{}：{}，无可升级的集（最近 {} 轮均无）", manual ? "（手动触发）" : "",
                            outcome.active() ? "搜索 " + outcome.searched() + " 次、退避中 " + outcome.backedOff() + " 集"
                                    : "洗版未激活",
                            beat.quietRounds());
                }
            }
        } catch (Exception e) {
            lastScan = new LastScan(new Date(), manual, null, e.getMessage());
            FaultThrottle.Decision decision = faults.onFailure(FAULT_KEY);
            if (decision.shouldReport()) {
                log.error("洗版扫描失败（连续 {} 次）：{}", decision.consecutiveFailures(), e.getMessage(), e);
            }
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
        return System.currentTimeMillis() - lastRunMillis >= intervalHours() * 3600_000L;
    }

    /**
     * 预计下一轮的最早时间：上一轮开始 + 周期。实际由整点心跳触发，最多再晚一小时。
     * 本次启动后还没跑过时为 null（首轮在启动 5 分钟后）。
     */
    public Date nextScanAt() {
        long last = lastRunMillis;
        return last == 0L ? null : new Date(last + intervalHours() * 3600_000L);
    }

    private int intervalHours() {
        Integer hours = upgradeConfigService.getConfig().getScanIntervalHours();
        return (hours == null || hours <= 0) ? 6 : hours;
    }
}
