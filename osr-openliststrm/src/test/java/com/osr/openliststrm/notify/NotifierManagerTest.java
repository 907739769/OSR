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

    @Test
    void send_遍历所有渠道_全部收到相同消息() {
        NotifierManager manager = new NotifierManager(List.of(notifierA, notifierB));

        manager.send("hello");

        verify(notifierA).send("hello");
        verify(notifierB).send("hello");
    }

    @Test
    void send_某个渠道抛异常_不影响其余渠道继续发送() {
        doThrow(new RuntimeException("boom")).when(notifierA).send("hello");
        NotifierManager manager = new NotifierManager(List.of(notifierA, notifierB));

        manager.send("hello");

        verify(notifierB).send("hello");
    }

    @Test
    void send_空渠道列表_不抛异常() {
        NotifierManager manager = new NotifierManager(List.of());

        assertDoesNotThrow(() -> manager.send("hello"));
    }
}
