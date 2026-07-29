package com.ruoyi.openliststrm.pt.downloader;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.pt.downloader.model.DownloaderTorrent;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransmissionClientTest {

    private static final String SESSION_HEADER = "X-Transmission-Session-Id";

    private MockWebServer server;
    private TransmissionClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new TransmissionClient(new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private PtDownloaderPlus config(int id) {
        PtDownloaderPlus c = new PtDownloaderPlus();
        c.setId(id);
        c.setName("tr");
        c.setType("TRANSMISSION");
        c.setHost(server.getHostName());
        c.setPort(server.getPort());
        c.setUseHttps("0");
        c.setSavePath("/data/downloads");
        c.setTag("osr-pt");
        return c;
    }

    /** 首次请求（或 session 过期）Transmission 固定返回 409 并带新 session id */
    private MockResponse sessionRequired() {
        return new MockResponse().setResponseCode(409).addHeader(SESSION_HEADER, "test-session-id");
    }

    @Test
    void type_返回TRANSMISSION() {
        assertEquals("TRANSMISSION", client.type());
    }

    @Test
    void testConnection_sessionGet成功_判定连通() {
        server.enqueue(sessionRequired());
        server.enqueue(new MockResponse().setBody("{\"result\":\"success\",\"arguments\":{}}"));

        assertTrue(client.testConnection(config(1)));
    }

    @Test
    void testConnection_地址不可达_判定不连通而非抛异常() throws IOException {
        PtDownloaderPlus cfg = config(2);
        server.shutdown();

        assertFalse(client.testConnection(cfg));
    }

    @Test
    void testConnection_result非success_判定不连通() {
        server.enqueue(sessionRequired());
        server.enqueue(new MockResponse().setBody("{\"result\":\"invalid method\",\"arguments\":{}}"));

        assertFalse(client.testConnection(config(3)));
    }

    @Test
    void addTorrent_首次409后带session重试_请求参数正确() throws Exception {
        server.enqueue(sessionRequired());
        server.enqueue(new MockResponse().setBody(
                "{\"result\":\"success\",\"arguments\":{\"torrent-added\":{\"id\":7,\"name\":\"Show\"}}}"));
        // 第二次调用（打标签）复用缓存的 session，不会再收到 409
        server.enqueue(new MockResponse().setBody("{\"result\":\"success\",\"arguments\":{}}"));

        client.addTorrent(config(4), "https://pt.example.com/t.torrent", "/data/downloads", "osr-pt");

        server.takeRequest(); // 首次 409
        RecordedRequest add = server.takeRequest();
        assertEquals("test-session-id", add.getHeader(SESSION_HEADER));
        String addBody = add.getBody().readUtf8();
        assertTrue(addBody.contains("torrent-add"));
        assertTrue(addBody.contains("/data/downloads"));

        RecordedRequest setLabel = server.takeRequest();
        assertEquals("test-session-id", setLabel.getHeader(SESSION_HEADER));
        String setBody = setLabel.getBody().readUtf8();
        assertTrue(setBody.contains("torrent-set"));
        assertTrue(setBody.contains("osr-pt"));
    }

    @Test
    void addTorrent_日志不得泄漏下载链接中的凭据() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(TransmissionClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            server.enqueue(sessionRequired());
            server.enqueue(new MockResponse().setBody("{\"result\":\"success\",\"arguments\":{}}"));
            server.enqueue(new MockResponse().setBody("{\"result\":\"success\",\"arguments\":{}}"));

            String secret = "super-secret-apikey";
            client.addTorrent(config(5),
                    "http://prowlarr:9696/1/download?apikey=" + secret + "&link=abc",
                    "/data/downloads", "osr-pt");

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (a, b) -> a + "\n" + b);
            assertFalse(logged.contains(secret), "日志中不应出现下载链接里的凭据，实际内容：" + logged);
            assertTrue(logged.contains("http://prowlarr:9696/1/download"), "应保留可排查的链接主体");
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void addTorrent_打标签失败不影响种子已添加成功() throws Exception {
        server.enqueue(sessionRequired());
        server.enqueue(new MockResponse().setBody(
                "{\"result\":\"success\",\"arguments\":{\"torrent-added\":{\"id\":9,\"name\":\"Show\"}}}"));
        // torrent-set 调用返回失败（例如老版本不支持 labels），addTorrent 不应向上抛异常
        server.enqueue(new MockResponse().setBody("{\"result\":\"invalid argument\",\"arguments\":{}}"));

        client.addTorrent(config(6), "https://pt.example.com/t.torrent", "/data/downloads", "osr-pt");
        // 未抛异常即为通过
    }

    @Test
    void addTorrent_torrentAdd返回失败_抛IOException() {
        server.enqueue(sessionRequired());
        server.enqueue(new MockResponse().setBody("{\"result\":\"invalid argument\",\"arguments\":{}}"));

        assertThrows(IOException.class,
                () -> client.addTorrent(config(7), "https://pt.example.com/t.torrent", "/data/downloads", "osr-pt"));
    }

    @Test
    void listByTag_按label本地过滤并映射字段() throws Exception {
        server.enqueue(sessionRequired());
        server.enqueue(new MockResponse().setBody("""
                {"result":"success","arguments":{"torrents":[
                  {"id":1,"name":"Show.S01E01","percentDone":1.0,"status":6,"downloadDir":"/data","labels":["osr-pt"],"hashString":"AABBCC"},
                  {"id":2,"name":"Other.Show","percentDone":0.4,"status":4,"downloadDir":"/data","labels":["other-tag"],"hashString":"DDEEFF"},
                  {"id":3,"name":"Show.S01E02","percentDone":0.5,"status":4,"downloadDir":"/data","labels":["osr-pt","osr-pt-extra"],"hashString":"112233"}
                ]}}
                """));

        List<DownloaderTorrent> list = client.listByTag(config(8), "osr-pt");

        assertEquals(2, list.size());
        assertEquals("aabbcc", list.get(0).getHash());
        assertTrue(list.get(0).isCompleted());
        assertEquals("112233", list.get(1).getHash());
        assertFalse(list.get(1).isCompleted());
        assertEquals("osr-pt,osr-pt-extra", list.get(1).getTags());
    }

    @Test
    void listByTag_session过期重新获取后重试成功() throws Exception {
        PtDownloaderPlus cfg = config(9);

        // 第一轮：拿到 session 并缓存
        server.enqueue(sessionRequired());
        server.enqueue(new MockResponse().setBody("{\"result\":\"success\",\"arguments\":{\"torrents\":[]}}"));
        client.listByTag(cfg, "osr-pt");
        server.takeRequest();
        server.takeRequest();

        // 第二轮：缓存的 session 已过期 → 409（带新 id）→ 重试成功
        server.enqueue(new MockResponse().setResponseCode(409).addHeader(SESSION_HEADER, "renewed-session-id"));
        server.enqueue(new MockResponse().setBody("""
                {"result":"success","arguments":{"torrents":[
                  {"id":1,"name":"Show","percentDone":1.0,"status":6,"downloadDir":"/data","labels":["osr-pt"],"hashString":"AABBCC"}
                ]}}
                """));

        List<DownloaderTorrent> list = client.listByTag(cfg, "osr-pt");

        assertEquals(1, list.size());
        server.takeRequest();
        RecordedRequest retry = server.takeRequest();
        assertEquals("renewed-session-id", retry.getHeader(SESSION_HEADER));
    }

    @Test
    void listByTag_响应体不是合法JSON_抛IOException而非JSONException() {
        server.enqueue(sessionRequired());
        server.enqueue(new MockResponse().setBody("<html><body>502 Bad Gateway</body></html>"));

        IOException ex = assertThrows(IOException.class, () -> client.listByTag(config(10), "osr-pt"));
        assertTrue(ex.getMessage().contains("不是合法 JSON"));
    }

    @Test
    void listByTag_未配置密码时不带AuthorizationHeader() throws Exception {
        server.enqueue(sessionRequired());
        server.enqueue(new MockResponse().setBody("{\"result\":\"success\",\"arguments\":{\"torrents\":[]}}"));

        client.listByTag(config(11), "osr-pt");

        server.takeRequest();
        RecordedRequest retry = server.takeRequest();
        assertEquals(null, retry.getHeader("Authorization"));
    }

    @Test
    void listByTag_配置用户名密码时带上BasicAuth() throws Exception {
        PtDownloaderPlus cfg = config(12);
        cfg.setUsername("admin");
        cfg.setPassword("secret");

        server.enqueue(sessionRequired());
        server.enqueue(new MockResponse().setBody("{\"result\":\"success\",\"arguments\":{\"torrents\":[]}}"));

        client.listByTag(cfg, "osr-pt");

        server.takeRequest();
        RecordedRequest retry = server.takeRequest();
        assertTrue(retry.getHeader("Authorization").startsWith("Basic "));
    }
}
