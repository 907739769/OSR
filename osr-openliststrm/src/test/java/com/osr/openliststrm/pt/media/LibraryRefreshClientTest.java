package com.osr.openliststrm.pt.media;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Emby/Jellyfin 与 Plex 的「列媒体库目录 + 按目录通知刷新」两个接口的请求格式。
 * Plex 只在 MockWebServer 上验证过，与 PlexClient 其余部分一样。
 */
class LibraryRefreshClientTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private PtMediaServerPlus config(String type, String key) {
        PtMediaServerPlus c = new PtMediaServerPlus();
        c.setId(1);
        c.setName("客厅");
        c.setType(type);
        c.setUrl(server.url("/").toString());
        c.setApiKey(key);
        return c;
    }

    // ---------------- Emby / Jellyfin ----------------

    @Test
    void emby_库目录取自VirtualFolders的Locations_一个库多个目录各一条() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                [
                  {"Name":"电视剧","ItemId":"9","Locations":["/media/电视剧","/media2/电视剧"]},
                  {"Name":"空库","ItemId":"10","Locations":[]},
                  {"Name":"电影","ItemId":"11"}
                ]
                """));

        List<LibraryRoot> roots = new EmbyClient(new OkHttpClient()).listLibraryRoots(config("EMBY", "k"));

        assertEquals(List.of(new LibraryRoot("电视剧", "/media/电视剧", "9"),
                new LibraryRoot("电视剧", "/media2/电视剧", "9")), roots);
        RecordedRequest request = server.takeRequest();
        assertEquals("/Library/VirtualFolders", request.getRequestUrl().encodedPath());
        assertEquals("k", request.getHeader("X-Emby-Token"));
    }

    @Test
    void emby_刷新是一次POST带上全部目录_整库时用库目录本身() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));
        LibraryRoot tv = new LibraryRoot("电视剧", "/media/电视剧", "9");
        LibraryRoot movie = new LibraryRoot("电影", "/media/电影", "11");

        new EmbyClient(new OkHttpClient()).refreshPaths(config("EMBY", "k"), List.of(
                new RefreshTarget(tv, "/media/电视剧/三体/Season 1"),
                new RefreshTarget(movie, null)));

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/Library/Media/Updated", request.getRequestUrl().encodedPath());
        assertEquals("k", request.getHeader("X-Emby-Token"));
        JSONArray updates = JSONObject.parseObject(request.getBody().readUtf8()).getJSONArray("Updates");
        assertEquals(2, updates.size());
        assertEquals("/media/电视剧/三体/Season 1", updates.getJSONObject(0).getString("Path"));
        assertEquals("Created", updates.getJSONObject(0).getString("UpdateType"));
        assertEquals("/media/电影", updates.getJSONObject(1).getString("Path"));
    }

    @Test
    void emby_刷新失败带上状态码() {
        server.enqueue(new MockResponse().setResponseCode(401));
        LibraryRoot tv = new LibraryRoot("电视剧", "/media/电视剧", "9");

        MediaServerHttpException e = assertThrows(MediaServerHttpException.class, () ->
                new EmbyClient(new OkHttpClient()).refreshPaths(config("EMBY", "k"), List.of(new RefreshTarget(tv, null))));
        assertEquals(401, e.statusCode());
    }

    @Test
    void emby_没有目标时不发请求() throws Exception {
        new EmbyClient(new OkHttpClient()).refreshPaths(config("EMBY", "k"), List.of());

        assertEquals(0, server.getRequestCount());
    }

    // ---------------- Plex ----------------

    @Test
    void plex_库目录取自sections的Location() throws Exception {
        server.enqueue(new MockResponse().setBody("""
                {"MediaContainer":{"Directory":[
                  {"key":"1","title":"电视剧","type":"show","Location":[{"id":1,"path":"/data/tv"}]},
                  {"key":"3","title":"电影","type":"movie","Location":[{"id":2,"path":"/data/movies"},{"id":3,"path":"/data/movies2"}]}
                ]}}
                """));

        List<LibraryRoot> roots = new PlexClient(new OkHttpClient()).listLibraryRoots(config("PLEX", "tok"));

        assertEquals(List.of(new LibraryRoot("电视剧", "/data/tv", "1"),
                new LibraryRoot("电影", "/data/movies", "3"),
                new LibraryRoot("电影", "/data/movies2", "3")), roots);
    }

    @Test
    void plex_每个目标一次局部扫描_整库时不带path() throws Exception {
        server.enqueue(new MockResponse());
        server.enqueue(new MockResponse());
        LibraryRoot tv = new LibraryRoot("电视剧", "/data/tv", "1");
        LibraryRoot movie = new LibraryRoot("电影", "/data/movies", "3");

        new PlexClient(new OkHttpClient()).refreshPaths(config("PLEX", "tok"), List.of(
                new RefreshTarget(tv, "/data/tv/三体 (2023)/Season 01"),
                new RefreshTarget(movie, null)));

        RecordedRequest first = server.takeRequest();
        assertEquals("/library/sections/1/refresh", first.getRequestUrl().encodedPath());
        assertEquals("/data/tv/三体 (2023)/Season 01", first.getRequestUrl().queryParameter("path"));
        assertEquals("tok", first.getHeader("X-Plex-Token"));
        RecordedRequest second = server.takeRequest();
        assertEquals("/library/sections/3/refresh", second.getRequestUrl().encodedPath());
        assertNull(second.getRequestUrl().queryParameter("path"));
    }

    @Test
    void 接口默认不支持_返回null() throws Exception {
        IMediaServerClient bare = new IMediaServerClient() {
            @Override
            public String type() {
                return "X";
            }

            @Override
            public MediaServerProbe testConnection(PtMediaServerPlus config) {
                return null;
            }

            @Override
            public java.util.Set<Integer> listEpisodes(PtMediaServerPlus config, String tmdbId, int season) {
                return java.util.Set.of();
            }

            @Override
            public boolean hasMovie(PtMediaServerPlus config, String tmdbId) {
                return false;
            }
        };

        assertNull(bare.listLibraryRoots(config("X", "k")));
    }
}
