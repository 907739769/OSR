package com.osr.openliststrm.rename.extractor.impl;

import com.osr.openliststrm.rename.extractor.Extractor;
import com.osr.openliststrm.rename.model.MediaInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ResolutionExtractor implements Extractor {
    // 增加对 3840x2160 这种 "数字x数字" 格式的支持
    // 同时也支持 8k, 4k, 2k 等简写
    private static final Pattern P = Pattern.compile(
            "\\b(8k|4320p|4k|2160p|1440p|2k|1080p|1080i|720p|480p|576p|360p)\\b|\\b(\\d{3,4})[xX*](\\d{3,4})\\b",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String extract(String name, MediaInfo info) {
        Matcher m = P.matcher(name);
        if (m.find()) {
            // Group 2 和 3 是 宽x高 格式 (例如 3840x2160)
            if (m.group(2) != null && m.group(3) != null) {
                // 通常我们用高度来表示分辨率，例如 2160
                String height = m.group(3);
                info.setResolution(normalizeResolution(height + "p"));
            } else {
                // Group 1 是标准格式 (例如 4k)
                info.setResolution(normalizeResolution(m.group(1)));
            }
            // 将识别到的分辨率从文件名中移除
            name = (name.substring(0, m.start()) + " " + name.substring(m.end())).trim();
        }
        return name;
    }

    private String normalizeResolution(String raw) {
        if (raw == null) return null;
        String r = raw.toLowerCase();
        if (r.equals("4k") || r.contains("3840") || r.equals("2160p")) return "2160p";
        if (r.equals("8k") || r.contains("4320")) return "4320p";
        if (r.equals("2k") || r.contains("1440")) return "1440p";
        return r;
    }
}