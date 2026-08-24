package com.osr.openliststrm.rename;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 季号后缀的剥离与解析。这个坑在两条链路上各踩过一次（刮削侧把《梦魇绝镇 第四季》刮成另一部剧，
 * 豆瓣源把「瑞克和莫蒂 第九季」整批记成未匹配），判据收口在这里之后不可能再分叉。
 */
class SeasonSuffixTest {

    // ---------- strip ----------

    @Test
    void strip_中文数字季号() {
        assertEquals("瑞克和莫蒂", SeasonSuffix.strip("瑞克和莫蒂 第九季"));
        assertEquals("百年孤独", SeasonSuffix.strip("百年孤独 第二季"));
        assertEquals("梦魇绝镇", SeasonSuffix.strip("梦魇绝镇 第四季"));
    }

    @Test
    void strip_阿拉伯数字季号() {
        assertEquals("三体", SeasonSuffix.strip("三体 第2季"));
        assertEquals("三体", SeasonSuffix.strip("三体 第 2 部"));
    }

    @Test
    void strip_英文Season写法() {
        assertEquals("Rick and Morty", SeasonSuffix.strip("Rick and Morty Season 9"));
        assertEquals("Rick and Morty", SeasonSuffix.strip("Rick and Morty season 9"));
    }

    @Test
    void strip_连写多个季号() {
        assertEquals("进击的巨人", SeasonSuffix.strip("进击的巨人 第四季 Season 4"));
    }

    @Test
    void strip_没有季号时原样返回() {
        assertEquals("漫长的季节", SeasonSuffix.strip("漫长的季节"));
    }

    @Test
    void strip_季号在中间不剥() {
        // 只匹配结尾。片名里本来就含「第一季」字样的情况极少，但剥中间会把作品名切断
        assertEquals("第一季的故事", SeasonSuffix.strip("第一季的故事"));
    }

    @Test
    void strip_整个标题就是季号时不剥() {
        // 《第五季》是比利时片 La cinquième saison，剥掉会交出一个空标题
        assertEquals("第五季", SeasonSuffix.strip("第五季"));
    }

    @Test
    void strip_null安全() {
        assertNull(SeasonSuffix.strip(null));
    }

    @Test
    void strip_不会误伤含季字的片名() {
        assertEquals("漫长的季节", SeasonSuffix.strip("漫长的季节"));
        assertEquals("四季物语", SeasonSuffix.strip("四季物语"));
    }

    // ---------- parse ----------

    @Test
    void parse_中文数字() {
        assertEquals(9, SeasonSuffix.parse("瑞克和莫蒂 第九季"));
        assertEquals(2, SeasonSuffix.parse("百年孤独 第二季"));
        assertEquals(4, SeasonSuffix.parse("梦魇绝镇 第四季"));
    }

    @Test
    void parse_中文数字省略前导一() {
        // 「第十季」是最常见的写法，不是「第一十季」
        assertEquals(10, SeasonSuffix.parse("某剧 第十季"));
        assertEquals(11, SeasonSuffix.parse("某剧 第十一季"));
    }

    @Test
    void parse_中文数字两位() {
        assertEquals(20, SeasonSuffix.parse("某剧 第二十季"));
        assertEquals(21, SeasonSuffix.parse("某剧 第二十一季"));
        assertEquals(99, SeasonSuffix.parse("某剧 第九十九季"));
    }

    @Test
    void parse_两当作2() {
        assertEquals(2, SeasonSuffix.parse("某剧 第两季"));
    }

    @Test
    void parse_阿拉伯数字与全角数字() {
        assertEquals(2, SeasonSuffix.parse("三体 第2季"));
        assertEquals(12, SeasonSuffix.parse("某剧 第 12 部"));
        assertEquals(3, SeasonSuffix.parse("某剧 第３季"));
    }

    @Test
    void parse_英文Season写法() {
        assertEquals(9, SeasonSuffix.parse("Rick and Morty Season 9"));
    }

    @Test
    void parse_连写多个时取最后一个() {
        assertEquals(4, SeasonSuffix.parse("进击的巨人 第四季 Season 4"));
    }

    @Test
    void parse_没有季号返回null() {
        assertNull(SeasonSuffix.parse("漫长的季节"));
        assertNull(SeasonSuffix.parse("四季物语"));
    }

    @Test
    void parse_整个标题就是季号时返回null() {
        // 判据与 strip 保持一致：那是作品名不是季号，读成「第 5 季」会去订《第五季》这部电影的第 5 季
        assertNull(SeasonSuffix.parse("第五季"));
    }

    @Test
    void parse_超出合理范围返回null() {
        // 猜错季号比不给季号糟得多：会订到一季根本不存在的内容并静静地一集都补不到
        assertNull(SeasonSuffix.parse("某剧 第一百季"));
        assertNull(SeasonSuffix.parse("某剧 第0季"));
        assertNull(SeasonSuffix.parse("某剧 第零季"));
    }

    @Test
    void parse_null与空安全() {
        assertNull(SeasonSuffix.parse(null));
        assertNull(SeasonSuffix.parse(""));
        assertNull(SeasonSuffix.parse("   "));
    }
}
