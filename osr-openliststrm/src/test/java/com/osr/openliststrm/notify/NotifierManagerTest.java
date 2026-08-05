package com.osr.openliststrm.notify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotifierManagerTest {

    @Mock private INotifier notifierA;
    @Mock private INotifier notifierB;

    /**
     * 分发一律走三参 {@code send(type, msg, target)}，两参重载只是把目标补成
     * {@link NotifyTarget#BROADCAST} 后转调。渠道侧只需实现两参版本，三参有默认实现兜底。
     */
    @Test
    void send_遍历所有渠道_全部收到相同消息() {
        NotifierManager manager = new NotifierManager(List.of(notifierA, notifierB));

        manager.send(NotificationType.GENERAL, "hello");

        verify(notifierA).send(NotificationType.GENERAL, "hello", NotifyTarget.BROADCAST);
        verify(notifierB).send(NotificationType.GENERAL, "hello", NotifyTarget.BROADCAST);
    }

    @Test
    void send_带投递目标_原样传给各渠道() {
        NotifierManager manager = new NotifierManager(List.of(notifierA, notifierB));
        NotifyTarget target = NotifyTarget.owner(42L);

        manager.send(NotificationType.DOWNLOAD_COMPLETE, "done", target);

        verify(notifierA).send(NotificationType.DOWNLOAD_COMPLETE, "done", target);
        verify(notifierB).send(NotificationType.DOWNLOAD_COMPLETE, "done", target);
    }

    /** target 传 null 不能把 NPE 抛给业务调用方，按广播处理 */
    @Test
    void send_目标为null_按广播处理() {
        NotifierManager manager = new NotifierManager(List.of(notifierA));

        manager.send(NotificationType.GENERAL, "hello", null);

        verify(notifierA).send(NotificationType.GENERAL, "hello", NotifyTarget.BROADCAST);
    }

    @Test
    void send_某个渠道抛异常_不影响其余渠道继续发送() {
        doThrow(new RuntimeException("boom")).when(notifierA)
                .send(NotificationType.GENERAL, "hello", NotifyTarget.BROADCAST);
        NotifierManager manager = new NotifierManager(List.of(notifierA, notifierB));

        manager.send(NotificationType.GENERAL, "hello");

        verify(notifierB).send(NotificationType.GENERAL, "hello", NotifyTarget.BROADCAST);
    }

    @Test
    void send_空渠道列表_不抛异常() {
        NotifierManager manager = new NotifierManager(List.of());

        assertDoesNotThrow(() -> manager.send(NotificationType.GENERAL, "hello"));
    }
}
