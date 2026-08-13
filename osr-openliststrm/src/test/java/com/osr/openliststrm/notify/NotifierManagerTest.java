package com.osr.openliststrm.notify;

import com.osr.openliststrm.mybatisplus.domain.NotifyRoutePlus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotifierManagerTest {

    @Mock private INotifier notifierA;
    @Mock private INotifier notifierB;
    @Mock private NotifyRouteService routeService;

    @BeforeEach
    void setUp() {
        when(notifierA.channelKey()).thenReturn("A");
        when(notifierB.channelKey()).thenReturn("B");
        // 默认两个渠道都不支持分人，需要分人的用例各自覆盖
        when(notifierA.supportsDirectDelivery()).thenReturn(false);
        when(notifierB.supportsDirectDelivery()).thenReturn(false);
    }

    private NotifierManager manager(INotifier... notifiers) {
        return new NotifierManager(List.of(notifiers), routeService);
    }

    private static NotifyRoutePlus route(boolean enabled, String scope) {
        NotifyRoutePlus r = new NotifyRoutePlus();
        r.setEnabled(enabled ? "1" : "0");
        r.setRecipientScope(scope);
        return r;
    }

    // ---------- 路由开关 ----------

    /**
     * 路由表里没有该 (类型, 渠道) 组合时按「发送」处理。
     * 新增通知类型或新增渠道后路由行还没补上时，宁可多发也不能静默丢通知——
     * 静默丢的故障用户根本发现不了。
     */
    @Test
    void 路由缺失_按发送处理() {
        when(routeService.find(any(), anyString())).thenReturn(null);

        manager(notifierA).send(NotificationType.GENERAL, "hello");

        verify(notifierA).send(NotificationType.GENERAL, "hello", NotifyTarget.BROADCAST);
    }

    @Test
    void 路由关闭_跳过该渠道_不影响其他渠道() {
        when(routeService.find(any(), eq("A"))).thenReturn(route(false, NotifyRoutePlus.SCOPE_ADMIN));
        when(routeService.find(any(), eq("B"))).thenReturn(route(true, NotifyRoutePlus.SCOPE_ADMIN));

        manager(notifierA, notifierB).send(NotificationType.GENERAL, "hello");

        verify(notifierA, never()).send(any(), anyString(), any());
        verify(notifierB).send(NotificationType.GENERAL, "hello", NotifyTarget.BROADCAST);
    }

    // ---------- 收件人范围 ----------

    /**
     * 不支持分人的渠道（Telegram/Webhook）无论配了什么 scope 都只能广播。
     * 它们本来就只有一个接收人，假装尊重 scope 会让行为和配置对不上。
     */
    @Test
    void 不支持分人的渠道_任何scope都退化为广播() {
        when(routeService.find(any(), anyString())).thenReturn(route(true, NotifyRoutePlus.SCOPE_OWNER));

        manager(notifierA).send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.owner(42L));

        verify(notifierA).send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.BROADCAST);
    }

    @Test
    void 支持分人_ADMIN档_忽略归属只发默认接收人() {
        when(notifierA.supportsDirectDelivery()).thenReturn(true);
        when(routeService.find(any(), anyString())).thenReturn(route(true, NotifyRoutePlus.SCOPE_ADMIN));

        manager(notifierA).send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.owner(42L));

        verify(notifierA).send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.BROADCAST);
    }

    @Test
    void 支持分人_OWNER档_只发归属人() {
        when(notifierA.supportsDirectDelivery()).thenReturn(true);
        when(routeService.find(any(), anyString())).thenReturn(route(true, NotifyRoutePlus.SCOPE_OWNER));

        manager(notifierA).send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.owner(42L));

        verify(notifierA).send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.owner(42L));
    }

    /**
     * 系统级告警（索引器故障、复制超时）没有归属人。OWNER 档如果理解成「无归属就丢弃」，
     * 这类告警会静默消失——这是本次设计里最容易做错的一处，所以单独钉一条。
     */
    @Test
    void 支持分人_OWNER档_无归属时回退默认接收人而不是丢弃() {
        when(notifierA.supportsDirectDelivery()).thenReturn(true);
        when(routeService.find(any(), anyString())).thenReturn(route(true, NotifyRoutePlus.SCOPE_OWNER));

        manager(notifierA).send(NotificationType.GENERAL, "索引器挂了", NotifyTarget.BROADCAST);

        verify(notifierA).send(NotificationType.GENERAL, "索引器挂了", NotifyTarget.BROADCAST);
    }

    @Test
    void 支持分人_BOTH档_归属人加默认接收人() {
        when(notifierA.supportsDirectDelivery()).thenReturn(true);
        when(routeService.find(any(), anyString())).thenReturn(route(true, NotifyRoutePlus.SCOPE_BOTH));

        manager(notifierA).send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.owner(42L));

        verify(notifierA).send(NotificationType.DOWNLOAD_COMPLETE, "done", NotifyTarget.ownerAndDefault(42L));
    }

    // ---------- 健壮性 ----------

    @Test
    void 目标为null_按广播处理() {
        when(routeService.find(any(), anyString())).thenReturn(null);

        manager(notifierA).send(NotificationType.GENERAL, "hello", null);

        verify(notifierA).send(NotificationType.GENERAL, "hello", NotifyTarget.BROADCAST);
    }

    @Test
    void 某个渠道抛异常_不影响其余渠道继续发送() {
        when(routeService.find(any(), anyString())).thenReturn(null);
        doThrow(new RuntimeException("boom")).when(notifierA)
                .send(NotificationType.GENERAL, "hello", NotifyTarget.BROADCAST);

        manager(notifierA, notifierB).send(NotificationType.GENERAL, "hello");

        verify(notifierB).send(NotificationType.GENERAL, "hello", NotifyTarget.BROADCAST);
    }

    /** 路由查询本身炸了也不能让通知链路挂掉 */
    @Test
    void 路由查询抛异常_不影响发送() {
        when(routeService.find(any(), anyString())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> manager(notifierA).send(NotificationType.GENERAL, "hello"));
    }

    @Test
    void 空渠道列表_不抛异常() {
        assertDoesNotThrow(() -> manager().send(NotificationType.GENERAL, "hello"));
    }
}
