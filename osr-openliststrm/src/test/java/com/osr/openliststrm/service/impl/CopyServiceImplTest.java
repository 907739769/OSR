package com.osr.openliststrm.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.osr.openliststrm.service.ICopyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CopyServiceImplTest {

    @Mock
    private IOpenlistCopyPlusService openlistCopyPlusService;

    @InjectMocks
    private CopyServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void retryAllFailed_没有失败记录_返回0且不触发重试() {
        when(openlistCopyPlusService.count(any(Wrapper.class))).thenReturn(0L);

        ICopyService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(0, outcome.retried());
        assertEquals(0, outcome.remaining());
        verify(openlistCopyPlusService, never()).list(any(Wrapper.class));
        verify(openlistCopyPlusService, never()).listByIds(any());
    }

    @Test
    void retryAllFailed_失败记录未超上限_全部提交重试且remaining为0() {
        when(openlistCopyPlusService.count(any(Wrapper.class))).thenReturn(2L);
        OpenlistCopyPlus a = record(7, "2");
        OpenlistCopyPlus b = record(4, "4");
        when(openlistCopyPlusService.list(any(Wrapper.class))).thenReturn(List.of(a, b));
        // 监控超时/任务丢失（4）与失败（2）同样计入
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of(a, b));

        ICopyService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(2, outcome.retried());
        assertEquals(0, outcome.remaining());
        verify(openlistCopyPlusService).listByIds(eq(List.of("7", "4")));
    }

    @Test
    void retryAllFailed_失败记录超过200条上限_只取最新200条且remaining正确() {
        when(openlistCopyPlusService.count(any(Wrapper.class))).thenReturn(300L);
        OpenlistCopyPlus a = record(1, "2");
        when(openlistCopyPlusService.list(any(Wrapper.class))).thenReturn(List.of(a));
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of(a));

        ICopyService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(1, outcome.retried());
        assertEquals(299, outcome.remaining());
    }

    @Test
    void retryAllFailed_查询后状态已被改掉的记录不计入已提交数() {
        // 查出两条失败记录，到重试时其中一条已被监控或兜底任务改成成功
        when(openlistCopyPlusService.count(any(Wrapper.class))).thenReturn(2L);
        when(openlistCopyPlusService.list(any(Wrapper.class))).thenReturn(List.of(record(1, "2"), record(2, "2")));
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of(record(1, "2"), record(2, "3")));

        ICopyService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(1, outcome.retried());
        assertEquals(0, outcome.remaining());
    }

    private static OpenlistCopyPlus record(int id, String status) {
        OpenlistCopyPlus copy = new OpenlistCopyPlus();
        copy.setCopyId(id);
        copy.setCopyStatus(status);
        return copy;
    }
}
