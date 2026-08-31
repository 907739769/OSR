package com.osr.openliststrm.rename;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TrailingYearTest {

    @Test
    void 半角括号_剥掉并带出年份() {
        assertEquals("给阿嬷的情书", TrailingYear.strip("给阿嬷的情书 (2026)"));
        assertEquals("2026", TrailingYear.parse("给阿嬷的情书 (2026)"));
    }

    @Test
    void 全角括号同样支持() {
        assertEquals("给阿嬷的情书", TrailingYear.strip("给阿嬷的情书（2026）"));
        assertEquals("2026", TrailingYear.parse("给阿嬷的情书（2026）"));
    }

    @Test
    void 括号内外的空白一律容忍() {
        assertEquals("肖申克的救赎", TrailingYear.strip("肖申克的救赎 ( 1994 ) "));
        assertEquals("1994", TrailingYear.parse("肖申克的救赎 ( 1994 ) "));
    }

    @Test
    void 剥空则不剥_整个标题就是年份括号() {
        // 剥掉什么都不剩，交出空标题会让下游拿空串去搜 TMDb、去比对订阅标题
        assertEquals("(2026)", TrailingYear.strip("(2026)"));
        assertNull(TrailingYear.parse("(2026)"));
    }

    @Test
    void 尾部裸数字一概不动_速度与激情9的序号不是年份() {
        // 放宽成「尾部四位数字」会把序号、绝对集号剥掉，而那同样让标题全等判定落空且完全静默
        assertEquals("速度与激情 9", TrailingYear.strip("速度与激情 9"));
        assertEquals("银翼杀手 2049", TrailingYear.strip("银翼杀手 2049"));
        assertNull(TrailingYear.parse("银翼杀手 2049"));
    }

    @Test
    void 片名本身以年份为名_没有括号就不动() {
        assertEquals("1917", TrailingYear.strip("1917"));
        assertNull(TrailingYear.parse("1917"));
    }

    @Test
    void 括号不在尾部时不剥() {
        assertEquals("怪奇物语 (1985) 故事集", TrailingYear.strip("怪奇物语 (1985) 故事集"));
        assertNull(TrailingYear.parse("怪奇物语 (1985) 故事集"));
    }

    @Test
    void 括号里不是四位年份时不剥() {
        assertEquals("某剧 (第二季)", TrailingYear.strip("某剧 (第二季)"));
        assertEquals("某剧 (1080p)", TrailingYear.strip("某剧 (1080p)"));
        assertEquals("某剧 (12345)", TrailingYear.strip("某剧 (12345)"));
        assertNull(TrailingYear.parse("某剧 (1080p)"));
    }

    @Test
    void 空与null() {
        assertNull(TrailingYear.strip(null));
        assertNull(TrailingYear.parse(null));
        assertNull(TrailingYear.parse("   "));
        assertEquals("   ", TrailingYear.strip("   "));
    }
}
