package com.osr.openliststrm.pt.autoadd.source;

import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 系列/人物来源：ID 解析、系列全收、人物只收导演与前几位主演的近期电影。
 */
class TmdbFollowSourceTest {

    @Test
    void ID_可填纯数字或贴链接() {
        assertEquals(10, TmdbFollowSource.parseId(" 10 "));
        assertEquals(87359, TmdbFollowSource.parseId("https://www.themoviedb.org/collection/87359-mission-impossible-collection"));
        assertEquals(287, TmdbFollowSource.parseId("https://www.themoviedb.org/person/287-brad-pitt?language=zh-CN"));
        assertNull(TmdbFollowSource.parseId("https://www.themoviedb.org/movie/603"));
        assertNull(TmdbFollowSource.parseId(""));
    }

    @Test
    void 系列_全部电影都收() {
        JSONObject json = JSONObject.parseObject("{\"parts\":[{\"id\":1,\"title\":\"一\",\"release_date\":\"1996-05-22\"},"
                + "{\"id\":2,\"title\":\"二\",\"release_date\":\"\"}]}");

        List<PopularItem> items = TmdbFollowSource.collectionItems(json);

        assertEquals(List.of("1", "2"), items.stream().map(PopularItem::getTmdbId).toList());
        assertEquals("1996", items.get(0).getYear());
        assertEquals("MOVIE", items.get(0).getMediaType());
    }

    @Test
    void 人物_只收导演与前五主演的近期电影_同片去重() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        JSONObject json = JSONObject.parseObject("{\"cast\":["
                + "{\"id\":1,\"title\":\"主演新片\",\"order\":0,\"release_date\":\"2026-03-01\"},"
                + "{\"id\":2,\"title\":\"客串\",\"order\":12,\"release_date\":\"2026-03-01\"},"
                + "{\"id\":3,\"title\":\"老片\",\"order\":0,\"release_date\":\"2020-01-01\"},"
                + "{\"id\":4,\"title\":\"未上映\",\"order\":1,\"release_date\":\"2027-06-01\"},"
                + "{\"id\":5,\"title\":\"没日期\",\"order\":0},"
                + "{\"id\":6,\"title\":\"自导自演\",\"order\":0,\"release_date\":\"2026-08-01\"}],"
                + "\"crew\":["
                + "{\"id\":6,\"title\":\"自导自演\",\"job\":\"Director\",\"release_date\":\"2026-08-01\"},"
                + "{\"id\":7,\"title\":\"导演作品\",\"job\":\"Director\",\"release_date\":\"2025-12-01\"},"
                + "{\"id\":8,\"title\":\"只是制片\",\"job\":\"Producer\",\"release_date\":\"2026-01-01\"}]}");

        List<String> ids = TmdbFollowSource.personItems(json, today).stream().map(PopularItem::getTmdbId).toList();

        assertEquals(List.of("1", "4", "6", "7"), ids);
    }

    @Test
    void 响应为空不抛() {
        assertTrue(TmdbFollowSource.personItems(null, LocalDate.now()).isEmpty());
        assertTrue(TmdbFollowSource.collectionItems(null).isEmpty());
    }
}
