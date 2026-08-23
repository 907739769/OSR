package com.osr.openliststrm.pt.clean;

import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.RoundHeartbeat;
import com.osr.openliststrm.pt.PtNotifyText;

import java.util.List;
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
 * 定时触发自动删种。
 * <p>
 * 周期默认 60 分钟，比下载追踪（30 秒）慢两个数量级是刻意的：删种的判据是"做种够久了"，
 * 以小时计，用秒级轮询只会让每一轮都把整个保种盘的种子列表拉一遍。
 * </p>
 * <p>
 * 首次执行延后 5 分钟：应用刚起来时下载器可能还没就绪，更重要的是留一段窗口——
 * 用户升级后如果发现规则配错了，来得及在第一次真正删除之前关掉开关。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class TorrentCleanTask {

    @Autowired
    private TorrentCleanService cleanService;

    @Value("${pt.clean.interval-minutes:60}")
    private int intervalMinutes;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过周期时避免重叠触发：重叠会让同一个组被两个线程同时判成可删并各删一次 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        long minutes = Math.max(1, intervalMinutes);
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll),
                Instant.now().plusSeconds(300), Duration.ofMinutes(minutes));
        log.info("TorrentCleanTask started, interval={}min", minutes);
    }

    @PreDestroy
    public void stop() {
        log.info("TorrentCleanTask stopped");
        MDC.clear();
    }

    /** 无变化时最多半小时报一次平安：不打的话「一切正常」和「调度器死了」在日志上一模一样 */
    private final RoundHeartbeat heartbeat = new RoundHeartbeat();

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("TorrentCleanTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            List<CleanSummary> summaries = cleanService.cleanAll();
            int torrents = summaries.stream().mapToInt(CleanSummary::getDeletedTorrents).sum();
            int groups = summaries.stream().mapToInt(CleanSummary::getDeletedGroups).sum();
            int failed = summaries.stream().mapToInt(CleanSummary::getFailedGroups).sum();
            long freed = summaries.stream().mapToLong(CleanSummary::getFreedBytes).sum();
            if (torrents > 0 || failed > 0) {
                heartbeat.active();
                // size() 在 <=0 时返回 null（通知里那边是「整段不写」的语义），
                // 只删失败没删成时 freed 就是 0，直接拼会打出「释放 null」
                String freedText = freed > 0 ? PtNotifyText.size(freed) : "0 MB";
                log.info("自动删种完成：删除 {} 个种子（{} 组），释放 {}{}",
                        torrents, groups, freedText,
                        failed > 0 ? "，" + failed + " 组删除失败" : "");
            } else {
                RoundHeartbeat.Beat beat = heartbeat.quiet();
                if (beat.shouldReport()) {
                    int scanned = summaries.stream().mapToInt(CleanSummary::getScannedGroups).sum();
                    log.info("自动删种完成：扫描 {} 组，无需删除（最近 {} 轮均无）", scanned, beat.quietRounds());
                }
            }
        } catch (Exception e) {
            log.error("TorrentCleanTask poll error", e);
        } finally {
            running.set(false);
        }
    }
}
