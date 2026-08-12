package com.osr.openliststrm.pt.calendar;

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
 * 本地按季内相对集号存 1..26，直接按集号取会整季取空。
 * </p>
 */
class AirDateResolverTest {

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
        Map<Integer, LocalDate> resolved = AirDateResolver.resolve(
                List.of(1, 2, 3), tmdb(1, "2026-08-01", 3));

        assertEquals(LocalDate.parse("2026-08-01"), resolved.get(1));
        assertEquals(LocalDate.parse("2026-08-15"), resolved.get(3));
    }

    @Test
    void 绝对集号与相对集号_集数相等时按位置兜底() {
        // 航海王第23季：TMDb 1156..1181，本地 1..26
        Map<Integer, LocalDate> resolved = AirDateResolver.resolve(
                List.of(1, 2, 3, 4), tmdb(1156, "2026-04-05", 4));

        assertEquals(4, resolved.size());
        assertEquals(LocalDate.parse("2026-04-05"), resolved.get(1));
        assertEquals(LocalDate.parse("2026-04-26"), resolved.get(4));
    }

    @Test
    void 集数对不上时宁可留空也不错位() {
        // 错位的播出日期比没有日期更糟：用户会照着它去等一集根本不在那天播的剧
        assertTrue(AirDateResolver.resolve(List.of(1, 2, 3), tmdb(1156, "2026-04-05", 5)).isEmpty());
        assertTrue(AirDateResolver.resolve(List.of(1, 2, 3, 4, 5), tmdb(1156, "2026-04-05", 3)).isEmpty());
    }

    @Test
    void 部分集号对得上时不启用位置兜底() {
        // 只要有一个对得上就说明两边是同一套编号，缺的那些是 TMDb 没录日期，不该猜
        Map<Integer, LocalDate> partial = new TreeMap<>();
        partial.put(2, LocalDate.parse("2026-08-08"));

        Map<Integer, LocalDate> resolved = AirDateResolver.resolve(List.of(1, 2, 3), partial);

        assertEquals(1, resolved.size());
        assertEquals(LocalDate.parse("2026-08-08"), resolved.get(2));
    }

    @Test
    void 本地集号乱序时也按升序对齐() {
        Map<Integer, LocalDate> resolved = AirDateResolver.resolve(
                List.of(3, 1, 2), tmdb(1156, "2026-04-05", 3));

        assertEquals(LocalDate.parse("2026-04-05"), resolved.get(1));
        assertEquals(LocalDate.parse("2026-04-12"), resolved.get(2));
        assertEquals(LocalDate.parse("2026-04-19"), resolved.get(3));
    }

    @Test
    void 空输入返回空表不抛异常() {
        assertTrue(AirDateResolver.resolve(null, tmdb(1, "2026-08-01", 3)).isEmpty());
        assertTrue(AirDateResolver.resolve(List.of(), tmdb(1, "2026-08-01", 3)).isEmpty());
        assertTrue(AirDateResolver.resolve(List.of(1, 2), null).isEmpty());
        assertTrue(AirDateResolver.resolve(List.of(1, 2), Map.of()).isEmpty());
    }
}
