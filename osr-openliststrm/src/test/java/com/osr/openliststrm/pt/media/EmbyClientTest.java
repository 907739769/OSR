package com.osr.openliststrm.pt.media;

import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbyClientTest {

    private MockWebServer server;
    private EmbyClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new EmbyClient(new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private PtMediaServerPlus config(String userId) {
        PtMediaServerPlus c = new PtMediaServerPlus();
        c.setId(1);
        c.setName("emby");
        c.setType("EMBY");
        c.setUrl(server.url("/").toString());
        c.setApiKey("emby-key");
        c.setUserId(userId);
        return c;
    }

    @Test
    void type_返回EMBY() {
        assertEquals("EMBY", client.type());
    }

    @Test
    void listEpisodes_返回该季已有集号集合() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"Items":[{"Id":"series-42","Name":"某剧"}]}
                """));
        server.enqueue(new MockResponse().setBody("""
                {"Items":[
                  {"Id":"ep1","IndexNumber":1},
                  {"Id":"ep2","IndexNumber":2},
                  {"Id":"ep5","IndexNumber":5}
                ]}
                """));

        Set<Integer> episodes = client.listEpisodes(config(null), "12345", 1);

        assertEquals(Set.of(1, 2, 5), episodes);
    }

    @Test
    void listEpisodes_请求参数正确() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"Items\":[{\"Id\":\"series-42\"}]}"));
        server.enqueue(new MockResponse().setBody("{\"Items\":[]}"));

        client.listEpisodes(config("user-9"), "12345", 3);

        RecordedRequest lookup = server.takeRequest();
        assertEquals("emby-key", lookup.getHeader("X-Emby-Token"));
        assertEquals("Series", lookup.getRequestUrl().queryParameter("IncludeItemTypes"));
        assertEquals("tmdb.12345", lookup.getRequestUrl().queryParameter("AnyProviderIdEquals"));

        RecordedRequest episodes = server.takeRequest();
        assertTrue(episodes.getPath().startsWith("/Shows/series-42/Episodes"));
        assertEquals("3", episodes.getRequestUrl().queryParameter("season"));
        assertEquals("user-9", episodes.getRequestUrl().queryParameter("userId"));
    }

    @Test
    void listEpisodes_未配置userId_不带该参数() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"Items\":[{\"Id\":\"series-42\"}]}"));
        server.enqueue(new MockResponse().setBody("{\"Items\":[]}"));

        client.listEpisodes(config(null), "12345", 1);

        server.takeRequest();
        RecordedRequest episodes = server.takeRequest();
        assertEquals(null, episodes.getRequestUrl().queryParameter("userId"));
    }

    @Test
    void listEpisodes_剧集不在库中_返回空集合且不发第二次请求() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"Items\":[]}"));

        assertTrue(client.listEpisodes(config(null), "99999", 1).isEmpty());
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void listEpisodes_集号字段缺失的条目_被忽略() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"Items\":[{\"Id\":\"series-42\"}]}"));
        server.enqueue(new MockResponse().setBody("""
                {"Items":[{"Id":"ep1","IndexNumber":1},{"Id":"special"}]}
                """));

        assertEquals(Set.of(1), client.listEpisodes(config(null), "12345", 1));
    }

    @Test
    void listEpisodes_HTTP错误_抛IOException() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertThrows(IOException.class, () -> client.listEpisodes(config(null), "12345", 1));
    }

    @Test
    void hasMovie_命中返回true() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"Items\":[{\"Id\":\"movie-7\"}]}"));

        assertTrue(client.hasMovie(config(null), "550"));

        RecordedRequest request = server.takeRequest();
        assertEquals("Movie", request.getRequestUrl().queryParameter("IncludeItemTypes"));
        assertEquals("tmdb.550", request.getRequestUrl().queryParameter("AnyProviderIdEquals"));
    }

    @Test
    void hasMovie_未命中返回false() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"Items\":[]}"));

        assertFalse(client.hasMovie(config(null), "550"));
    }

    @Test
    void testConnection_系统信息接口正常_判定连通() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"ServerName\":\"emby\",\"Version\":\"4.8.0\"}"));

        assertTrue(client.testConnection(config(null)));
        assertEquals("/System/Info", server.takeRequest().getPath());
    }

    @Test
    void testConnection_鉴权失败_判定不连通而非抛异常() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertFalse(client.testConnection(config(null)));
    }

    @Test
    void hasMovie_响应体不是合法JSON_抛IOException而非JSONException() {
        // 模拟反向代理故障：返回一个 HTML 错误页而非 Emby 的 JSON 对象
        server.enqueue(new MockResponse().setBody("<html><body>502 Bad Gateway</body></html>"));

        IOException ex = assertThrows(IOException.class, () -> client.hasMovie(config(null), "550"));
        assertTrue(ex.getMessage().contains("不是合法 JSON"));
    }

    @Test
    void listEpisodes_响应体是超长非JSON文本_异常消息不整段塞入() {
        String huge = "y".repeat(5000);
        server.enqueue(new MockResponse().setBody(huge));

        IOException ex = assertThrows(IOException.class, () -> client.listEpisodes(config(null), "12345", 1));
        assertTrue(ex.getMessage().length() < 500);
    }
}
