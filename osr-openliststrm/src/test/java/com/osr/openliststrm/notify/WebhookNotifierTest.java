package com.osr.openliststrm.notify;

import com.osr.openliststrm.config.OpenlistConfig;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookNotifierTest {

    @Mock private OpenlistConfig config;

    private MockWebServer server;
    private WebhookNotifier notifier;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        notifier = new WebhookNotifier(config, new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void send_url未配置_不发起任何请求() {
        when(config.getNotifyWebhookUrl()).thenReturn("");

        notifier.send(NotificationType.GENERAL, "hello");

        assertEquals(0, server.getRequestCount());
    }

    @Test
    void send_url已配置_发起POST请求且请求体正确() throws Exception {
        when(config.getNotifyWebhookUrl()).thenReturn(server.url("/hook").toString());
        server.enqueue(new MockResponse().setResponseCode(200));

        notifier.send(NotificationType.GENERAL, "hello world");

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getHeader("Content-Type").startsWith("application/json"));
        assertEquals("{\"text\":\"hello world\"}", request.getBody().readUtf8());
    }

    @Test
    void send_服务端返回5xx_不抛出异常() {
        when(config.getNotifyWebhookUrl()).thenReturn(server.url("/hook").toString());
        server.enqueue(new MockResponse().setResponseCode(500));

        assertDoesNotThrow(() -> notifier.send(NotificationType.GENERAL, "hello"));
    }

    @Test
    void send_连接失败_不抛出异常() throws IOException {
        MockWebServer deadServer = new MockWebServer();
        deadServer.start();
        String url = deadServer.url("/hook").toString();
        deadServer.shutdown();
        when(config.getNotifyWebhookUrl()).thenReturn(url);

        assertDoesNotThrow(() -> notifier.send(NotificationType.GENERAL, "hello"));
    }
}
