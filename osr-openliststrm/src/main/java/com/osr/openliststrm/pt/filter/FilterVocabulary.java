package com.osr.openliststrm.pt.filter;

import java.util.List;

/**
 * 过滤 / 洗版规则里「分辨率、来源、质量标签」三类字段的可选值，供前端渲染下拉。
 * <p>
 * 这三类字段后端一律按<b>全等</b>（大小写不敏感）比对，而取值来自本地解析器的归一化结果：
 * 手打的 {@code WEB-DL}、{@code 4K}、{@code HDR 10} 永远命不中，也不会有任何报错。
 * 所以前端改成从这里选，不再自由输入。
 * </p>
 * <p>
 * <b>这张表必须跟着解析器走</b>：{@code ResolutionExtractor#normalizeResolution}、
 * {@code SourceAndGroupExtractor} 的 SOURCE/TAGS 正则、{@code CodecExtractor} 的编码归一化。
 * {@code FilterVocabularyTest} 拿真实解析器逐个标题跑一遍，防的就是两边漂移。
 * </p>
 *
 * @author Jack
 */
public final class FilterVocabulary {

    private FilterVocabulary() {
    }

    /** 分辨率，按从高到低排列（前端据此给出默认优先级） */
    public static final List<String> RESOLUTIONS = List.of(
            "4320p", "2160p", "1440p", "1080p", "1080i", "720p", "576p", "480p", "360p");

    /**
     * 媒介来源，大致按画质从高到低。REMUX 由 {@link MediaSource} 从标签还原而来，
     * 其余是 {@code SourceAndGroupExtractor} 去掉连字符后的归一化形式（比对不分大小写）。
     */
    public static final List<String> SOURCES = List.of(
            "REMUX", "BluRay", "WEBDL", "WEBRip", "WEB", "HDTV", "BDRip", "BRRip", "HDRip",
            "DVD", "DVDRip", "CAM");

    /**
     * 质量标签：{@code MediaInfo.tags} ∪ 视频编码 ∪ 音频编码（见 {@code QualityProfile#collectTags}）。
     * 按「画面 → 音频 → 编码 → 其它」分组排列，便于在下拉里找。
     */
    public static final List<String> TAGS = List.of(
            // 画面
            "Dolby Vision", "HDR10+", "HDR10", "HDR", "10BIT", "12BIT", "60FPS", "HFR", "IMAX",
            // 音频
            "Atmos", "TrueHD", "DTS-HD", "DTSX", "DTS", "EAC3", "AC3", "AAC", "FLAC", "OPUS",
            // 视频编码
            "H265", "H264", "AV1",
            // 其它
            "REMUX", "PROPER", "REPACK", "NF", "AMZN", "DSNP", "HMAX", "IQIYI", "YOUKU");

    /** 返回给前端的整包 */
    public record Options(List<String> resolutions, List<String> sources, List<String> tags) {
    }

    public static Options options() {
        return new Options(RESOLUTIONS, SOURCES, TAGS);
    }
}
