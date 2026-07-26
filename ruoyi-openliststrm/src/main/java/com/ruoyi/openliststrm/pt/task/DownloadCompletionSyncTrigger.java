package com.ruoyi.openliststrm.pt.task;

import com.ruoyi.common.utils.Threads;
import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 下载完成后触发 STRM 联动同步的调度壳子：只负责把
 * {@link DownloadCompletionSyncService#sync} 丢到虚拟线程异步执行，立即返回不等待——
 * {@code strmDir} 是网络 I/O，可能较慢，不能阻塞 {@link DownloadTrackTask} 的轮询本身。
 * 风格同 {@code pt.subscription.SubscriptionSearchOnCreateTrigger}。
 *
 * @author Jack
 */
@Slf4j
@Component
public class DownloadCompletionSyncTrigger {

    @Autowired
    private DownloadCompletionSyncService syncService;

    private final TaskScheduler scheduler = SpringUtils.getBean("virtualScheduledExecutor");

    public void triggerAsync(PtDownloadRecordPlus record, PtDownloaderPlus downloader) {
        scheduler.schedule(Threads.wrap(() -> {
            try {
                syncService.sync(record, downloader);
            } catch (Exception e) {
                log.warn("下载记录[{}] 完成后联动同步失败：{}", record.getId(), e.getMessage());
            }
        }), Instant.now());
    }
}
