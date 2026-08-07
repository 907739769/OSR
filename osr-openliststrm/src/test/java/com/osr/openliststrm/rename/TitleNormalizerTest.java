package com.osr.openliststrm.rename;

import org.junit.jupiter.api.Test;

import static com.osr.openliststrm.rename.TitleNormalizer.normalizeForCompare;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 比较用标题归一化。PT 订阅匹配与 TMDb 刮削共用这一份，任何一侧改口径都会在这里体现。
 */
class TitleNormalizerTest {

    @Test
    void 空输入返回null() {
        assertNull(normalizeForCompare(null));
        assertNull(normalizeForCompare(""));
        assertNull(normalizeForCompare("   "));
        // 全是标点，归一化后为空
        assertNull(normalizeForCompare("---...___"));
    }

    @Test
    void 转小写并把点下划线连字符压成空格() {
        assertEquals("some show", normalizeForCompare("Some.Show"));
        assertEquals("some show", normalizeForCompare("some_show"));
        assertEquals("some show", normalizeForCompare("SOME-SHOW"));
        assertEquals("some show", normalizeForCompare("  Some...Show  "));
    }

    @Test
    void 全角空格连字符句号都能识别() {
        // Java 的 \s 不认全角空格 U+3000，日剧/韩剧标题里它极常见
        assertEquals("孤独的 美食家", normalizeForCompare("孤独的　美食家"));
        assertEquals("孤独的 美食家", normalizeForCompare("孤独的－美食家"));
        assertEquals("some show", normalizeForCompare("Some．Show"));
        // 三者归一化后一致，才谈得上「同一部作品的不同写法能对上」
        assertEquals(normalizeForCompare("孤独的 美食家"), normalizeForCompare("孤独的　美食家"));
    }

    @Test
    void 中英文标点都被抹掉_这是本次修复的核心() {
        // 全角冒号：《神探夏洛克：可恶的新娘》曾因此在 PT 侧漏搜、在刮削侧却能匹配
        assertEquals(normalizeForCompare("神探夏洛克 可恶的新娘"),
                normalizeForCompare("神探夏洛克：可恶的新娘"));
        // 半角冒号 + 破折号
        assertEquals(normalizeForCompare("Mission Impossible Fallout"),
                normalizeForCompare("Mission: Impossible - Fallout"));
        // 中点
        assertEquals(normalizeForCompare("WALL E"), normalizeForCompare("WALL·E"));
        // 撇号
        assertEquals(normalizeForCompare("Marvel s Daredevil"),
                normalizeForCompare("Marvel's Daredevil"));
        // 书名号、感叹号
        assertEquals(normalizeForCompare("进击的巨人"), normalizeForCompare("《进击的巨人》"));
        assertEquals(normalizeForCompare("你好 李焕英"), normalizeForCompare("你好，李焕英！"));
    }

    @Test
    void 波浪号被抹掉_它在Unicode里算符号不算标点() {
        // ～(U+FF5E) 与 〜(U+301C) 不属于 \p{IsPunctuation}，必须显式覆盖
        assertEquals(normalizeForCompare("夏日大作战"), normalizeForCompare("夏日大作战～"));
        assertEquals(normalizeForCompare("夏日大作战"), normalizeForCompare("夏日大作战〜"));
    }

    @Test
    void 标点替换成空格而不是删除_避免不同作品塌到一起() {
        // 删除的话 M*A*S*H 会塌成 mash，误撞另一部叫 MASH 的作品
        assertEquals("m a s h", normalizeForCompare("M*A*S*H"));
        assertNotEquals(normalizeForCompare("MASH"), normalizeForCompare("M*A*S*H"));
    }

    @Test
    void 不同作品不会因为归一化而相等() {
        // 全等比较的保护边界：The Office 不能吃掉 The Office US
        assertNotEquals(normalizeForCompare("The Office"), normalizeForCompare("The Office US"));
        assertNotEquals(normalizeForCompare("三体"), normalizeForCompare("三体动画版"));
    }
}
