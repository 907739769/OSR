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
        OpenlistCopyPlus a = new OpenlistCopyPlus();
        a.setCopyId(7);
        OpenlistCopyPlus b = new OpenlistCopyPlus();
        b.setCopyId(4);
        when(openlistCopyPlusService.list(any(Wrapper.class))).thenReturn(List.of(a, b));
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of());

        ICopyService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(2, outcome.retried());
        assertEquals(0, outcome.remaining());
        verify(openlistCopyPlusService).listByIds(eq(List.of("7", "4")));
    }

    @Test
    void retryAllFailed_失败记录超过200条上限_只取最新200条且remaining正确() {
        when(openlistCopyPlusService.count(any(Wrapper.class))).thenReturn(300L);
        OpenlistCopyPlus a = new OpenlistCopyPlus();
        a.setCopyId(1);
        when(openlistCopyPlusService.list(any(Wrapper.class))).thenReturn(List.of(a));
        when(openlistCopyPlusService.listByIds(any())).thenReturn(List.of());

        ICopyService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(1, outcome.retried());
        assertEquals(299, outcome.remaining());
    }
}
