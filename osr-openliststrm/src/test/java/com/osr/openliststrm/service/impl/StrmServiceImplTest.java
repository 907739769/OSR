package com.osr.openliststrm.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import com.osr.openliststrm.service.IStrmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StrmServiceImplTest {

    @Mock
    private IOpenlistStrmPlusService openlistStrmPlusService;

    private StrmServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new StrmServiceImpl();
        ReflectionTestUtils.setField(service, "openlistStrmPlusService", openlistStrmPlusService);
    }

    @Test
    void retryAllFailed_没有失败记录_返回0且不触发重试() {
        when(openlistStrmPlusService.count(any(Wrapper.class))).thenReturn(0L);

        IStrmService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(0, outcome.retried());
        assertEquals(0, outcome.remaining());
        verify(openlistStrmPlusService, never()).list(any(Wrapper.class));
        verify(openlistStrmPlusService, never()).listByIds(any());
    }

    @Test
    void retryAllFailed_失败记录未超上限_全部提交重试且remaining为0() {
        when(openlistStrmPlusService.count(any(Wrapper.class))).thenReturn(2L);
        OpenlistStrmPlus a = new OpenlistStrmPlus();
        a.setStrmId(5);
        OpenlistStrmPlus b = new OpenlistStrmPlus();
        b.setStrmId(3);
        when(openlistStrmPlusService.list(any(Wrapper.class))).thenReturn(List.of(a, b));
        // retryStrm 内部会再查一次 listByIds 取完整记录；返回空列表即可让内部的异步重试分支安全跑完，
        // 不需要真的执行网络请求，本测试只关心 retryAllFailed 自己的查询与转发逻辑
        when(openlistStrmPlusService.listByIds(any())).thenReturn(List.of());

        IStrmService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(2, outcome.retried());
        assertEquals(0, outcome.remaining());
        verify(openlistStrmPlusService).listByIds(eq(List.of("5", "3")));
    }

    @Test
    void retryAllFailed_失败记录超过200条上限_只取最新200条且remaining正确() {
        when(openlistStrmPlusService.count(any(Wrapper.class))).thenReturn(250L);
        OpenlistStrmPlus a = new OpenlistStrmPlus();
        a.setStrmId(9);
        when(openlistStrmPlusService.list(any(Wrapper.class))).thenReturn(List.of(a));
        when(openlistStrmPlusService.listByIds(any())).thenReturn(List.of());

        IStrmService.RetryOutcome outcome = service.retryAllFailed();

        assertEquals(1, outcome.retried());
        assertEquals(249, outcome.remaining());
    }
}
