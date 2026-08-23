package com.osr.openliststrm.rename.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MediaInfo.toString() 不许把 TMDb 原始响应体带出来。
 * <p>
 * MediaParser 会对<b>每个</b>刮削的文件把整个 MediaInfo 打进日志。metadata 里装的是
 * images / content_ratings / external_ids 的完整 JsonNode，一份 tv images 实测 26 KB——
 * 生产日志里 12 个文件的这一条就占掉整份文件的 29.2%，单行最高 58 KB，
 * 而真正要看的 title/season/episode/tmdbId 全在前 600 字符。
 * 同一份 images JSON 在 TMDbClient 那侧还各有一条摘要，这里再全量打一遍是第二次重复。
 *
 * @author Jack
 */
class MediaInfoToStringTest {

    /** 造一份形状与 TMDb images 一致、体量足够大的响应 */
    private static com.fasterxml.jackson.databind.JsonNode fatImages() throws Exception {
        StringBuilder sb = new StringBuilder("{\"backdrops\":[");
        for (int i = 0; i < 200; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"file_path\":\"/aVeryLongTmdbFilePathThatLooksLikeThis").append(i)
              .append(".jpg\",\"width\":3840,\"height\":2160,\"vote_average\":5.4}");
        }
        return new ObjectMapper().readTree(sb.append("],\"posters\":[],\"logos\":[]}").toString());
    }

    private static MediaInfo sample() throws Exception {
        MediaInfo info = new MediaInfo("Re.Zero.kara.Hajimeru.Isekai.Seikatsu.S01E59.strm");
        info.setTitle("Re：从零开始的异世界生活");
        info.setTmdbId("65942");
        info.setSeason("01");
        info.setEpisode("59");
        info.setResolution("1080p");
        info.getMetadata().put("images", fatImages());
        return info;
    }

    @Test
    void 不带出响应体_整行保持可读长度() throws Exception {
        String s = sample().toString();

        // 响应体里的内容一个字都不该出现
        assertFalse(s.contains("aVeryLongTmdbFilePathThatLooksLikeThis"),
                "metadata 的原始 JSON 不该进 toString");
        assertFalse(s.contains("vote_average"), "metadata 的原始 JSON 不该进 toString");
        assertTrue(s.length() < 1000, "整行应保持可读长度，实际 " + s.length() + " 字符：" + s);
    }

    @Test
    void 业务字段一个都不能丢() throws Exception {
        String s = sample().toString();

        // 排除 metadata 不等于把有用的一起排掉——这些正是这条日志存在的理由
        assertTrue(s.contains("Re：从零开始的异世界生活"));
        assertTrue(s.contains("65942"));
        assertTrue(s.contains("episode=59"));
        assertTrue(s.contains("1080p"));
        assertTrue(s.contains("Re.Zero.kara.Hajimeru.Isekai.Seikatsu.S01E59.strm"));
    }

    @Test
    void 仍要说清元数据拉到了没_以及各有多大() throws Exception {
        String s = sample().toString();

        // 摘要替身：回答「元数据拉到了没」，这是全量打印唯一还在被用来回答的问题
        assertTrue(s.contains("metadata="), "metadata 该有一个摘要替身：" + s);
        assertTrue(s.contains("images:"), "摘要该报出有哪些 key：" + s);
        assertTrue(s.contains("KB"), "摘要该报出各自多大：" + s);
    }

    @Test
    void 没有元数据时摘要为空对象_不写null() {
        String s = new MediaInfo("Show.S01E01.mkv").toString();
        assertTrue(s.contains("metadata={}"), s);
    }
}
