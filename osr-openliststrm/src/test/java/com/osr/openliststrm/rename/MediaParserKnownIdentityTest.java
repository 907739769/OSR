package com.osr.openliststrm.rename;

import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.openai.OpenAIClient;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.openliststrm.tmdb.TMDbApiService;
import com.osr.openliststrm.tmdb.TMDbClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 跨模块走真实解析器和 TMDbClient，仅模拟最底层 API，防止 ID/标题/类型协议漂移。 */
class MediaParserKnownIdentityTest {
    @Test
    void 已知剧集身份忽略误导文件名_无季号时仍使用tv_在请求分集详情前补季集() throws Exception {
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            TMDbApiService api = mock(TMDbApiService.class);
            spring.when(() -> SpringUtils.getBean(TMDbApiService.class)).thenReturn(api);
            when(api.getDetails("key", "tv", 79481)).thenReturn("""
                    {"id":79481,"name":"斗破苍穹","first_air_date":"2017-01-07"}
                    """);
            OpenAIClient ai = mock(OpenAIClient.class);
            MediaParser parser = new MediaParser(new TMDbClient("key"), ai);
            MediaInfo info = parser.parseWithKnownTmdbId("Completely.Wrong.Title.2026.1080p.mkv",
                    new MediaIdentity("79481", "tv", "3", "2"));
            assertEquals("79481", info.getTmdbId());
            assertEquals("斗破苍穹", info.getTitle());
            assertEquals("2017", info.getYear());
            assertEquals("3", info.getSeason());
            assertEquals("2", info.getEpisode());
            verify(api, never()).search(anyString(), anyString(), anyString(), any());
            verifyNoInteractions(ai);
        }
    }

    @Test
    void 指定电影时清掉误解析的季集号() throws Exception {
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            TMDbApiService api = mock(TMDbApiService.class);
            spring.when(() -> SpringUtils.getBean(TMDbApiService.class)).thenReturn(api);
            when(api.getDetails("key", "movie", 123)).thenReturn("{\"id\":123,\"title\":\"正确电影\"}");
            MediaInfo info = new MediaParser(new TMDbClient("key"), null)
                    .parseWithKnownTmdbId("Wrong.S01E02.mkv", "123", "movie");
            assertNull(info.getSeason());
            assertNull(info.getEpisode());
            assertEquals("正确电影", info.getTitle());
        }
    }

    @Test
    void 指定作品详情为空错误或不一致时停止_不搜索其他作品也不调用AI() throws Exception {
        for (String response : new String[]{null, "null", "{}", "{\"id\":999,\"name\":\"另一部剧\"}", "invalid"}) {
            try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
                TMDbApiService api = mock(TMDbApiService.class);
                spring.when(() -> SpringUtils.getBean(TMDbApiService.class)).thenReturn(api);
                when(api.getDetails("key", "tv", 79481)).thenReturn(response);
                OpenAIClient ai = mock(OpenAIClient.class);
                MediaParser parser = new MediaParser(new TMDbClient("key"), ai);
                assertThrows(IllegalStateException.class, () -> parser.parseWithKnownTmdbId("Wrong.S01E01.mkv", "79481", "tv"));
                verify(api, never()).search(anyString(), anyString(), anyString(), any());
                verifyNoInteractions(ai);
            }
        }
    }

    @Test
    void ID和类型必须明确_电影哨兵季和剧集特别篇不混淆() {
        assertThrows(IllegalArgumentException.class, () -> new MediaIdentity("79481", "TV", null, null));
        assertThrows(IllegalArgumentException.class, () -> new MediaIdentity("电影名", "movie", null, null));
        assertEquals("0", new MediaIdentity("79481", "tv", "00", null).season());
        assertNull(new MediaIdentity("123", "movie", "0", null).season());
    }
}
