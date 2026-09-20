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

        assertTrue(client.testConnection(config(null)).ok());
        assertEquals("/System/Info", server.takeRequest().getPath());
    }

    @Test
    void testConnection_鉴权失败_判定不连通而非抛异常() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertFalse(client.testConnection(config(null)).ok());
    }

    // ---------- 失败原因要能指导处置 ----------
    //
    // 这几条钉住的是「连接失败」四个字回答不了的问题：API Key 填错、地址/端口不通、
    // 反代把请求转给了别的服务——三者要用户去改的东西完全不同。退回一句通用文案时，
    // 功能照常「工作」（仍然正确地判定为不连通），只是用户无从下手，所以只能靠断言守着。

    @Test
    void testConnection_连通时回显产品名与版本_让用户确认连的是哪一台() {
        server.enqueue(new MockResponse().setBody("""
                {"ProductName":"Jellyfin Server","Version":"10.9.11","ServerName":"客厅"}
                """));

        MediaServerProbe probe = client.testConnection(config(null));

        assertTrue(probe.ok());
        assertTrue(probe.detail().contains("Jellyfin Server"));
        assertTrue(probe.detail().contains("10.9.11"));
        assertTrue(probe.detail().contains("客厅"));
    }

    /** 缺失的片段整段不写，不写「未知」——一句「版本：未知」不帮用户做任何判断 */
    @Test
    void testConnection_信息字段缺失时不输出未知() {
        server.enqueue(new MockResponse().setBody("{}"));

        MediaServerProbe probe = client.testConnection(config(null));

        assertTrue(probe.ok());
        assertFalse(probe.detail().contains("未知"));
    }

    @Test
    void testConnection_401指向APIKey() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertTrue(client.testConnection(config(null)).detail().contains("API Key"));
    }

    @Test
    void testConnection_403同样指向APIKey() {
        server.enqueue(new MockResponse().setResponseCode(403));

        assertTrue(client.testConnection(config(null)).detail().contains("API Key"));
    }

    /** 404 最常见的成因是反代少配了路径前缀，与 Key 错完全是两个方向 */
    @Test
    void testConnection_404指向地址而不是APIKey() {
        server.enqueue(new MockResponse().setResponseCode(404));

        String detail = client.testConnection(config(null)).detail();
        assertTrue(detail.contains("地址"));
        assertFalse(detail.contains("API Key"));
    }

    @Test
    void testConnection_502指向对端或代理不可用() {
        server.enqueue(new MockResponse().setResponseCode(502));

        assertTrue(client.testConnection(config(null)).detail().contains("502"));
    }

    @Test
    void testConnection_返回HTML_指出这可能不是EmbyJellyfin地址() {
        server.enqueue(new MockResponse().setBody("<html><body>Welcome to nginx</body></html>"));

        MediaServerProbe probe = client.testConnection(config(null));

        assertFalse(probe.ok());
        assertTrue(probe.detail().contains("不是合法 JSON"));
    }

    @Test
    void testConnection_地址无法解析时不抛异常() {
        PtMediaServerPlus broken = config(null);
        broken.setUrl("这不是一个地址");

        assertFalse(client.testConnection(broken).ok());
    }

    // ---------- 用户列表 ----------

    @Test
    void listUsers_解析出id与名称() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                [{"Id":"abc123","Name":"Jack"},{"Id":"def456","Name":"家人"}]
                """));

        var users = client.listUsers(config(null));

        assertEquals(2, users.size());
        assertEquals("abc123", users.get(0).id());
        assertEquals("Jack", users.get(0).name());
        assertEquals("/Users", server.takeRequest().getPath());
    }

    /** 没有 Id 的条目直接丢掉：它填进配置里也没有意义 */
    @Test
    void listUsers_跳过没有Id的条目() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                [{"Id":"abc123","Name":"Jack"},{"Name":"坏数据"}]
                """));

        assertEquals(1, client.listUsers(config(null)).size());
    }

    @Test
    void listUsers_HTTP错误_抛IOException() {
        server.enqueue(new MockResponse().setResponseCode(401));

        assertThrows(IOException.class, () -> client.listUsers(config(null)));
    }

    // ---------- 全剧集号 ----------

    /**
     * 这个方法存在的前提就是「库的分季方式和订阅对不上」，所以它<b>不能</b>带 season 参数——
     * 带了就退化成按季查，长篇动画那条兜底路径整个失效，而结果只是「少匹配上几集」。
     */
    @Test
    void listAllEpisodeNumbers_不带季号参数() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"Items\":[{\"Id\":\"series-42\"}]}"));
        server.enqueue(new MockResponse().setBody("""
                {"Items":[{"IndexNumber":1168},{"IndexNumber":1169}]}
                """));

        Set<Integer> numbers = client.listAllEpisodeNumbers(config(null), "12345");

        assertEquals(Set.of(1168, 1169), numbers);
        server.takeRequest();
        RecordedRequest episodes = server.takeRequest();
        assertEquals(null, episodes.getRequestUrl().queryParameter("season"));
    }

    @Test
    void listAllEpisodeNumbers_剧集不在库中_返回空集合且不发第二次请求() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"Items\":[]}"));

        assertTrue(client.listAllEpisodeNumbers(config(null), "99999").isEmpty());
        assertEquals(1, server.getRequestCount());
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
