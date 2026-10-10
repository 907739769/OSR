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
import static org.mockito.ArgumentMatchers.eq;
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

    /** TMDb 对「The Prince of Tennis II」的真实返回（裁剪过字段）：全等的是 2012 年那部，正确答案只是包含命中 */
    private TMDbApiService princeOfTennis(MockedStatic<SpringUtils> spring) {
        TMDbApiService api = api(spring);
        when(api.search(anyString(), anyString(), anyString(), any())).thenReturn("""
                {"results":[
                  {"id":67249,"name":"新网球王子","original_name":"新テニスの王子様","original_language":"ja","first_air_date":"2012-01-04","popularity":9.8},
                  {"id":236786,"name":"新网球王子 OVA 对战Genius10","original_name":"新テニスの王子様 OVA vs Genius10","original_language":"ja","first_air_date":"2014-10-29","popularity":5.2},
                  {"id":205493,"name":"新网球王子 U-17世界杯篇","original_name":"新テニスの王子様 U-17 WORLD CUP","original_language":"ja","first_air_date":"2022-07-07","popularity":21.9}
                ]}
                """);
        when(api.getDetails(anyString(), eq("tv"), eq(67249), eq("en-US"))).thenReturn("{\"name\":\"The Prince of Tennis II\"}");
        when(api.getDetails(anyString(), eq("tv"), eq(236786), eq("en-US"))).thenReturn("{\"name\":\"New Prince of Tennis OVA vs. Genius10\"}");
        when(api.getDetails(anyString(), eq("tv"), eq(205493), eq("en-US"))).thenReturn("{\"name\":\"The Prince of Tennis II U-17 WORLD CUP\"}");
        when(api.getDetails(anyString(), eq("tv"), eq(67249))).thenReturn("""
                {"id":67249,"first_air_date":"2012-01-04","last_air_date":"2012-03-28","number_of_episodes":13,"seasons":[
                  {"season_number":0,"air_date":"2012-04-20"},{"season_number":1,"air_date":"2012-01-04"}]}
                """);
        when(api.getDetails(anyString(), eq("tv"), eq(205493))).thenReturn("""
                {"id":205493,"first_air_date":"2022-07-07","last_air_date":"2026-10-01","number_of_episodes":39,"seasons":[
                  {"season_number":1,"air_date":"2022-07-07"},{"season_number":2,"air_date":"2024-10-03"},
                  {"season_number":3,"air_date":"2026-10-01"}]}
                """);
        return api;
    }

    private static MediaInfo princeOfTennis(String season, String year) {
        MediaInfo info = new MediaInfo("The Prince of Tennis II S" + season + "E02 " + year + " 1080p CR WEB-DL");
        info.setTitle("The Prince of Tennis II");
        info.setOriginalTitle("The Prince of Tennis II");
        info.setSeason(season);
        info.setEpisode("2");
        info.setYear(year);
        return info;
    }

    /**
     * 标题全等的候选只有 1 季、2012 年就完结了，而另一个标题也对得上的候选第 3 季正是 2026 年开播：
     * 让给后者。标题分不出来的两部作品，季与年份分得出来。
     */
    @Test
    void 全等候选的季与年份说不通_另一候选这一季正是这一年开播_改选后者() {
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            princeOfTennis(spring);

            assertEquals("205493", client.matchTmdbId("tv", princeOfTennis("03", "2026")));
        }
    }

    /** 年份可能只是压制年：没有别的候选「季对得上」时，说不通的那个照常采纳，不能变成识别不出 */
    @Test
    void 季与年份说不通但没有更合适的候选_照常采纳() {
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            princeOfTennis(spring);

            assertEquals("67249", client.matchTmdbId("tv", princeOfTennis("03", "2019")));
        }
    }

    @Test
    void 季与年份对得上的全等候选_直接采纳() {
        try (MockedStatic<SpringUtils> spring = mockStatic(SpringUtils.class)) {
            princeOfTennis(spring);

            assertEquals("67249", client.matchTmdbId("tv", princeOfTennis("01", "2012")));
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
