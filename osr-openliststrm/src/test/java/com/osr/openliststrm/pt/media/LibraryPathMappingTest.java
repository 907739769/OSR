package com.osr.openliststrm.pt.media;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryPathMappingTest {

    @Test
    void 没有规则时按原路径() {
        LibraryPathMapping.Mapped mapped = LibraryPathMapping.parse(null).map("/data/media/电视剧/三体/");

        assertEquals("/data/media/电视剧/三体", mapped.path());
        assertNull(mapped.rule(), "没有规则命中时 rule 必须为空，通知那边据此区分「配错了」与「本来就不是库」");
    }

    @Test
    void 取最长前缀_且必须落在分隔符上() {
        LibraryPathMapping mapping = LibraryPathMapping.parse("""
                /data => /mnt
                /data/media => /media
                """);

        assertEquals("/media/电视剧/三体", mapping.map("/data/media/电视剧/三体").path());
        // /data/media2 不能被 /data/media 覆盖，只能退到 /data 那条
        assertEquals("/mnt/media2/x", mapping.map("/data/media2/x").path());
        assertEquals("/media", mapping.map("/data/media").path());
    }

    @Test
    void 两边末尾斜杠都不影响结果() {
        LibraryPathMapping mapping = LibraryPathMapping.parse("/data/media/ => /media/");

        assertEquals("/media/电影/阿凡达 (2009)", mapping.map("/data/media/电影/阿凡达 (2009)").path());
    }

    @Test
    void 目标是Windows路径时余下部分换成反斜杠() {
        LibraryPathMapping mapping = LibraryPathMapping.parse("""
                /data/media => D:\\Media
                /data/nas => \\\\nas\\share\\
                """);

        assertEquals("D:\\Media\\电视剧\\三体", mapping.map("/data/media/电视剧/三体").path());
        assertEquals("\\\\nas\\share\\电影", mapping.map("/data/nas/电影").path());
    }

    @Test
    void 根目录规则覆盖一切() {
        assertEquals("/host/data/a", LibraryPathMapping.parse("/ => /host").map("/data/a").path());
    }

    @Test
    void 空行注释与格式不对的行在解析时跳过() {
        LibraryPathMapping mapping = LibraryPathMapping.parse("""

                # 注释
                写错的一行
                /data/media => /media
                """);

        assertEquals(1, mapping.rules().size());
    }

    @Test
    void 校验_格式不对给出行号() {
        assertNull(LibraryPathMapping.validate(null));
        assertNull(LibraryPathMapping.validate("# 只有注释\n\n/data => /media"));
        assertTrue(LibraryPathMapping.validate("/data => /media\n/data/x").startsWith("路径映射第 2 行格式不对"));
        assertNotNull(LibraryPathMapping.validate("/data =>"));
    }

    @Test
    void 校验_左边不是绝对路径多半是写反了() {
        String error = LibraryPathMapping.validate("D:\\Media => /data/media");

        assertTrue(error.contains("左边应是 OSR 容器里的绝对路径"), error);
    }

    @Test
    void 找库目录_取最深的那个() {
        LibraryRoot all = new LibraryRoot("全部", "/media", "1");
        LibraryRoot tv = new LibraryRoot("电视剧", "/media/电视剧", "2");

        assertSame(tv, LibraryPathMapping.findRoot(List.of(all, tv), "/media/电视剧/三体/Season 1"));
        assertSame(all, LibraryPathMapping.findRoot(List.of(all, tv), "/media/电影/x"));
        assertNull(LibraryPathMapping.findRoot(List.of(all, tv), "/media2/x"), "同前缀的兄弟目录不能算进库");
    }

    @Test
    void 找库目录_Windows路径不分大小写与分隔符() {
        LibraryRoot root = new LibraryRoot("电视剧", "D:\\Media\\TV\\", "1");

        assertSame(root, LibraryPathMapping.findRoot(List.of(root), "d:\\media\\tv\\三体"));
        assertFalse(LibraryPathMapping.isUnder("D:\\Media\\TV2", "D:\\Media\\TV"));
    }

    @Test
    void Linux路径区分大小写() {
        assertFalse(LibraryPathMapping.isUnder("/Media/TV", "/media"));
    }
}
