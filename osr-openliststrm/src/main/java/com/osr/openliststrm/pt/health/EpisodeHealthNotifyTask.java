package com.osr.openliststrm.pt.health;

import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 逾期缺集提醒的心跳。
 *
 * @author Jack
 */
@Slf4j
@Component
public class EpisodeHealthNotifyTask {

    /**
     * 一天一轮。
     * <p>
     * 不需要更密：判据里最细的粒度是"播出天数"，一天之内扫多少遍结论都一样。
     * 真正的提醒频率由 {@code EpisodeHealthNotifyService} 的指纹去重与重提醒周期决定，
     * 心跳只负责让状态变化最迟一天内被发现。
     * </p>
     */
    private static final Duration INTERVAL = Duration.ofHours(24);

    /**
     * 首次延迟。刻意排在 {@code EpisodeAirDateSyncTask}（240 秒）之后：
     * 那一轮兼做存量播出日期的回填，先跑完再体检，升级后的第一次提醒才是基于真实日期的。
     * <p>
     * 即便回填还没跑完也不会误报——没有 {@code air_date} 的集落在
     * {@link EpisodeHealthBucket#NO_AIR_DATE} 一档，而提醒只发逾期缺失那一档。
     * 这里的排序换来的是"第一次提醒就是完整的"，不是"第一次提醒不会出错"。
     * </p>
     */
    private static final Duration INITIAL_DELAY = Duration.ofSeconds(600);

    private final EpisodeHealthNotifyService notifyService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    private final AtomicBoolean running = new AtomicBoolean(false);

    public EpisodeHealthNotifyTask(EpisodeHealthNotifyService notifyService) {
        this.notifyService = notifyService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::run), Instant.now().plus(INITIAL_DELAY), INTERVAL);
        log.info("EpisodeHealthNotifyTask started, interval={}", INTERVAL);
    }

    @PreDestroy
    public void stop() {
        log.info("EpisodeHealthNotifyTask stopped");
        MDC.clear();
    }

    private void run() {
        if (!running.compareAndSet(false, true)) {
            log.debug("EpisodeHealthNotifyTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            int sent = notifyService.notifyOverdue();
            if (sent > 0) {
                log.info("逾期缺集提醒完成，共发出 {} 条", sent);
            }
        } catch (Exception e) {
            log.error("EpisodeHealthNotifyTask run error", e);
        } finally {
            running.set(false);
        }
    }
}
