package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.common.utils.CurrentUserService;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    /** subscribe() 要取当前登录用户写订阅归属，不打桩会在 BaseController.getUserId() 处 NPE */
    @Mock
    private CurrentUserService currentUserService;

    private PtSubscriptionRestController controller;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        controller = new PtSubscriptionRestController();
        inject("subscriptionBiz", subscriptionBiz);
        inject("searchOnCreateTrigger", searchOnCreateTrigger);
        inject("currentUserService", currentUserService);
        when(currentUserService.getUserId()).thenReturn(7L);
    }

    /** 逐级向上找字段：currentUserService 声明在 BaseController 上，不在控制器自身 */
    private void inject(String fieldName, Object value) throws Exception {
        for (Class<?> type = controller.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(controller, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(fieldName);
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

    /**
     * 归属人必须来自当前登录用户，且要盖掉请求体里带的值——否则任何人都能构造一个
     * ownerUserId 把订阅挂到别人名下，那个人会收到一堆自己没订过的下载通知。
     */
    @Test
    void subscribe_归属人取当前登录用户_忽略请求体传入的值() throws Exception {
        when(subscriptionBiz.subscribe(any(SubscribeRequest.class))).thenReturn(activeSub(13));
        SubscribeRequest request = new SubscribeRequest();
        request.setOwnerUserId(999L);

        controller.subscribe(request);

        ArgumentCaptor<SubscribeRequest> captor = ArgumentCaptor.forClass(SubscribeRequest.class);
        verify(subscriptionBiz).subscribe(captor.capture());
        assertEquals(7L, captor.getValue().getOwnerUserId());
    }
}
