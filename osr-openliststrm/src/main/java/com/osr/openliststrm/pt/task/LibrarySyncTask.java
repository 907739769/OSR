package com.osr.openliststrm.pt.task;

import com.osr.common.utils.RoundHeartbeat;
import com.osr.common.utils.Threads;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.openliststrm.pt.media.LibraryRefreshedEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 每 10 分钟遍历订阅中的订阅，与 Emby 对账（补齐总集数、推进已入库、重算状态）。
 * <p>
 * 另有一条<b>提前对账</b>：刷新通知发给媒体服务器后（{@link LibraryRefreshedEvent}），等它扫完那几个目录再提前跑一轮，
 * 把「新集落盘 → 订阅显示已入库」从最多 10 分钟缩到几分钟。下载完成那一刻的即时对账（{@code DownloadCompletionSyncService}）
 * 补不上这段：那时文件还在下载器目录里，同步上网盘、生成 STRM、重命名、刮削都还没发生，媒体库里当然没有。
 * </p>
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

    @Autowired
    @Qualifier("virtualScheduledExecutor")
    private TaskScheduler scheduler;

    /**
     * 刷新通知发出后多久提前对账，{@code <= 0} 关闭。等的是媒体服务器扫完目录：Emby/Jellyfin 收到通知后
     * 自己还要攒一会儿才扫，Plex 的局部扫描快得多；3 分钟对前者是够的，对后者只是稍晚。
     */
    @Value("${pt.library.early-sync-delay-seconds:180}")
    private long earlySyncDelaySeconds;

    /** 单轮耗时超过心跳间隔时，避免重叠触发重复对账所有订阅 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 已有一轮提前对账在排队：期间再来的通知合并进去，一批新集只多跑一轮 */
    private final AtomicBoolean earlyPending = new AtomicBoolean(false);

    /** 最近一轮（定时或提前）真正开始的时刻，毫秒；0 表示还没跑过 */
    private volatile long lastRoundStartedAt;

    /** 到点时有一轮正在跑：隔多久再试、最多试几次。都没轮上就交给下一轮定时对账 */
    static final long EARLY_RETRY_SECONDS = 60;
    static final int EARLY_MAX_RETRIES = 3;

    /** 到点前这么久之内刚开始过一轮（多半是定时那轮正好落在媒体服务器扫完之后），就不再重复跑 */
    static final long EARLY_SKIP_IF_RAN_WITHIN_MILLIS = 60_000L;

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

    /** 媒体服务器刚收下刷新通知：排一轮提前对账（已排着的就合并进去） */
    @EventListener
    public void onLibraryRefreshed(LibraryRefreshedEvent event) {
        if (earlySyncDelaySeconds <= 0) {
            return;
        }
        if (!earlyPending.compareAndSet(false, true)) {
            log.debug("已有一轮提前对账在排队，本次刷新通知（{} 处）合并进去", event.targets());
            return;
        }
        log.debug("媒体服务器已收到 {} 处刷新通知，{} 秒后提前对账", event.targets(), earlySyncDelaySeconds);
        scheduleEarly(earlySyncDelaySeconds, 0);
    }

    private void scheduleEarly(long delaySeconds, int attempt) {
        scheduler.schedule(Threads.wrap(() -> runEarly(attempt)), Instant.now().plusSeconds(delaySeconds));
    }

    void runEarly(int attempt) {
        long now = System.currentTimeMillis();
        if (lastRoundStartedAt > 0 && now - lastRoundStartedAt < EARLY_SKIP_IF_RAN_WITHIN_MILLIS) {
            earlyPending.set(false);
            log.debug("刚对过账（{} 秒前），提前对账跳过", (now - lastRoundStartedAt) / 1000);
            return;
        }
        if (running.get()) {
            if (attempt < EARLY_MAX_RETRIES) {
                // 仍占着 earlyPending：重试期间来的通知照样合并，不会另起一条
                scheduleEarly(EARLY_RETRY_SECONDS, attempt + 1);
            } else {
                earlyPending.set(false);
                log.debug("对账一直在跑，提前对账放弃，交给下一轮定时对账");
            }
            return;
        }
        // 先放开再跑：这一轮进行期间又落盘的新集，要能再排下一轮
        earlyPending.set(false);
        poll("提前对账");
    }

    private void poll() {
        poll(null);
    }

    /**
     * @param trigger 非定时触发时写进日志的说明；定时那轮为 null
     */
    private void poll(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.debug("LibrarySyncTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        lastRoundStartedAt = System.currentTimeMillis();
        try {
            // 只对有缺集的订阅执行对账，跳过全部已入库的 ACTIVE 订阅。
            // 订阅之间并发（见 LibrarySyncService 的并发度注释），refreshAll 会等齐才返回
            long startedAt = System.currentTimeMillis();
            LibrarySyncService.SyncOutcome outcome = librarySyncService.refreshAll();
            long cost = System.currentTimeMillis() - startedAt;
            if (outcome.changed()) {
                heartbeat.active();
                log.info("{}完成：{} 条订阅，{} 集入库{}，耗时 {}ms", trigger == null ? "对账" : trigger,
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
