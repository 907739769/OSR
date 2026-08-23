package com.osr.openliststrm.tmdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TMDb 响应在日志里只留摘要，不留响应体。
 * <p>
 * {@code fetchAndStore} 原先把整个 JsonNode 打出来，一份 tv images 实测 26 KB，
 * 23 条就占掉生产日志的 15.4%。这条日志唯一被用来回答的问题是「这次拉到东西了吗、大概多少」，
 * 而那个问题几个数字就答完了——隔壁 {@code fetchSeasonImagesIfNeeded} 一直是这么写的。
 *
 * @author Jack
 */
class TMDbClientSummarizeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    @Test
    void images_那种对象报各数组字段的长度() throws Exception {
        String s = TMDbClient.summarize(json(
                "{\"backdrops\":[{\"file_path\":\"/a.jpg\"},{\"file_path\":\"/b.jpg\"}],"
                        + "\"posters\":[{\"file_path\":\"/c.jpg\"}],\"logos\":[],\"id\":65942}"));

        assertEquals("{backdrops=2, posters=1, logos=0}", s);
        // 响应体本身一个字都不许出现
        assertFalse(s.contains("file_path"));
    }

    @Test
    void 顶层就是数组时报条数() throws Exception {
        assertEquals("3 条", TMDbClient.summarize(json("[{\"a\":1},{\"a\":2},{\"a\":3}]")));
    }

    @Test
    void 没有数组字段的对象报字段数_externalIds那种() throws Exception {
        // external_ids 全是标量字段，逐个列出去没有意义，报个数即可
        String s = TMDbClient.summarize(json(
                "{\"id\":65942,\"imdb_id\":\"tt5607616\",\"tvdb_id\":305089,\"wikidata_id\":\"Q65086\"}"));

        assertEquals("4 个字段", s);
        assertFalse(s.contains("tt5607616"));
    }

    @Test
    void 空对象与null不炸() throws Exception {
        assertEquals("0 个字段", TMDbClient.summarize(json("{}")));
        assertEquals("null", TMDbClient.summarize(null));
        assertEquals("null", TMDbClient.summarize(json("null")));
    }

    @Test
    void 大响应体压成一行短摘要() throws Exception {
        StringBuilder sb = new StringBuilder("{\"backdrops\":[");
        for (int i = 0; i < 300; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"file_path\":\"/someVeryLongTmdbPath").append(i).append(".jpg\",\"width\":3840}");
        }
        JsonNode fat = json(sb.append("]}").toString());

        assertTrue(fat.toString().length() > 10000, "样本本身要足够大才说明问题");
        assertEquals("{backdrops=300}", TMDbClient.summarize(fat));
    }
}
