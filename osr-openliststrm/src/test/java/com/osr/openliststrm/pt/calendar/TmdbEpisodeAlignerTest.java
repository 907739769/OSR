package com.osr.openliststrm.pt.calendar;

import com.osr.openliststrm.pt.calendar.TmdbEpisodeAligner.TmdbEpisodeRef;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TMDb 集号与本地集号的对齐。
 * <p>
 * 真实触发场景：《航海王》第 23 季在 TMDb 上的 episode_number 是 1156 起（绝对集号），
 * 本地按季内相对集号存 1..26。种子标题 {@code One Piece S23E13 Episode 1168} 独立印证了
 * 这个对应关系。对齐结果同时供两处使用：追剧日历取播出日期，媒体库对账取绝对集号。
 * </p>
 */
class TmdbEpisodeAlignerTest {

    private static Map<Integer, LocalDate> tmdb(int firstNumber, String firstDate, int count) {
        Map<Integer, LocalDate> map = new TreeMap<>();
        LocalDate date = LocalDate.parse(firstDate);
        for (int i = 0; i < count; i++) {
            map.put(firstNumber + i, date.plusWeeks(i));
        }
        return map;
    }

    @Test
    void 集号能对上时按集号对() {
        Map<Integer, TmdbEpisodeRef> aligned = TmdbEpisodeAligner.align(List.of(1, 2, 3), tmdb(1, "2026-08-01", 3));

        assertEquals(LocalDate.parse("2026-08-01"), aligned.get(1).airDate());
        assertEquals(LocalDate.parse("2026-08-15"), aligned.get(3).airDate());
        assertEquals(1, aligned.get(1).episodeNumber(), "普通剧集的 TMDb 集号与本地集号一致");
        assertEquals(3, aligned.get(3).episodeNumber());
    }

    @Test
    void 绝对集号与相对集号_集数相等时按位置兜底() {
        // 航海王第23季：TMDb 1156..1159，本地 1..4
        Map<Integer, TmdbEpisodeRef> aligned = TmdbEpisodeAligner.align(List.of(1, 2, 3, 4), tmdb(1156, "2026-04-05", 4));

        assertEquals(4, aligned.size());
        assertEquals(LocalDate.parse("2026-04-05"), aligned.get(1).airDate());
        assertEquals(LocalDate.parse("2026-04-26"), aligned.get(4).airDate());
        // 关键：本地第 1 集要对出绝对号 1156——媒体库按绝对编号存的就是这个号
        assertEquals(1156, aligned.get(1).episodeNumber());
        assertEquals(1159, aligned.get(4).episodeNumber());
    }

    @Test
    void 集数对不上时宁可留空也不错位() {
        // 错位的对应关系会同时污染播出日期和入库判定，比没有更糟
        assertTrue(TmdbEpisodeAligner.align(List.of(1, 2, 3), tmdb(1156, "2026-04-05", 5)).isEmpty());
        assertTrue(TmdbEpisodeAligner.align(List.of(1, 2, 3, 4, 5), tmdb(1156, "2026-04-05", 3)).isEmpty());
    }

    @Test
    void 部分集号对得上时不启用位置兜底() {
        // 只要有一个对得上就说明两边是同一套编号，缺的那些是 TMDb 没录，不该猜
        Map<Integer, LocalDate> partial = new TreeMap<>();
        partial.put(2, LocalDate.parse("2026-08-08"));

        Map<Integer, TmdbEpisodeRef> aligned = TmdbEpisodeAligner.align(List.of(1, 2, 3), partial);

        assertEquals(1, aligned.size());
        assertEquals(LocalDate.parse("2026-08-08"), aligned.get(2).airDate());
        assertEquals(2, aligned.get(2).episodeNumber());
    }

    @Test
    void 本地集号乱序时也按升序对齐() {
        Map<Integer, TmdbEpisodeRef> aligned = TmdbEpisodeAligner.align(List.of(3, 1, 2), tmdb(1156, "2026-04-05", 3));

        assertEquals(1156, aligned.get(1).episodeNumber());
        assertEquals(1157, aligned.get(2).episodeNumber());
        assertEquals(1158, aligned.get(3).episodeNumber());
    }

    @Test
    void TMDb缺日期时仍要给出集号() {
        // 未定档的未来集：日历排不进去，但入库对账仍然需要那个绝对集号
        Map<Integer, LocalDate> withNulls = new TreeMap<>();
        withNulls.put(1156, LocalDate.parse("2026-04-05"));
        withNulls.put(1157, null);

        Map<Integer, TmdbEpisodeRef> aligned = TmdbEpisodeAligner.align(List.of(1, 2), withNulls);

        assertEquals(1157, aligned.get(2).episodeNumber());
        assertTrue(aligned.get(2).airDate() == null);
    }

    @Test
    void 空输入返回空表不抛异常() {
        assertTrue(TmdbEpisodeAligner.align(null, tmdb(1, "2026-08-01", 3)).isEmpty());
        assertTrue(TmdbEpisodeAligner.align(List.of(), tmdb(1, "2026-08-01", 3)).isEmpty());
        assertTrue(TmdbEpisodeAligner.align(List.of(1, 2), null).isEmpty());
        assertTrue(TmdbEpisodeAligner.align(List.of(1, 2), Map.of()).isEmpty());
    }
}
