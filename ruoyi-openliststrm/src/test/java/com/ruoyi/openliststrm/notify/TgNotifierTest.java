package com.ruoyi.openliststrm.notify;

import com.ruoyi.openliststrm.config.OpenlistConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TgNotifierTest {

    @Mock private OpenlistConfig config;

    @Test
    void send_token为空_不构造TgSendMsg也不抛异常() {
        when(config.getOpenListTgToken()).thenReturn("");
        when(config.getOpenListTgUserId()).thenReturn("user-1");
        TgNotifier notifier = new TgNotifier(config);

        assertDoesNotThrow(() -> notifier.send("hello"));

        assertNull(notifier.cachedBot);
    }

    @Test
    void send_userId为空_不构造TgSendMsg也不抛异常() {
        when(config.getOpenListTgToken()).thenReturn("token-1");
        when(config.getOpenListTgUserId()).thenReturn("");
        TgNotifier notifier = new TgNotifier(config);

        assertDoesNotThrow(() -> notifier.send("hello"));

        assertNull(notifier.cachedBot);
    }

    @Test
    void send_两者都为空_不构造TgSendMsg也不抛异常() {
        when(config.getOpenListTgToken()).thenReturn(null);
        when(config.getOpenListTgUserId()).thenReturn(null);
        TgNotifier notifier = new TgNotifier(config);

        assertDoesNotThrow(() -> notifier.send("hello"));

        assertNull(notifier.cachedBot);
    }
}
