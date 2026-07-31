package com.osr.openliststrm.rename.processor;

import com.osr.openliststrm.rename.model.MediaInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author Jack
 * @Date 2025/8/12 16:52
 * @Version 1.0.0
 */
public class TitleProcessor {

    /**
     * 从剩余字符串中提取标题，优先保留中文（中括号内或连续中文），
     * 同时解析英文点分割的标题（One.Hundred.Thousand... → One Hundred Thousand ...）
     */
    public void processTitle(String remaining, MediaInfo info) {
        if (remaining == null) remaining = "";
        String s = remaining.trim();

        Pattern bracketChinese = Pattern.compile("[\\[【]([^\\]】]+)[\\]】]");
        Matcher m = bracketChinese.matcher(s);
        if (m.find()) {
            String zh = m.group(1).trim();
            info.setOriginalTitle(zh);
            s = removeRange(s, m.start(), m.end());
        } else {
            Pattern chinese = Pattern.compile("([\\u4e00-\\u9fa5·．]+)");
            m = chinese.matcher(s);
            if (m.find()) {
                info.setOriginalTitle(m.group(1).trim());
                s = removeRange(s, m.start(), m.end());
            }
        }

        String eng = s.replaceAll("[._]+", " ").trim();

        if (info.getOriginalTitle() == null && !eng.isEmpty()) {
            info.setOriginalTitle(eng);
        } else if (info.getOriginalTitle() != null && !eng.isEmpty()) {
            info.setEnglishTitle(eng);
        }

//        if (info.getTitle() == null) {
//            info.setTitle(info.getOriginalTitle());
//        }
    }

    private String removeRange(String s, int a, int b) {
        return (s.substring(0, a) + s.substring(b)).trim();
    }
}