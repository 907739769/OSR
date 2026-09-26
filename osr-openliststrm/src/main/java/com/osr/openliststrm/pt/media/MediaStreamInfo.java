package com.osr.openliststrm.pt.media;

import java.util.ArrayList;
import java.util.List;

/**
 * 媒体服务器解析出的一个条目（一集或一部电影）的音轨与字幕流，供字幕体检用。
 * <p>
 * 只保留判断「有没有中文字幕」要用的几个字段，不做任何判定——判定在 {@code pt/health/SubtitleHealthService}，
 * 这里保持为原始事实，接新的服务器时只需把它的字段搬进来。
 *
 * @param probed         服务器是否解析过这个文件的媒体信息。没解析过（刚入库、STRM 没被提取过）时
 *                       流列表是空的，这<b>不等于没有字幕</b>，必须与「解析过、确实没字幕」分开
 * @param audioLanguages 各音轨的语言码（可能为 null）
 * @param subtitles      字幕流，内封与外挂都在里面
 * @author Jack
 */
public record MediaStreamInfo(boolean probed, List<String> audioLanguages, List<SubtitleTrack> subtitles) {

    public static final MediaStreamInfo NOT_PROBED = new MediaStreamInfo(false, List.of(), List.of());

    /**
     * @param language 语言码（Emby 是 ISO 639-2，如 chi/eng；外挂字幕认不出时为 null 或 und）
     * @param title    轨道标题与显示名拼在一起，语言码缺失时靠它兜底（如「简体中文」「CHS」）
     * @param external 是否外挂字幕文件
     */
    public record SubtitleTrack(String language, String title, boolean external) {
    }

    /**
     * 同一集在库里有多个版本（或多台服务器各有一份）时合并成一份：任一份解析过就算解析过，流取并集。
     */
    public MediaStreamInfo merge(MediaStreamInfo other) {
        if (other == null) {
            return this;
        }
        List<String> audio = new ArrayList<>(audioLanguages);
        audio.addAll(other.audioLanguages);
        List<SubtitleTrack> subs = new ArrayList<>(subtitles);
        subs.addAll(other.subtitles);
        return new MediaStreamInfo(probed || other.probed, audio, subs);
    }
}
