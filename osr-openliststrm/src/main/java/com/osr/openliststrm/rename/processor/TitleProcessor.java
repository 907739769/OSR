package com.osr.openliststrm.rename.processor;

import com.osr.openliststrm.rename.SeasonSuffix;
import com.osr.openliststrm.rename.model.MediaInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @Author Jack
 * @Date 2025/8/12 16:52
 * @Version 1.0.0
 */
public class TitleProcessor {

    /** 方括号/中括号包裹的片段：站点标签、发布组、质量标注、部分中文站的作品名都是这个形态 */
    private static final Pattern BRACKET = Pattern.compile("[\\[【]([^\\]】]+)[\\]】]");

    /** 片段里是否真的含中文 */
    private static final Pattern HAS_CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 连续中文块（含中点分隔符） */
    private static final Pattern CHINESE_RUN = Pattern.compile("([\\u4e00-\\u9fa5·．]+)");


    /**
     * 从剩余字符串中提取标题，优先保留中文（中括号内或连续中文），
     * 同时解析英文点分割的标题（One.Hundred.Thousand... → One Hundred Thousand ...）
     */
    public void processTitle(String remaining, MediaInfo info) {
        if (remaining == null) remaining = "";
        String s = remaining.trim();

        // 括号内必须<b>真的含中文</b>才当作品名。原实现只要求括号内非空，于是
        // [Nekomoe kissaten]、[FRDS] 这类发布组/站点标签会被当成中文标题，真正的作品名
        // 反而被挤进 englishTitle——PT 订阅侧标题匹配不上（漏搜），重命名侧则会拿着
        // 发布组名去 TMDb 搜索，搜到什么就按什么命名，且因为 tmdbId 非空连 AI 兜底都不会触发。
        // 取第一个含中文的括号；一个都没有时不消费任何括号，交给下面的连续中文块兜底。
        Matcher bracket = BRACKET.matcher(s);
        boolean fromBracket = false;
        while (bracket.find()) {
            String inner = bracket.group(1).trim();
            if (HAS_CHINESE.matcher(inner).find()) {
                info.setOriginalTitle(inner);
                s = removeRange(s, bracket.start(), bracket.end());
                fromBracket = true;
                break;
            }
        }
        if (!fromBracket) {
            Matcher chinese = CHINESE_RUN.matcher(s);
            if (chinese.find()) {
                info.setOriginalTitle(chinese.group(1).trim());
                s = removeRange(s, chinese.start(), chinese.end());
            }
        }

        String eng = s.replaceAll("[._]+", " ").trim();

        if (info.getOriginalTitle() == null && !eng.isEmpty()) {
            info.setOriginalTitle(eng);
        } else if (info.getOriginalTitle() != null && !eng.isEmpty()) {
            info.setEnglishTitle(eng);
        }

        // 季号后缀对「这是哪部作品」没有任何贡献，却足以让 TMDb 查询整个落空（见 SeasonSuffix 的类注释）。
        // PT 订阅侧同样受益：SubscriptionEngine 用 originalTitle 当种子的 parsedTitle 去比对订阅标题，
        // 而订阅存的是不带季号的作品名。
        info.setOriginalTitle(SeasonSuffix.strip(info.getOriginalTitle()));
        info.setEnglishTitle(SeasonSuffix.strip(info.getEnglishTitle()));

//        if (info.getTitle() == null) {
//            info.setTitle(info.getOriginalTitle());
//        }
    }

    /**
     * 剥掉标题尾部的季号后缀。剥完为空时返回原值——宁可多带三个字，也不能交出空标题。
     *
     * @param title 允许为 null
     */

    private String removeRange(String s, int a, int b) {
        return (s.substring(0, a) + s.substring(b)).trim();
    }
}
