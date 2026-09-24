package com.osr.openliststrm.pt.media;

import com.alibaba.fastjson2.JSONObject;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plex 没有按 TMDb ID 查条目的接口，靠列全库建索引——索引的来源（新旧两种 agent）、
 * 多库合并、按季过滤与缓存都在这里钉住。
 */
class PlexClientTest {

    private MockWebServer server;
    private PlexClient client;
    private PtMediaServerPlus config;
    private final AtomicInteger sectionListings = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (!"tok".equals(request.getHeader("X-Plex-Token"))) {
                    return new MockResponse().setResponseCode(401);
                }
                String path = request.getRequestUrl().encodedPath();
                return switch (path) {
                    case "/" -> json("{\"MediaContainer\":{\"version\":\"1.40.0\",\"friendlyName\":\"家里 Plex\"}}");
                    case "/library/sections" -> json("{\"MediaContainer\":{\"Directory\":["
                            + "{\"key\":\"1\",\"type\":\"show\"},{\"key\":\"2\",\"type\":\"show\"},{\"key\":\"3\",\"type\":\"movie\"}]}}");
                    case "/library/sections/1/all" -> {
                        sectionListings.incrementAndGet();
                        yield json("{\"MediaContainer\":{\"Metadata\":["
                                + "{\"ratingKey\":\"100\",\"Guid\":[{\"id\":\"imdb://tt0903747\"},{\"id\":\"tmdb://1396\"}]}]}}");
                    }
                    // 第二个剧集库里同一部剧还有一份，用的是旧版 agent 的 guid
                    case "/library/sections/2/all" -> json("{\"MediaContainer\":{\"Metadata\":["
                            + "{\"ratingKey\":\"200\",\"guid\":\"com.plexapp.agents.themoviedb://1396?lang=zh\"}]}}");
                    case "/library/sections/3/all" -> json("{\"MediaContainer\":{\"Metadata\":["
                            + "{\"ratingKey\":\"300\",\"Guid\":[{\"id\":\"tmdb://603\"}]}]}}");
                    case "/library/metadata/100/allLeaves" -> json("{\"MediaContainer\":{\"Metadata\":["
                            + "{\"parentIndex\":1,\"index\":1},{\"parentIndex\":1,\"index\":2},{\"parentIndex\":2,\"index\":1}]}}");
                    case "/library/metadata/200/allLeaves" -> json("{\"MediaContainer\":{\"Metadata\":["
                            + "{\"parentIndex\":1,\"index\":3}]}}");
                    default -> new MockResponse().setResponseCode(404);
                };
            }
        });
        server.start();
        client = new PlexClient(new OkHttpClient());
        config = new PtMediaServerPlus();
        config.setId(1);
        config.setName("Plex");
        config.setType("PLEX");
        config.setUrl(server.url("/").toString());
        config.setApiKey("tok");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    void 按季取集号_多个库里的同一部剧合并() throws IOException {
        assertEquals(Set.of(1, 2, 3), client.listEpisodes(config, "1396", 1));
        assertEquals(Set.of(1), client.listEpisodes(config, "1396", 2));
    }

    @Test
    void 全剧集号不看季号() throws IOException {
        assertEquals(Set.of(1, 2, 3), client.listAllEpisodeNumbers(config, "1396"));
    }

    @Test
    void 电影只在电影库里找() throws IOException {
        assertTrue(client.hasMovie(config, "603"));
        assertFalse(client.hasMovie(config, "1396"), "剧集的 TMDb ID 不能在电影库里命中");
    }

    @Test
    void 不在库里的剧返回空集() throws IOException {
        assertTrue(client.listEpisodes(config, "9999", 1).isEmpty());
    }

    /** 对账每条订阅查一次：索引要缓存，一轮对账不能对每条订阅各把全库拉一遍 */
    @Test
    void 库索引有缓存() throws IOException {
        client.listEpisodes(config, "1396", 1);
        client.listEpisodes(config, "1396", 2);
        client.listEpisodes(config, "9999", 1);

        assertEquals(1, sectionListings.get());
    }

    @Test
    void 连通测试_成功回显版本与名称_令牌错误给出能指导处置的原因() {
        MediaServerProbe ok = client.testConnection(config);
        assertTrue(ok.ok());
        assertTrue(ok.detail().contains("1.40.0") && ok.detail().contains("家里 Plex"), ok.detail());

        config.setApiKey("wrong");
        MediaServerProbe bad = client.testConnection(config);
        assertFalse(bad.ok());
        assertTrue(bad.detail().contains("Plex Token"), bad.detail());
    }

    @Test
    void 外部ID解析_新旧两种agent() {
        assertEquals("1396", PlexClient.tmdbIdOf(JSONObject.parseObject("{\"Guid\":[{\"id\":\"tmdb://1396\"}]}")));
        assertEquals("1396", PlexClient.tmdbIdOf(JSONObject.parseObject("{\"guid\":\"com.plexapp.agents.themoviedb://1396?lang=en\"}")));
        assertNull(PlexClient.tmdbIdOf(JSONObject.parseObject("{\"guid\":\"plex://show/5d9c086c46115600200aa2fe\"}")));
    }
}
