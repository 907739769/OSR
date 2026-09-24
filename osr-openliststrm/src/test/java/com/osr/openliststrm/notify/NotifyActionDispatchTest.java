package com.osr.openliststrm.notify;

import com.osr.openliststrm.mybatisplus.domain.NotifyRoutePlus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知上的快捷操作：带操作时才走四参数重载，不带时与引入之前逐字节一致；
 * 路由关掉的渠道带着操作也照样不发。
 */
class NotifyActionDispatchTest {

    private final INotifier notifier = mock(INotifier.class);
    private final NotifyRouteService routeService = mock(NotifyRouteService.class);
    private final NotifierManager manager = new NotifierManager(List.of(notifier), routeService);

    {
        when(notifier.channelKey()).thenReturn("TELEGRAM");
    }

    @Test
    void 没有操作_仍调三参数send() {
        manager.send(NotificationType.DOWNLOAD_FAILED, "msg", NotifyTarget.BROADCAST, List.of());

        verify(notifier).send(eq(NotificationType.DOWNLOAD_FAILED), eq("msg"), any(NotifyTarget.class));
        verify(notifier, never()).send(any(), anyString(), any(), anyList());
    }

    @Test
    void 带操作_调四参数send并原样传下去() {
        List<NotifyAction> actions = List.of(NotifyAction.retryDownload(7));

        manager.send(NotificationType.DOWNLOAD_FAILED, "msg", NotifyTarget.BROADCAST, actions);

        verify(notifier).send(eq(NotificationType.DOWNLOAD_FAILED), eq("msg"), any(NotifyTarget.class), eq(actions));
    }

    @Test
    void 路由关掉的渠道_带操作也不发() {
        NotifyRoutePlus off = new NotifyRoutePlus();
        off.setEnabled("0");
        when(routeService.find(NotificationType.DOWNLOAD_FAILED, "TELEGRAM")).thenReturn(off);

        manager.send(NotificationType.DOWNLOAD_FAILED, "msg", NotifyTarget.BROADCAST, List.of(NotifyAction.retryDownload(7)));

        verify(notifier, never()).send(any(), anyString(), any(), anyList());
    }

    /** 企微文本消息没有按钮：操作写成「可直接回复」的指令，指令文本必须与聊天指令完全一致 */
    @Test
    void 企微提示_逐行列出可回复的指令() {
        String hint = WeComNotifier.actionHint(List.of(NotifyAction.searchMissing(3), NotifyAction.progress(3)));

        assertTrue(hint.startsWith("\n\n可直接回复："), hint);
        assertTrue(hint.contains("\n补搜 3　立即补搜"), hint);
        assertTrue(hint.contains("\n进度 3　查看进度"), hint);
        assertEquals("", WeComNotifier.actionHint(List.of()));
    }
}
