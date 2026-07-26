package com.ruoyi.openliststrm.pt.task;

import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 下载完成后的联动同步：立即对账该订阅一次，不必等 {@link LibrarySyncTask} 的下一轮
 * 批量周期（默认 10 分钟）。对账是轻量操作（查 Emby + 内存更新集状态）。
 *
 * @author Jack
 */
@Slf4j
@Service
public class DownloadCompletionSyncService {

    private final SubscriptionService subscriptionService;

    public DownloadCompletionSyncService(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /**
     * 下载完成后立即对账订阅，消除 LibrarySyncTask 的 10 分钟兜底窗口。
     */
    public void sync(PtDownloadRecordPlus record, PtDownloaderPlus downloader) {
        if (downloader == null) {
            return;
        }
        refreshSubscription(record);
    }

    private void refreshSubscription(PtDownloadRecordPlus record) {
        try {
            subscriptionService.refresh(record.getSubId());
        } catch (Exception e) {
            log.warn("下载记录[{}] 完成后提前对账订阅[{}]失败：{}", record.getId(), record.getSubId(), e.getMessage());
        }
    }
}
