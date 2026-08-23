package com.osr.openliststrm.monitor.processor;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在途产物识别的边界。
 * <p>
 * 漏判一个临时文件不是「多跑一次」这么便宜：它每被写一次就来一个 ENTRY_MODIFY，
 * 每个事件都要进 {@code isFileStable} 睡两秒。实测 115 客户端的 {@code .<sha1>.parts}
 * 在 3.016 秒内触发 569 个事件，也就是同一个文件上同时挂着 569 个各睡两秒的线程。
 * 反过来误判一个真实媒体文件则是彻底不处理，所以这两侧都要钉住。
 *
 * @author Jack
 */
class FileStabilityUtilsTest {

    private static boolean transient_(String name) {
        return FileStabilityUtils.isTransientArtifact(Paths.get("/download/osr/剧集/2016", name));
    }

    @Test
    void 复数形态的_parts_要认出来() {
        // 这条正是漏判过的：清单里原先只有 ".part"，而 endsWith(".part") 对 ".parts" 为 false
        assertTrue(transient_("7a6b056a2a0dffdd07b9bfc0dabcffdeddc74297.parts"));
    }

    @Test
    void 已知后缀逐个认出来() {
        assertTrue(transient_("Show.S01E01.mkv.!qB"));
        assertTrue(transient_("Show.S01E01.mkv.part"));
        assertTrue(transient_("Show.S01E01.mkv.tmp"));
        assertTrue(transient_("Show.S01E01.mkv.downloading"));
        assertTrue(transient_("Show.S01E01.mkv.aria2"));
        assertTrue(transient_("Show.S01E01.mkv.crdownload"));
    }

    @Test
    void 后缀大小写不敏感() {
        // qB 写的是 ".!qB"，但别的客户端未必跟着大小写走
        assertTrue(transient_("Show.S01E01.mkv.!QB"));
        assertTrue(transient_("Show.S01E01.mkv.PART"));
    }

    @Test
    void 点开头的一律跳过_挡住清单没覆盖到的形态() {
        // 各家客户端的临时文件几乎都是「点 + 哈希」，而媒体文件不会以点开头。
        // 这条是清单之外的第二道，专门挡未知形态
        assertTrue(transient_(".7a6b056a2a0dffdd07b9bfc0dabcffdeddc74297"));
        assertTrue(transient_(".DS_Store"));
    }

    @Test
    void 正常媒体文件一个都不许误判() {
        assertFalse(transient_("Re.Zero.kara.Hajimeru.Isekai.Seikatsu.S01E59.strm"));
        assertFalse(transient_("喜剧之王单口季.King.of.Comedy.S03E21.2024.2160p.WEB-DL.H.265.AAC2.0-HHWEB.mkv"));
        assertFalse(transient_("Show.S01E01.srt"));
        // 名字里带 part 但不是后缀
        assertFalse(transient_("Re Zero kara Hajimeru Isekai Seikatsu S03 Part2.mkv"));
    }

    @Test
    void 比后缀还短的文件名不越界() {
        assertFalse(transient_("a"));
        assertFalse(transient_("ab.mkv"));
    }

    @Test
    void 没有文件名的路径不判为临时() {
        Path root = Paths.get("/");
        assertFalse(FileStabilityUtils.isTransientArtifact(root));
    }
}
