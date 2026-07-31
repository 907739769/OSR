package com.osr.openliststrm.pt.task;

import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.scheduling.TaskScheduler;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * DownloadCompletionSyncTrigger 是纯调度壳子，只负责把 sync 丢到虚拟线程池异步执行，
 * 且内部 try/catch 兜底不向外抛异常。这里直接捕获提交给 scheduler 的 Runnable 并
 * 同步执行，验证异常吞咽行为——写法同 SubscriptionSearchOnCreateTriggerTest。
 */
class DownloadCompletionSyncTriggerTest {

    @Mock
    private DownloadCompletionSyncService syncService;

    @Mock
    private TaskScheduler taskScheduler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private DownloadCompletionSyncTrigger newTrigger() throws Exception {
        DownloadCompletionSyncTrigger trigger;
        try (MockedStatic<SpringUtils> springUtils = org.mockito.Mockito.mockStatic(SpringUtils.class)) {
            springUtils.when(() -> SpringUtils.getBean("virtualScheduledExecutor")).thenReturn(taskScheduler);
            trigger = new DownloadCompletionSyncTrigger();
        }
        Field field = DownloadCompletionSyncTrigger.class.getDeclaredField("syncService");
        field.setAccessible(true);
        field.set(trigger, syncService);
        return trigger;
    }

    private PtDownloadRecordPlus record() {
        PtDownloadRecordPlus r = new PtDownloadRecordPlus();
        r.setId(100);
        return r;
    }

    private PtDownloaderPlus downloader() {
        PtDownloaderPlus d = new PtDownloaderPlus();
        d.setId(1);
        return d;
    }

    @Test
    void triggerAsync_正常提交任务并调用同步() throws Exception {
        DownloadCompletionSyncTrigger trigger = newTrigger();
        PtDownloadRecordPlus record = record();
        PtDownloaderPlus downloader = downloader();

        trigger.triggerAsync(record, downloader);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));
        taskCaptor.getValue().run();

        verify(syncService).sync(record, downloader);
    }

    @Test
    void triggerAsync_同步任务抛异常_不向外传播() throws Exception {
        DownloadCompletionSyncTrigger trigger = newTrigger();
        PtDownloadRecordPlus record = record();
        PtDownloaderPlus downloader = downloader();
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(syncService).sync(record, downloader);

        trigger.triggerAsync(record, downloader);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));

        assertDoesNotThrow(() -> taskCaptor.getValue().run());
        verify(syncService).sync(record, downloader);
    }
}
