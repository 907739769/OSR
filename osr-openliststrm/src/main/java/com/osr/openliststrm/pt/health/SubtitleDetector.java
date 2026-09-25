package com.osr.openliststrm.pt.health;

import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 从一个种子本身判断它带不带中文字幕，不联网：标题/描述里的中字标识 + 文件列表里的外挂字幕文件。
 * <p>
 * <b>只能判「识别到」与「没识别到」，判不出「确定没有」</b>：不少内封中字的种子标题里什么都不写，
 * 体检的文案因此一律写「未识别到中文字幕」，不写「没有中文字幕」。
 *
 * @author Jack
 */
public final class SubtitleDetector {

    /** 下载完成时识别到的字幕情况，落库在 {@code pt_download_record.subtitle} */
    public enum SubtitleStatus {
        /** 识别到中文字幕（标题/描述带中字标识，或有文件名带中文标识的外挂字幕） */
        ZH("有中文字幕"),
        /** 有外挂字幕文件，但从文件名认不出是中文 */
        OTHER("有字幕，语言未知"),
        /** 标题、描述、文件名里都没有识别到中文字幕 */
        NONE("未识别到中文字幕");

        private final String label;

        SubtitleStatus(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static SubtitleStatus of(String value) {
            if (value == null) {
                return null;
            }
            for (SubtitleStatus s : values()) {
                if (s.name().equals(value)) {
                    return s;
                }
            }
            return null;
        }
    }

    /** 外挂字幕的扩展名。idx/sub 是 VobSub 的一对，sup 是蓝光的 PGS 图形字幕 */
    static final Set<String> SUBTITLE_EXTENSIONS = Set.of("srt", "ass", "ssa", "vtt", "sup", "idx", "sub");

    /**
     * 字幕文件名里的中文标识。英文缩写要求两侧不是字母，免得 {@code Scene}、{@code TCP} 这类词误中；
     * 中文字直接出现就算（「简」「繁」「中」在字幕文件名里几乎只有这一种意思）。
     */
    private static final Pattern CHINESE_FILE_MARK = Pattern.compile(
            "(?<![a-z])(chs|cht|sc|tc|zh|zho|chi|chinese|gb|big5)(?![a-z])|[简繁中]",
            Pattern.CASE_INSENSITIVE);

    private SubtitleDetector() {
    }

    /**
     * @param fileNames 下载器返回的文件名（可含目录），拿不到时传空表
     */
    public static SubtitleStatus detect(String title, String description, List<String> fileNames) {
        if (TorrentFilterEngine.hasChineseSubtitleMark(title) || TorrentFilterEngine.hasChineseSubtitleMark(description)) {
            return SubtitleStatus.ZH;
        }
        boolean anySubtitleFile = false;
        for (String name : fileNames == null ? List.<String>of() : fileNames) {
            if (!isSubtitleFile(name)) {
                continue;
            }
            anySubtitleFile = true;
            if (CHINESE_FILE_MARK.matcher(baseName(name)).find()) {
                return SubtitleStatus.ZH;
            }
        }
        return anySubtitleFile ? SubtitleStatus.OTHER : SubtitleStatus.NONE;
    }

    static boolean isSubtitleFile(String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && SUBTITLE_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** 只看文件名、不看目录：目录名常是整部剧的发布名，里面的「中字」说的是视频不是这个字幕文件 */
    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
