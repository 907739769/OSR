package com.osr.openliststrm.pt.health;

import com.osr.openliststrm.pt.health.SubtitleDetector.SubtitleStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubtitleDetectorTest {

    /** 与过滤规则「外语片需中字」同一份正则：那边放行的种子，这边不能反过来说它没中字 */
    @Test
    void 标题带中字标识_判为有中文字幕() {
        assertEquals(SubtitleStatus.ZH, SubtitleDetector.detect("The.Show.S01E01.1080p.WEB-DL.CHS", null, List.of()));
        assertEquals(SubtitleStatus.ZH, SubtitleDetector.detect("某剧 S01 简繁英 字幕", null, List.of()));
    }

    @Test
    void 外挂字幕文件名带中文标识_判为有中文字幕() {
        assertEquals(SubtitleStatus.ZH, SubtitleDetector.detect("The.Show.S01E01.1080p", null,
                List.of("The.Show.S01E01.mkv", "Subs/The.Show.S01E01.chs.ass")));
        assertEquals(SubtitleStatus.ZH, SubtitleDetector.detect("The.Show.S01E01.1080p", null,
                List.of("The.Show.S01E01.mkv", "The.Show.S01E01.简体.srt")));
    }

    @Test
    void 有字幕文件但认不出语言_判为语言未知() {
        assertEquals(SubtitleStatus.OTHER, SubtitleDetector.detect("The.Show.S01E01.1080p", null,
                List.of("The.Show.S01E01.mkv", "The.Show.S01E01.en.srt")));
    }

    /** 目录名里的「中字」说的是整部发布、不是这个字幕文件，只看文件名 */
    @Test
    void 只看字幕文件名不看目录名() {
        assertEquals(SubtitleStatus.OTHER, SubtitleDetector.detect("The.Show.S01E01", null,
                List.of("The.Show.中字版/The.Show.S01E01.eng.srt")));
    }

    /** 英文缩写要求两侧不是字母：Scene、TCP 这类词不能误中 */
    @Test
    void 英文缩写不误中普通单词() {
        assertEquals(SubtitleStatus.OTHER, SubtitleDetector.detect("X", null, List.of("Scene.Release.srt")));
    }

    @Test
    void 什么都没有_判为未识别到() {
        assertEquals(SubtitleStatus.NONE, SubtitleDetector.detect("The.Show.S01E01.1080p", null,
                List.of("The.Show.S01E01.mkv", "sample.mkv")));
    }
}
