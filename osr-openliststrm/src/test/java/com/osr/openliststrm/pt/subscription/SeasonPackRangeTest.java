package com.osr.openliststrm.pt.subscription;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SeasonPackRangeTest {

    @Test
    void 方括号区间_解析出起止集号() {
        SeasonPackRange.Range range = SeasonPackRange.parse("[Group] Some Anime S01 [01-26][1080p][BDRip]");

        assertEquals(1, range.start());
        assertEquals(26, range.end());
    }

    @Test
    void 方括号区间带完结标记_照常解析() {
        assertEquals(13, SeasonPackRange.parse("[Group] Some Anime S01 [01-13Fin][1080p]").end());
        assertEquals(26, SeasonPackRange.parse("[Group] Some Anime S01 [01-26END]").end());
        assertEquals(24, SeasonPackRange.parse("[Group] Some Anime S01【01-24完】").end());
    }

    @Test
    void 中文集数区间_解析出起止集号() {
        SeasonPackRange.Range range = SeasonPackRange.parse("某番剧 第1季 第01-26话 1080p");

        assertEquals(1, range.start());
        assertEquals(26, range.end());
    }

    @Test
    void 年份区间不会被当成集数区间() {
        // 集号限 1-3 位，四位年份天然不命中。误判的代价是把整季包切成一小段，
        // 剩下的集反复空搜，比不解析更糟
        assertNull(SeasonPackRange.parse("Some Show S01 (2019-2021) 1080p"));
        assertNull(SeasonPackRange.parse("[Group] Some Show S01 [2019-2021][1080p]"));
    }

    @Test
    void 裸区间不解析_只认括号与中文写法() {
        // 标题里的裸 01-26 与分辨率、促销时间、其它编号无从分辨，判不出来就当整季包，
        // 交给下载器文件列表的事后对账兜底
        assertNull(SeasonPackRange.parse("Some Show S01 01-26 1080p WEB-DL"));
    }

    @Test
    void 起止相同不算区间() {
        // [01-01] 既不是区间也没有信息量
        assertNull(SeasonPackRange.parse("[Group] Some Show S01 [01-01][1080p]"));
    }

    @Test
    void 空标题返回null() {
        assertNull(SeasonPackRange.parse(null));
        assertNull(SeasonPackRange.parse("   "));
    }

    @Test
    void 前面有不合法区间时_继续找后面的合法区间() {
        // 不能"第一个匹配就返回"：真正的集数区间可能排在噪声之后
        SeasonPackRange.Range range = SeasonPackRange.parse("[Group][01-01] Some Show S01 [02-26][1080p]");

        assertEquals(2, range.start());
        assertEquals(26, range.end());
    }
}
