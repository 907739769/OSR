package com.osr.openliststrm.pt.subscription;

import com.osr.common.utils.spring.SpringUtils;
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
 * SubscriptionSearchOnCreateTrigger 是纯调度壳子，只负责把 supplementOnCreate 丢到虚拟线程池
 * 异步执行，且内部 try/catch 兜底不向外抛异常。这里直接捕获提交给 scheduler 的 Runnable 并
 * 同步执行，验证异常吞咽行为。
 */
class SubscriptionSearchOnCreateTriggerTest {

    @Mock
    private SearchSupplementService searchSupplementService;

    @Mock
    private TaskScheduler taskScheduler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private SubscriptionSearchOnCreateTrigger newTrigger() throws Exception {
        SubscriptionSearchOnCreateTrigger trigger;
        try (MockedStatic<SpringUtils> springUtils = mockStatic()) {
            springUtils.when(() -> SpringUtils.getBean("virtualScheduledExecutor")).thenReturn(taskScheduler);
            trigger = new SubscriptionSearchOnCreateTrigger();
        }
        Field field = SubscriptionSearchOnCreateTrigger.class.getDeclaredField("searchSupplementService");
        field.setAccessible(true);
        field.set(trigger, searchSupplementService);
        return trigger;
    }

    private static MockedStatic<SpringUtils> mockStatic() {
        return org.mockito.Mockito.mockStatic(SpringUtils.class);
    }

    @Test
    void triggerAsync_正常提交任务并调用补搜() throws Exception {
        SubscriptionSearchOnCreateTrigger trigger = newTrigger();

        trigger.triggerAsync(10);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));
        taskCaptor.getValue().run();

        verify(searchSupplementService).supplementOnCreate(10);
    }

    @Test
    void triggerAsync_补搜任务抛异常_不向外传播() throws Exception {
        SubscriptionSearchOnCreateTrigger trigger = newTrigger();
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(searchSupplementService).supplementOnCreate(10);

        trigger.triggerAsync(10);

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(taskCaptor.capture(), any(Instant.class));

        assertDoesNotThrow(() -> taskCaptor.getValue().run());
        verify(searchSupplementService).supplementOnCreate(10);
    }
}
