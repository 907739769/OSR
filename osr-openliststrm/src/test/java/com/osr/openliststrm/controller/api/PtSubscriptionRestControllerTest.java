package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 0a40bf50 修复的行为：建订阅补搜触发异常不应影响 subscribe() 返回成功，
 * 防止未来重构把 subscribe() 内的两个 try 块又合并回去导致误报建订阅失败。
 */
class PtSubscriptionRestControllerTest {

    @Mock
    private SubscriptionService subscriptionBiz;

    @Mock
    private SubscriptionSearchOnCreateTrigger searchOnCreateTrigger;

    private PtSubscriptionRestController controller;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        controller = new PtSubscriptionRestController();
        inject("subscriptionBiz", subscriptionBiz);
        inject("searchOnCreateTrigger", searchOnCreateTrigger);
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field field = PtSubscriptionRestController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(controller, value);
    }

    private static PtSubscriptionPlus activeSub(Integer id) {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(id);
        sub.setStatus(SubscriptionService.STATUS_ACTIVE);
        return sub;
    }

    @Test
    void subscribe_补搜触发抛异常_建订阅仍返回成功() throws Exception {
        PtSubscriptionPlus sub = activeSub(10);
        when(subscriptionBiz.subscribe(any(SubscribeRequest.class))).thenReturn(sub);
        doThrow(new RuntimeException("boom")).when(searchOnCreateTrigger).triggerAsync(anyInt());

        Result<Void> result = controller.subscribe(new SubscribeRequest());

        assertEquals(200, result.getCode());
        verify(searchOnCreateTrigger).triggerAsync(10);
    }

    @Test
    void subscribe_新订阅为ACTIVE_触发一次补搜() throws Exception {
        PtSubscriptionPlus sub = activeSub(11);
        when(subscriptionBiz.subscribe(any(SubscribeRequest.class))).thenReturn(sub);

        Result<Void> result = controller.subscribe(new SubscribeRequest());

        assertEquals(200, result.getCode());
        verify(searchOnCreateTrigger).triggerAsync(11);
    }

    @Test
    void subscribe_新订阅非ACTIVE_不触发补搜() throws Exception {
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(12);
        sub.setStatus("COMPLETED");
        when(subscriptionBiz.subscribe(any(SubscribeRequest.class))).thenReturn(sub);

        Result<Void> result = controller.subscribe(new SubscribeRequest());

        assertEquals(200, result.getCode());
        verify(searchOnCreateTrigger, org.mockito.Mockito.never()).triggerAsync(anyInt());
    }
}
