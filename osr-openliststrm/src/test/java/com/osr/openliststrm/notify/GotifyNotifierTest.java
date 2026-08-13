package com.osr.openliststrm.notify;

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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GotifyNotifierTest {

    @Mock
    private OpenlistConfig config;

    private MockWebServer server;
    private GotifyNotifier notifier;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        notifier = new GotifyNotifier(config, new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void givenConfigured() {
        when(config.getNotifyGotifyUrl()).thenReturn(server.url("/").toString());
        when(config.getNotifyGotifyToken()).thenReturn("A1b2C3");
    }

    /** 只配一半的话请求必然 401，与其每次发通知都打一次注定失败的请求，不如静默跳过 */
    @Test
    void 只配地址没配token_不发请求() {
        when(config.getNotifyGotifyUrl()).thenReturn(server.url("/").toString());
        when(config.getNotifyGotifyToken()).thenReturn("");

        notifier.send(NotificationType.GENERAL, "hello");

        assertEquals(0, server.getRequestCount());
    }

    @Test
    void 只配token没配地址_不发请求() {
        when(config.getNotifyGotifyUrl()).thenReturn("");
        when(config.getNotifyGotifyToken()).thenReturn("A1b2C3");

        notifier.send(NotificationType.GENERAL, "hello");

        assertEquals(0, server.getRequestCount());
    }

    @Test
    void 正常发送_POST到message且token在查询串() throws Exception {
        givenConfigured();
        server.enqueue(new MockResponse().setResponseCode(200));

        notifier.send(NotificationType.DOWNLOAD_COMPLETE, "下载完成");

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/message?token=A1b2C3", req.getPath());
        JSONObject body = JSONObject.parseObject(req.getBody().readUtf8());
        assertEquals("OSR", body.getString("title"));
        assertEquals("下载完成", body.getString("message"));
    }

    /** 用户常常连域名带末尾斜杠一起粘贴，不能拼出 //message */
    @Test
    void 地址末尾多余斜杠_不产生双斜杠() throws Exception {
        when(config.getNotifyGotifyUrl()).thenReturn(server.url("/").toString());
        when(config.getNotifyGotifyToken()).thenReturn("tok");
        server.enqueue(new MockResponse().setResponseCode(200));

        notifier.send(NotificationType.GENERAL, "hi");

        assertTrue(server.takeRequest().getPath().startsWith("/message?"), "不应出现 //message");
    }

    @Test
    void HTML标签被清洗() throws Exception {
        givenConfigured();
        server.enqueue(new MockResponse().setResponseCode(200));

        notifier.send(NotificationType.GENERAL, "<b>复制任务失败</b>");

        JSONObject body = JSONObject.parseObject(server.takeRequest().getBody().readUtf8());
        assertEquals("复制任务失败", body.getString("message"));
    }

    @Test
    void 地址非法_不抛异常也不发请求() {
        when(config.getNotifyGotifyUrl()).thenReturn("这不是URL");
        when(config.getNotifyGotifyToken()).thenReturn("tok");

        assertDoesNotThrow(() -> notifier.send(NotificationType.GENERAL, "hello"));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void 服务端返回错误_不抛异常() {
        givenConfigured();
        server.enqueue(new MockResponse().setResponseCode(401));

        assertDoesNotThrow(() -> notifier.send(NotificationType.GENERAL, "hello"));
    }

    @Test
    void 渠道元信息_token对应应用而非用户所以不支持分人() {
        assertEquals("GOTIFY", notifier.channelKey());
        assertFalse(notifier.supportsDirectDelivery());
    }
}
