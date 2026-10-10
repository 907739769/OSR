package com.osr.openliststrm.tmdb;

import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.rename.model.MediaInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TMDbClient#matchTmdbId} 的两条契约。这两条必须走真实 TMDbClient，不能 mock 它——
 * 调用方（资源搜索页）mock 掉整个 client 之后，下面两个 bug 都照过：测试全绿、真实搜索里
 * 一条都识别不出来。
 */
class TMDbClientMatchTmdbIdTest {

    private final TMDbClient client = new TMDbClient("test-key");

    private TMDbApiService api(MockedStatic<SpringUtils> spring) {
        TMDbApiService api = mock(TMDbApiService.class);
        spring.when(() -> SpringUtils.getBean(TMDbApiService.class)).thenReturn(api);
        return api;
    }

    /**
     * 返回值必须是 id。{@code search()} 交出来的是 {@code getBestTitle} 的标题，id 只写在
     * {@code info.setTmdbId} 上——直接 {@code return search(...)} 会把「斗破苍穹」当成 id 交出去，
     * 调用方拿它去 {@code Integer.parseInt} 就炸，整页识别不出。
     */
    @Test
    void matchTmdbId返回的是数字id而不是标题() {
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            TMDbApiService api = api(spring);
            when(api.search(anyString(), anyString(), anyString(), any()))
                    .thenReturn("{\"results\":[{\"id\":79481,\"name\":\"斗破苍穹\",\"popularity\":10}]}");

            MediaInfo info = new MediaInfo("Fights Break Sphere S05E01 1080p");
            info.setOriginalTitle("斗破苍穹");

            assertEquals("79481", client.matchTmdbId("tv", info));
        }
    }

    /**
     * 识别只要一个 id。{@code enrich} 那条路采纳候选后还要拉详情、图片、外部 ID、分级——那是给刮削
     * 写 NFO 用的，一个作品 5~6 个请求；资源搜索页一次识别几十组标题，全拉一遍就是上百个白发的请求，
     * 而且它们都要过 {@code TMDbApiService} 那个并发 4 的信号量，直接吃掉识别预算。
     */
    @Test
    void 只判定身份_采纳后不拉详情图片与别名() {
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            TMDbApiService api = api(spring);
            when(api.search(anyString(), anyString(), anyString(), any()))
                    .thenReturn("{\"results\":[{\"id\":79481,\"name\":\"斗破苍穹\",\"popularity\":10}]}");

            MediaInfo info = new MediaInfo("斗破苍穹 S05 1080p");
            info.setOriginalTitle("斗破苍穹");

            assertEquals("79481", client.matchTmdbId("tv", info));

            verify(api, never()).getDetails(anyString(), anyString(), anyInt());
            verify(api, never()).getTvImages(anyString(), anyInt());
            verify(api, never()).getExternalIds(anyString(), anyString(), anyInt());
            verify(api, never()).getTvContentRatings(anyString(), anyInt());
            verify(api, never()).getAlternativeTitles(anyString(), anyString(), anyInt());
        }
    }

    /**
     * 类型必须在入口归一。TMDbClient 内部按小写全等判断（{@code "movie".equals(type)} 决定读
     * {@code title} 还是 {@code name}），传大写时电影被当剧集取字段、集数反证整条失效，
     * 而且大写直接拼进 {@code /search/} 请求路径。
     */
    @Test
    void 大写媒体类型必须在入口归一否则电影字段取不到() {
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            TMDbApiService api = api(spring);
            when(api.search(anyString(), anyString(), anyString(), any()))
                    .thenReturn("{\"results\":[{\"id\":98325,\"title\":\"三体\",\"popularity\":10}]}");

            MediaInfo info = new MediaInfo("Three Body 2023 2160p");
            info.setOriginalTitle("三体");

            assertEquals("98325", client.matchTmdbId("MOVIE", info));

            ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
            verify(api).search(anyString(), type.capture(), anyString(), any());
            assertEquals("movie", type.getValue());
        }
    }
}
