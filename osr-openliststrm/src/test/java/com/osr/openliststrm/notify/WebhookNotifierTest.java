package com.osr.openliststrm.notify;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
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
        assertEquals("{\"text\":\"hello world\",\"type\":\"GENERAL\",\"typeLabel\":\"系统告警\"}",
                request.getBody().readUtf8());
    }

    /**
     * 文案是按 Telegram 的 HTML parse_mode 写的，Webhook 的下游不解析 HTML。
     * 尤其是 &amp;——PT 种子标题里的 & 相当常见，透传出去下游存到哪都是错的。
     */
    @Test
    void send_文案里的HTML标签与实体_都还原成纯文本() throws Exception {
        when(config.getNotifyWebhookUrl()).thenReturn(server.url("/hook").toString());
        server.enqueue(new MockResponse().setResponseCode(200));

        notifier.send(NotificationType.DOWNLOAD_COMPLETE, "<b>完成</b>：Tom &amp; Jerry");

        JSONObject body = JSON.parseObject(server.takeRequest().getBody().readUtf8());
        assertEquals("完成：Tom & Jerry", body.getString("text"));
        assertEquals("DOWNLOAD_COMPLETE", body.getString("type"));
        assertEquals("下载完成", body.getString("typeLabel"));
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
