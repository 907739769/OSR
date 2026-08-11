package com.osr.openliststrm.rename;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.scheduling.TaskScheduler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RenameTaskManagerTest {

    @Mock
    private IRenameDetailPlusService renameDetailService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    /**
     * RenameTaskManager 的字段初始化器会调用 SpringUtils.getBean("virtualScheduledExecutor")，
     * 必须在 mockStatic 作用域内完成构造，构造完再用反射注入 mock 的 renameDetailService。
     */
    private RenameTaskManager newService() throws Exception {
        RenameTaskManager service;
        try (MockedStatic<SpringUtils> springUtils = mockStatic(SpringUtils.class)) {
            springUtils.when(() -> SpringUtils.getBean("virtualScheduledExecutor")).thenReturn(mock(TaskScheduler.class));
            service = new RenameTaskManager();
        }
        Field field = RenameTaskManager.class.getDeclaredField("renameDetailService");
        field.setAccessible(true);
        field.set(service, renameDetailService);
        return service;
    }

    @Test
    void retryAllFailed_没有失败记录_返回0() throws Exception {
        RenameTaskManager service = newService();
        when(renameDetailService.count(any())).thenReturn(0L);

        RenameTaskManager.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(0, outcome.retried());
        assertEquals(0, outcome.remaining());
        verify(renameDetailService, never()).list(any(Wrapper.class));
        verify(renameDetailService, never()).getById(any());
    }

    @Test
    void retryAllFailed_有失败记录_对每条调用getById触发重新执行() throws Exception {
        RenameTaskManager service = newService();
        when(renameDetailService.count(any())).thenReturn(2L);
        RenameDetailPlus a = new RenameDetailPlus();
        a.setId(5);
        RenameDetailPlus b = new RenameDetailPlus();
        b.setId(3);
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(a, b));
        // getById 返回 null 时 executeRenameDetails 会记日志后直接返回，不会碰文件系统，
        // 本测试只关心 retryAllFailed 是否正确对每条失败记录发起了重新执行
        when(renameDetailService.getById(any())).thenReturn(null);

        RenameTaskManager.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(2, outcome.retried());
        assertEquals(0, outcome.remaining());
        verify(renameDetailService).getById(5);
        verify(renameDetailService).getById(3);
    }

    @Test
    void retryAllFailed_单条记录抛异常不影响其余记录处理() throws Exception {
        RenameTaskManager service = newService();
        when(renameDetailService.count(any())).thenReturn(2L);
        RenameDetailPlus a = new RenameDetailPlus();
        a.setId(5);
        RenameDetailPlus b = new RenameDetailPlus();
        b.setId(3);
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(a, b));
        when(renameDetailService.getById(5)).thenThrow(new RuntimeException("模拟异常"));
        when(renameDetailService.getById(3)).thenReturn(null);

        RenameTaskManager.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(2, outcome.retried());
        verify(renameDetailService).getById(5);
        verify(renameDetailService).getById(3);
    }

    @Test
    void retryAllFailed_超过异步阈值_不在调用线程上执行() throws Exception {
        // 唯一调用方是 TG 的 /retry，它要等本方法返回才发汇总回复。
        // 同步跑 200 条（每条查 TMDb + 复制 + 刮削）会让用户静默等几十分钟。
        RenameTaskManager service = spy(newService());
        doNothing().when(service).submitAsync(any());

        when(renameDetailService.count(any())).thenReturn(50L);
        List<RenameDetailPlus> failed = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            RenameDetailPlus d = new RenameDetailPlus();
            d.setId(i);
            failed.add(d);
        }
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(failed);

        RenameTaskManager.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(21, outcome.retried(), "retried 是「已提交」条数，不是「已完成」");
        assertEquals(29, outcome.remaining());
        verify(service).submitAsync(any());
        verify(renameDetailService, never()).getById(any());
    }

    @Test
    void retryAllFailed_未超过异步阈值_仍在调用线程上同步跑完() throws Exception {
        // 小批量留在调用线程上，TG 回复反映的才是真实完成情况
        RenameTaskManager service = spy(newService());
        when(renameDetailService.count(any())).thenReturn(2L);
        RenameDetailPlus a = new RenameDetailPlus();
        a.setId(5);
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(a));
        when(renameDetailService.getById(any())).thenReturn(null);

        service.retryAllFailed();

        verify(service, never()).submitAsync(any());
        verify(renameDetailService).getById(5);
    }

    @Test
    void retryAllFailed_失败记录超过200条上限_remaining正确() throws Exception {
        RenameTaskManager service = newService();
        when(renameDetailService.count(any())).thenReturn(210L);
        RenameDetailPlus a = new RenameDetailPlus();
        a.setId(1);
        when(renameDetailService.list(any(Wrapper.class))).thenReturn(List.of(a));
        when(renameDetailService.getById(any())).thenReturn(null);

        RenameTaskManager.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(1, outcome.retried());
        assertEquals(209, outcome.remaining());
    }
}
