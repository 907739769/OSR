package com.osr.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.utils.Threads;
import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
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
 * 每 30 秒轮询下载器，把种子状态回映到下载记录。
 *
 * @author Jack
 */
@Slf4j
@Component
public class DownloadTrackTask {

    @Autowired
    private IPtDownloaderPlusService downloaderService;
    @Autowired
    private DownloaderClientFactory downloaderClientFactory;
    @Autowired
    private DownloadTrackService trackService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    /** 单轮耗时超过心跳间隔时，避免重叠触发重复轮询所有下载器 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        ThreadTraceIdUtil.initTraceId();
        scheduler.scheduleAtFixedRate(Threads.wrap(this::poll), Instant.now().plusSeconds(30), Duration.ofSeconds(30));
        log.info("DownloadTrackTask started");
    }

    @PreDestroy
    public void stop() {
        log.info("DownloadTrackTask stopped");
        MDC.clear();
    }

    private void poll() {
        if (!running.compareAndSet(false, true)) {
            log.debug("DownloadTrackTask 上一轮尚未结束，跳过本次触发");
            return;
        }
        try {
            List<PtDownloaderPlus> downloaders = downloaderService.list(
                    new QueryWrapper<PtDownloaderPlus>().eq("enabled", "1"));
            for (PtDownloaderPlus downloader : downloaders) {
                try {
                    List<DownloaderTorrent> torrents = downloaderClientFactory.get(downloader)
                            .listByTag(downloader, downloader.getTag());
                    trackService.track(downloader, torrents);
                } catch (Exception e) {
                    log.warn("追踪下载器[{}]失败：{}", downloader.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("DownloadTrackTask poll error", e);
        } finally {
            running.set(false);
        }
    }
}
