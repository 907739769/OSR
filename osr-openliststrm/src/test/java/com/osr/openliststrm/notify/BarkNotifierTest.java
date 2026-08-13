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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BarkNotifierTest {

    @Mock
    private OpenlistConfig config;

    private MockWebServer server;
    private BarkNotifier notifier;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        notifier = new BarkNotifier(config, new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private void givenUrl(String suffix) {
        when(config.getNotifyBarkUrl()).thenReturn(server.url(suffix).toString());
    }

    private String decodedPath(RecordedRequest req) {
        return URLDecoder.decode(req.getPath(), StandardCharsets.UTF_8);
    }

    @Test
    void 未配置地址_不发请求() {
        when(config.getNotifyBarkUrl()).thenReturn("");

        notifier.send(NotificationType.GENERAL, "hello");

        assertEquals(0, server.getRequestCount());
    }

    @Test
    void 消息为空_不发请求() {
        givenUrl("/mykey");

        notifier.send(NotificationType.GENERAL, "  ");

        assertEquals(0, server.getRequestCount());
    }

    @Test
    void 正常发送_标题与正文各占一段路径() throws Exception {
        givenUrl("/mykey");
        server.enqueue(new MockResponse().setResponseCode(200));

        notifier.send(NotificationType.DOWNLOAD_COMPLETE, "下载完成");

        RecordedRequest req = server.takeRequest();
        assertEquals("GET", req.getMethod());
        assertEquals("/mykey/OSR/下载完成", decodedPath(req));
    }

    /**
     * 通知文案里带网盘路径是常态（复制失败 /电影/沙丘）。斜杠若不转义会被当成路径分隔符，
     * Bark 收到的就是一条结构错乱的 URL——这正是不用字符串拼接、改用 addPathSegment 的原因。
     */
    @Test
    void 消息里的斜杠被转义_不撑断URL结构() throws Exception {
        givenUrl("/mykey");
        server.enqueue(new MockResponse().setResponseCode(200));

        notifier.send(NotificationType.GENERAL, "复制失败 /电影/沙丘");

        RecordedRequest req = server.takeRequest();
        assertTrue(req.getPath().contains("%2F"), "斜杠应被转义，实际路径=" + req.getPath());
        assertEquals("/mykey/OSR/复制失败 /电影/沙丘", decodedPath(req));
    }

    @Test
    void 地址末尾多余斜杠_不产生空路径段() throws Exception {
        givenUrl("/mykey/");
        server.enqueue(new MockResponse().setResponseCode(200));

        notifier.send(NotificationType.GENERAL, "hi");

        assertEquals("/mykey/OSR/hi", decodedPath(server.takeRequest()));
    }

    @Test
    void HTML标签被清洗() throws Exception {
        givenUrl("/mykey");
        server.enqueue(new MockResponse().setResponseCode(200));

        notifier.send(NotificationType.GENERAL, "<b>复制任务失败</b>");

        assertEquals("/mykey/OSR/复制任务失败", decodedPath(server.takeRequest()));
    }

    @Test
    void 地址非法_不抛异常也不发请求() {
        when(config.getNotifyBarkUrl()).thenReturn("这不是URL");

        assertDoesNotThrow(() -> notifier.send(NotificationType.GENERAL, "hello"));
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void 服务端返回错误_不抛异常() {
        givenUrl("/mykey");
        server.enqueue(new MockResponse().setResponseCode(500));

        assertDoesNotThrow(() -> notifier.send(NotificationType.GENERAL, "hello"));
    }

    @Test
    void 渠道元信息_只有一个推送Key所以不支持分人() {
        assertEquals("BARK", notifier.channelKey());
        assertFalse(notifier.supportsDirectDelivery());
    }
}
