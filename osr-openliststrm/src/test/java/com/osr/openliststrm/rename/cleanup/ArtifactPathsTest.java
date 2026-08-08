package com.osr.openliststrm.rename.cleanup;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactPathsTest {

    @Test
    void 媒体库边界_取最靠近文件的那一层锚点() {
        Path p = Paths.get("/data/media/电视剧/国产剧/某剧 (2024)/Season 01");
        Path expected = Paths.get("/data/media/电视剧").toAbsolutePath().normalize();

        assertEquals(expected, ArtifactPaths.mediaRootOf(p));
    }

    @Test
    void 媒体库边界_路径里出现多次锚点时取最深的一次() {
        // 用户把库根目录也命名成"电影"是可能的，此时上层那个不是边界
        Path p = Paths.get("/data/电影/lib/电影/动作片/某片 (2020)");
        Path expected = Paths.get("/data/电影/lib/电影").toAbsolutePath().normalize();

        assertEquals(expected, ArtifactPaths.mediaRootOf(p));
    }

    @Test
    void 媒体库边界_找不到锚点返回null() {
        assertNull(ArtifactPaths.mediaRootOf(Paths.get("/data/random/dir")));
        assertNull(ArtifactPaths.mediaRootOf(null));
    }

    @Test
    void 元数据判定_认nfo图片字幕不认媒体文件() {
        assertTrue(ArtifactPaths.isMetadataFile("tvshow.nfo"));
        assertTrue(ArtifactPaths.isMetadataFile("poster.JPG"));
        assertTrue(ArtifactPaths.isMetadataFile("某剧.S01E01.srt"));
        assertFalse(ArtifactPaths.isMetadataFile("某剧.S01E01.strm"));
        assertFalse(ArtifactPaths.isMetadataFile("某剧.S01E01.mkv"));
        assertFalse(ArtifactPaths.isMetadataFile("README"));
        assertFalse(ArtifactPaths.isMetadataFile(null));
    }

    @Test
    void 去扩展名_只切最后一个点且不吃掉隐藏文件名() {
        assertEquals("a.b", ArtifactPaths.stripExtension("a.b.strm"));
        assertEquals("noext", ArtifactPaths.stripExtension("noext"));
        assertEquals(".hidden", ArtifactPaths.stripExtension(".hidden"));
    }

    @Test
    void LIKE转义_下划线与百分号不能当通配符漏出去() {
        // 路径里的下划线是发布组命名的常态，不转义会让「同剧还有别的记录」误判
        assertEquals("/tv/a\\_b", ArtifactPaths.escapeLike("/tv/a_b"));
        assertEquals("100\\%", ArtifactPaths.escapeLike("100%"));
        assertEquals("C:\\\\tv\\\\", ArtifactPaths.escapeLike("C:\\tv\\"));
        assertNull(ArtifactPaths.escapeLike(null));
    }
}
