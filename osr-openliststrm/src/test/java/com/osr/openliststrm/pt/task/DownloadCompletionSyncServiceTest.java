package com.osr.openliststrm.pt.task;

import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DownloadCompletionSyncServiceTest {

    @Mock private SubscriptionService subscriptionService;

    private DownloadCompletionSyncService service() {
        return new DownloadCompletionSyncService(subscriptionService);
    }

    private PtDownloadRecordPlus record() {
        PtDownloadRecordPlus r = new PtDownloadRecordPlus();
        r.setId(100);
        r.setSubId(10);
        r.setTitle("Some.Show.S01E02");
        return r;
    }

    private PtDownloaderPlus downloader() {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(1);
        return d;
    }

    @Test
    void 下载器为空_不做任何事() {
        service().sync(record(), null);

        verify(subscriptionService, never()).refresh(anyInt());
    }

    @Test
    void 下载器存在_对账订阅() {
        service().sync(record(), downloader());

        verify(subscriptionService).refresh(eq(10));
    }

    @Test
    void 对账抛异常_不向外抛异常() {
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(subscriptionService).refresh(anyInt());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service().sync(record(), downloader()));
    }
}
