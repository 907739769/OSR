package com.osr.openliststrm.pt.subscription;

import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import com.osr.openliststrm.tmdb.TMDbApiService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TmdbSearchServiceTest {

    @Mock
    private TMDbApiService tmDbApiService;

    @Mock
    private OpenlistConfig openlistConfig;

    @InjectMocks
    private TmdbSearchService service;

    @BeforeEach
    void setUp() {
        // lenient：search_关键词为空 用例中关键词校验会在调用 TMDb 前直接返回，
        // 不会用到这个桩，严格模式下会被判定为多余 stubbing
        lenient().when(openlistConfig.getTmdbApiKey()).thenReturn("test-key");
    }

    @Test
    void search_剧集_取name与first_air_date() {
        when(tmDbApiService.search(anyString(), anyString(), anyString(), any()))
                .thenReturn("""
                        {"results":[
                          {"id":1396,"name":"绝命毒师","original_name":"Breaking Bad",
                           "first_air_date":"2008-01-20","poster_path":"/abc.jpg","overview":"简介"}
                        ]}
                        """);

        List<TmdbSearchItem> items = service.search("TV", "绝命毒师");

        assertEquals(1, items.size());
        TmdbSearchItem item = items.get(0);
        assertEquals("1396", item.getTmdbId());
        assertEquals("绝命毒师", item.getTitle());
        assertEquals("Breaking Bad", item.getOriginalTitle());
        assertEquals("2008", item.getYear());
        assertEquals("/abc.jpg", item.getPosterPath());
        assertEquals("TV", item.getMediaType());
    }

    @Test
    void search_电影_取title与release_date() {
        when(tmDbApiService.search(anyString(), anyString(), anyString(), any()))
                .thenReturn("""
                        {"results":[
                          {"id":550,"title":"搏击俱乐部","original_title":"Fight Club",
                           "release_date":"1999-10-15","poster_path":"/x.jpg"}
                        ]}
                        """);

        List<TmdbSearchItem> items = service.search("MOVIE", "搏击俱乐部");

        assertEquals("550", items.get(0).getTmdbId());
        assertEquals("搏击俱乐部", items.get(0).getTitle());
        assertEquals("Fight Club", items.get(0).getOriginalTitle());
        assertEquals("1999", items.get(0).getYear());
        assertEquals("MOVIE", items.get(0).getMediaType());
    }

    @Test
    void search_日期缺失或格式异常_年份为null而非抛异常() {
        when(tmDbApiService.search(anyString(), anyString(), anyString(), any()))
                .thenReturn("""
                        {"results":[
                          {"id":1,"name":"无日期","first_air_date":""},
                          {"id":2,"name":"缺字段"},
                          {"id":3,"name":"怪日期","first_air_date":"待定"}
                        ]}
                        """);

        List<TmdbSearchItem> items = service.search("TV", "x");

        assertEquals(3, items.size());
        assertNull(items.get(0).getYear());
        assertNull(items.get(1).getYear());
        assertNull(items.get(2).getYear());
    }

    @Test
    void search_空结果_返回空列表() {
        when(tmDbApiService.search(anyString(), anyString(), anyString(), any()))
                .thenReturn("{\"results\":[]}");

        assertTrue(service.search("TV", "不存在的剧").isEmpty());
    }

    @Test
    void search_响应无results字段_返回空列表而非NPE() {
        when(tmDbApiService.search(anyString(), anyString(), anyString(), any()))
                .thenReturn("{\"status_message\":\"Invalid API key\"}");

        assertTrue(service.search("TV", "x").isEmpty());
    }

    @Test
    void search_响应非法JSON_返回空列表而非抛异常() {
        when(tmDbApiService.search(anyString(), anyString(), anyString(), any()))
                .thenReturn("<html>error</html>");

        assertTrue(service.search("TV", "x").isEmpty());
    }

    @Test
    void search_关键词为空_直接返回空列表且不调TMDb() {
        assertTrue(service.search("TV", "  ").isEmpty());
        verify(tmDbApiService, never()).search(anyString(), anyString(), anyString(), any());
    }

    @Test
    void getSeasonEpisodeCount_取指定季的集数() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("""
                        {"id":1396,"seasons":[
                          {"season_number":0,"episode_count":8},
                          {"season_number":1,"episode_count":7},
                          {"season_number":2,"episode_count":13}
                        ]}
                        """);

        assertEquals(7, service.getSeasonEpisodeCount("1396", 1));
        assertEquals(13, service.getSeasonEpisodeCount("1396", 2));
    }

    @Test
    void getSeasonEpisodeCount_季号0是特别篇_不是电影_同样能查到() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("""
                        {"seasons":[{"season_number":0,"episode_count":8},{"season_number":1,"episode_count":7}]}
                        """);

        assertEquals(8, service.getSeasonEpisodeCount("1396", 0));
    }

    @Test
    void getSeasonEpisodeCount_季不存在_抛IllegalArgumentException() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("{\"seasons\":[{\"season_number\":1,\"episode_count\":7}]}");

        assertThrows(IllegalArgumentException.class, () -> service.getSeasonEpisodeCount("1396", 99));
    }

    @Test
    void getSeasonEpisodeCount_响应无seasons_抛IllegalArgumentException() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("{\"id\":1396}");

        assertThrows(IllegalArgumentException.class, () -> service.getSeasonEpisodeCount("1396", 1));
    }

    @Test
    void getDetail_取标题年份海报() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("""
                        {"id":1396,"name":"绝命毒师","original_name":"Breaking Bad",
                         "first_air_date":"2008-01-20","poster_path":"/abc.jpg"}
                        """);

        TmdbSearchItem detail = service.getDetail("TV", "1396");

        assertEquals("绝命毒师", detail.getTitle());
        assertEquals("2008", detail.getYear());
        assertEquals("/abc.jpg", detail.getPosterPath());
    }

    @Test
    void getDetail_电影从详情直接取imdb_id_不额外调external_ids() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("""
                        {"id":550,"title":"搏击俱乐部","original_title":"Fight Club",
                         "release_date":"1999-10-15","imdb_id":"tt0137523"}
                        """);

        TmdbSearchItem detail = service.getDetail("MOVIE", "550");

        assertEquals("tt0137523", detail.getImdbId());
        verify(tmDbApiService, never()).getExternalIds(anyString(), anyString(), anyInt());
    }

    @Test
    void getDetail_电影详情无imdb_id字段_imdbId为null() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("{\"id\":550,\"title\":\"片\",\"release_date\":\"1999-10-15\"}");

        assertNull(service.getDetail("MOVIE", "550").getImdbId());
    }

    @Test
    void getDetail_剧集查external_ids取imdb_id() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("""
                        {"id":1396,"name":"绝命毒师","original_name":"Breaking Bad",
                         "first_air_date":"2008-01-20"}
                        """);
        when(tmDbApiService.getExternalIds(anyString(), eq("tv"), anyInt()))
                .thenReturn("{\"imdb_id\":\"tt0903747\"}");

        TmdbSearchItem detail = service.getDetail("TV", "1396");

        assertEquals("tt0903747", detail.getImdbId());
    }

    @Test
    void getDetail_标题已是中文_不查别名接口() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("""
                        {"id":1396,"name":"绝命毒师","original_name":"Breaking Bad",
                         "original_language":"en","first_air_date":"2008-01-20"}
                        """);

        assertEquals("绝命毒师", service.getDetail("TV", "1396").getTitle());
        verify(tmDbApiService, never()).getAlternativeTitles(anyString(), anyString(), anyInt());
    }

    @Test
    void getDetail_标题是英文_取别名接口的CN中文名() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("""
                        {"id":1396,"name":"Silent Tokyo","original_name":"サイレント東京",
                         "original_language":"ja","first_air_date":"2020-01-20"}
                        """);
        when(tmDbApiService.getAlternativeTitles(anyString(), eq("tv"), anyInt()))
                .thenReturn("""
                        {"results":[
                          {"iso_3166_1":"US","title":"Silent Tokyo"},
                          {"iso_3166_1":"TW","title":"寂靜的東京"},
                          {"iso_3166_1":"CN","title":"寂静的东京"}
                        ]}
                        """);

        assertEquals("寂静的东京", service.getDetail("TV", "1396").getTitle());
    }

    @Test
    void getDetail_无CN别名_按TW_HK顺序兜底() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("{\"id\":550,\"title\":\"Some Movie\",\"original_language\":\"en\",\"release_date\":\"1999-10-15\"}");
        when(tmDbApiService.getAlternativeTitles(anyString(), eq("movie"), anyInt()))
                .thenReturn("""
                        {"titles":[
                          {"iso_3166_1":"HK","title":"某部電影（港）"},
                          {"iso_3166_1":"TW","title":"某部電影（台）"}
                        ]}
                        """);

        assertEquals("某部電影（台）", service.getDetail("MOVIE", "550").getTitle());
    }

    @Test
    void getDetail_CN别名登记的是拼音或英文_跳过并保留原标题() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("{\"id\":550,\"title\":\"Some Movie\",\"original_language\":\"en\",\"release_date\":\"1999-10-15\"}");
        when(tmDbApiService.getAlternativeTitles(anyString(), eq("movie"), anyInt()))
                .thenReturn("""
                        {"titles":[
                          {"iso_3166_1":"CN","title":"Mou Bu Dian Ying"},
                          {"iso_3166_1":"JP","title":"某の映画"}
                        ]}
                        """);

        assertEquals("Some Movie", service.getDetail("MOVIE", "550").getTitle());
    }

    @Test
    void getDetail_别名接口失败_保留原英文标题不抛异常() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("{\"id\":550,\"title\":\"Some Movie\",\"original_language\":\"en\",\"release_date\":\"1999-10-15\"}");
        when(tmDbApiService.getAlternativeTitles(anyString(), anyString(), anyInt())).thenReturn(null);

        assertEquals("Some Movie", service.getDetail("MOVIE", "550").getTitle());
    }

    @Test
    void getDetail_剧集external_ids无imdb_id_imdbId为null() {
        when(tmDbApiService.getDetails(anyString(), anyString(), anyInt()))
                .thenReturn("{\"id\":1396,\"name\":\"剧\",\"first_air_date\":\"2008-01-20\"}");
        when(tmDbApiService.getExternalIds(anyString(), eq("tv"), anyInt()))
                .thenReturn("{\"tvdb_id\":123}");

        assertNull(service.getDetail("TV", "1396").getImdbId());
    }
}
