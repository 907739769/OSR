package com.osr.openliststrm.pt.transfer;

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
 * 定时执行转移做种。
 * <p>
 * 周期默认 15 分钟。这个值同时决定了两件事：多久发现一批新的达标种子，以及
 * <b>已发起的转移多久能收尾</b>——目标端的校验结果要等下一轮才会被读到。比自动删种
 * （60 分钟）快，是因为转移中的种子处于"暂停、不做种"的状态，收尾越快越好；
 * 又不像下载追踪（30 秒）那么频繁，因为转移的判据是"做种够久了"，以小时计。
 * </p>
 * <p>
 * 首次执行延后 5 分钟：应用刚起来时下载器可能还没就绪，也给用户留一段窗口——
 * 升级后如果发现规则配错了，来得及在第一次真正搬东西之前关掉开关。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class TorrentTransferTask {

    @Autowired
    private TorrentTransferService transferService;

    @Value("${pt.transfer.interval-minutes:15}")
    private int intervalMinutes;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过周期时避免重叠触发：重叠会让同一个种子被两个线程各发起一次转移 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        long minutes = Math.max(1, intervalMinutes);
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll),
                Instant.now().plusSeconds(300), Duration.ofMinutes(minutes));
        log.info("TorrentTransferTask started, interval={}min", minutes);
    }

    @PreDestroy
    public void stop() {
        log.info("TorrentTransferTask stopped");
        MDC.clear();
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("TorrentTransferTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            transferService.transferAll();
        } catch (Exception e) {
            log.error("TorrentTransferTask poll error", e);
        } finally {
            running.set(false);
        }
    }
}
