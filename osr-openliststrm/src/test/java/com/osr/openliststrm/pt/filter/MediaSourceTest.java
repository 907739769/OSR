package com.osr.openliststrm.pt.filter;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REMUX 在 PT 侧的来源口径：解析器只给出「BluRay + 标签 REMUX」，这里把它还原成来源，
 * 且在没写 REMUX 的列表里按 BluRay 对待，保证升级前后行为不变。
 */
class MediaSourceTest {

    @Test
    void 带REMUX标签的蓝光_来源还原为REMUX() {
        assertEquals("REMUX", MediaSource.effective("BluRay", List.of("REMUX", "HDR10")));
        assertEquals("REMUX", MediaSource.effective(null, List.of("remux")));
    }

    @Test
    void 没有REMUX标签或来源不是蓝光_保留解析结果() {
        assertEquals("BluRay", MediaSource.effective("BluRay", List.of("HDR10")));
        assertEquals("WEBDL", MediaSource.effective("WEBDL", List.of("REMUX")));
        assertEquals(null, MediaSource.effective(null, null));
    }

    @Test
    void 白名单只写REMUX时_压制版蓝光被挡住_REMUX放行() {
        // 修复前只写 REMUX 的白名单会淘汰全部种子：解析结果里根本没有 REMUX 这个来源
        assertTrue(MediaSource.in(List.of("REMUX"), "REMUX"));
        assertFalse(MediaSource.in(List.of("REMUX"), "BluRay"));
    }

    @Test
    void 白名单没写REMUX但写了BluRay_REMUX照旧放行() {
        // 兼容：此前 REMUX 一直被当成 BluRay，写 BluRay 的用户升级后不能突然收不到 REMUX
        assertTrue(MediaSource.in(List.of("BluRay", "WEBDL"), "REMUX"));
        assertFalse(MediaSource.in(List.of("WEBDL"), "REMUX"));
    }

    @Test
    void 优先级写了REMUX时排在BluRay前_没写时与BluRay并列() {
        assertEquals(0, MediaSource.rank("REMUX", List.of("REMUX", "BluRay", "WEBDL")));
        assertEquals(1, MediaSource.rank("BluRay", List.of("REMUX", "BluRay", "WEBDL")));
        assertEquals(0, MediaSource.rank("REMUX", List.of("BluRay", "WEBDL")));
        assertEquals(2, MediaSource.rank("REMUX", List.of("WEBDL", "HDTV")));
    }
}
