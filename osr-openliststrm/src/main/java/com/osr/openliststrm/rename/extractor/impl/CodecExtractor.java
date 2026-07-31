package com.osr.openliststrm.rename.extractor.impl;

import com.osr.openliststrm.rename.extractor.Extractor;
import com.osr.openliststrm.rename.model.MediaInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CodecExtractor implements Extractor {
    // 视频编码：支持 x264, x265, h.264, h.265, h 265, hevc, av1, mpeg2 等
    // h[ .]?26[45] 匹配 h264, h.264, h 264, h265, h.265, h 265
    private static final Pattern VIDEO = Pattern.compile("\\b(x26[45]|h[ .]?26[45]|hevc|avc|av1|vp[89]|mpeg-?2)\\b", Pattern.CASE_INSENSITIVE);

    // 音频编码：支持 aac, ac3, ddp, dts 等，并支持可选的声道后缀 (如 2.0, 5.1, 7.1)
    // (?:[ .]?\d[ .]\d)? 匹配可选的 "2.0" " 5.1" "2 0" 等格式
    private static final Pattern AUDIO = Pattern.compile("\\b((?:aac|ddp|dd\\+|ac3|dts-?hd|dts-?x|dts|truehd|atmos|opus|vorbis|flac|pcm|mp3|eac3)(?:[ .]?\\d[ .]\\d)?)\\b", Pattern.CASE_INSENSITIVE);

    @Override
    public String extract(String name, MediaInfo info) {
        // 提取视频编码
        Matcher mv = VIDEO.matcher(name);
        if (mv.find() && info.getVideoCodec() == null) {
            info.setVideoCodec(normalizeVideo(mv.group(1)));
            // 从文件名中移除识别到的编码
            name = name.substring(0, mv.start()) + " " + name.substring(mv.end());
        }

        // 提取音频编码
        Matcher ma = AUDIO.matcher(name);
        if (ma.find() && info.getAudioCodec() == null) {
            info.setAudioCodec(normalizeAudio(ma.group(1)));
            name = name.substring(0, ma.start()) + " " + name.substring(ma.end());
        }

        return name.replaceAll("\\s+", " ").trim();
    }

    private String normalizeVideo(String raw) {
        String r = raw.toLowerCase().replace(".", "").replace(" ", "");
        if (r.contains("264") || r.contains("avc")) return "H264";
        if (r.contains("265") || r.contains("hevc")) return "H265";
        return r.toUpperCase();
    }

    private String normalizeAudio(String raw) {
        String r = raw.toLowerCase().replace("-", "").replace(" ", "").replace(".", "");
        // 移除末尾的数字（声道），只保留编码名称
        r = r.replaceAll("\\d+$", "");

        if (r.contains("ddp") || r.contains("dd+") || r.equals("eac3")) return "EAC3";
        if (r.contains("truehd")) return "TrueHD";
        if (r.contains("atmos")) return "Atmos";
        if (r.contains("dtshd")) return "DTS-HD";
        if (r.equals("ac3") || r.equals("dd")) return "AC3";
        return r.toUpperCase(); // 如 AAC
    }
}