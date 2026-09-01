package com.osr.openliststrm.rename.extractor.impl;

import com.osr.openliststrm.rename.extractor.Extractor;
import com.osr.openliststrm.rename.model.MediaInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SourceAndGroupExtractor implements Extractor {

    // 优化：加入常见流媒体简写 (TX, YOUKU, IQIYI, NF, AMZN) 以及 HFR 等
    private static final Pattern TAGS = Pattern.compile(
            "\\b(REMUX|ISO|ENCODED|PROPER|REPACK|Atmos|HDR10\\+|HDR10|HDR|10bit|12bit|60fps|HFR|DV|DoVi|IMAX|TX|YOUKU|IQIYI|NF|AMZN|HMAX|DSNP)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SOURCE = Pattern.compile("\\b(WEB-?DL|WEB-?Rip|Blu-?Ray|BRRip|HDRip|HDTV|BDRip|CAM|WEB|DVD|DVDRip)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * 标题结尾那一段：最后一个空白之后的全部内容。{@code MediaParser#normalize} 已经把
     * "点 + 字母数字" 形态的分隔点统一换成了空格，所以到这一步空白是唯一的段边界。
     * <p>
     * 曾经的实际 bug：旧正则 {@code (?:[-@]|\s@)\s*([A-Za-z0-9_.-]+)$} 是<b>从左找第一个</b>
     * 连字符当引导符，于是发布组名自带连字符时只截到最后一节——
     * {@code Gei a ma de qing shu 2026 1080p WEB-DL H.265 AAC lijiang-tv} 解析出的发布组是
     * {@code tv} 而不是 {@code lijiang-tv}（{@code WEB-DL} 已在上一步被 SOURCE 摘走，
     * 剩下的第一个连字符正好是组名内部那个）。后果是两条链路同时失效且都没有任何错误现象：
     * 发布组黑名单按全等匹配，拉黑 {@code lijiang-tv} 永远命不中，那个组的种子照下不误；
     * 重命名产出的文件名尾巴是 {@code -tv}，被截掉的 {@code lijiang} 还留在标题里。
     * 现在先切出整段、再判这一段是不是发布组。
     * </p>
     */
    private static final Pattern TRAILING_SEGMENT = Pattern.compile("(?:^|\\s)([A-Za-z0-9_\\.@-]+)$");

    /**
     * "AAC @ Group"：@ 与组名之间还隔着空格，组名本身单独成段，靠 {@link #TRAILING_SEGMENT}
     * 看不出它由 @ 引导（那一段既没有引导符也没有内部连字符，会被当成普通词放过），
     * 所以单开一条先试。紧贴的 "@Group"、"1080p@Group" 由 {@link #groupOf} 处理。
     */
    private static final Pattern GROUP_AT_SPACED = Pattern.compile("(?:^|\\s)(@)\\s+([A-Za-z0-9_\\.-]+)$");

    /**
     * "03"、"E03"、"S01E04" 这类纯集号/带 E 前缀/带季号前缀的集号不是发布组名，是季集区间的结尾
     * （如 S01E01-03、S01E01-E03、S01E01-S01E04——区间结尾把季号又重复了一遍）。
     * <p>
     * 判定对象是<b>整段</b>而不是连字符之后那一节，所以区间的两端都要写进正则。
     * </p>
     */
    private static final Pattern EPISODE_LIKE = Pattern.compile(
            "^(?:S\\d{1,2})?E?\\d{1,4}(?:-(?:S\\d{1,2})?E?\\d{1,4})?$", Pattern.CASE_INSENSITIVE);

    /**
     * 整段本身就是技术标识时不当发布组。上面几步通常已经把它们摘干净了（分辨率、编码、来源
     * 各有自己的 extractor），这里兜的是"摘不掉"的情况——{@code info.getSource()} 已被赋值时
     * SOURCE 分支不执行，{@code WEB-DL} 会原样留到结尾，而它内部正好有个连字符。
     */
    private static final Pattern NOT_GROUP = Pattern.compile(
            "^(?:WEB-?DL|WEB-?RIP|BLU-?RAY|DTS-?HD(?:-?MA)?|DTS-?X|DD-?EX|E-?AC-?3|MPEG-?2|HDR10-?\\+?|H-?26[45]|X-?26[45]|\\d+-?bit)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GROUP_BRACKET = Pattern.compile("^\\[([A-Za-z0-9_\\.-]+)\\]");

    @Override
    public String extract(String name, MediaInfo info) {
        // 1. Tags
        Matcher t = TAGS.matcher(name);
        StringBuilder cleanName = new StringBuilder(name);
        while (t.find()) {
            String tag = t.group(1).toUpperCase();
            if (tag.equals("DV") || tag.equals("DOVI")) tag = "Dolby Vision";

            // 记录标签
            info.getTags().add(tag);

            // 替换为空格
            for (int i = t.start(); i < t.end(); i++) cleanName.setCharAt(i, ' ');
        }
        name = cleanName.toString().replaceAll("\\s+", " ").trim();

        // 2. Source
        Matcher s = SOURCE.matcher(name);
        if (s.find() && info.getSource() == null) {
            String rawSource = s.group(1).toUpperCase();
            String normalizedSource = rawSource.replaceAll("[-\\.]", "");
            if (normalizedSource.equals("BLURAY")) normalizedSource = "BluRay";
            if (normalizedSource.equals("WEBRIP")) normalizedSource = "WEBRip";

            info.setSource(normalizedSource);
            name = (name.substring(0, s.start()) + " " + name.substring(s.end())).trim();
        }

        // 3. Group
        Matcher gb = GROUP_BRACKET.matcher(name);
        if (gb.find()) {
            String candidate = gb.group(1);
            if (!candidate.matches("(?i)4k|1080p|web-dl|web-rip|webrip")) {
                info.setReleaseGroup(candidate);
                name = name.substring(gb.end()).trim();
            }
        } else {
            Matcher at = GROUP_AT_SPACED.matcher(name);
            if (at.find()) {
                info.setReleaseGroup(at.group(2));
                name = name.substring(0, at.start(1)).trim();
            } else {
                Matcher ge = TRAILING_SEGMENT.matcher(name);
                if (ge.find()) {
                    String group = groupOf(ge.group(1));
                    if (group != null) {
                        info.setReleaseGroup(group);
                        // 截掉整段（含引导的连字符/@），而不是只截最后一节
                        name = name.substring(0, ge.start(1)).trim();
                    }
                }
            }
        }

        return name.trim();
    }

    /**
     * 判断标题结尾那一段是不是发布组名，是则返回组名，否则返回 {@code null}。
     * <p>
     * 判据仍是"这一段由 - 或 @ 引导"，沿用旧实现的保守取向：结尾是一个不带连字符的普通词
     * （{@code 2026}、{@code 2Audio}）时宁可不认——认错会把作品名的一部分当成发布组截掉，
     * 而那会同时污染标题匹配与重命名，比解析不出发布组糟得多。变的只是连字符出现在段
     * <b>内部</b>（{@code lijiang-tv}）时整段都算组名，不再只取最后一节。
     * </p>
     */
    private static String groupOf(String segment) {
        String candidate = segment;
        int at = candidate.lastIndexOf('@');
        if (at >= 0) {
            // "@Group"、"1080p@Group"：@ 一律是分隔符，组名是它后面的部分
            candidate = candidate.substring(at + 1).trim();
        } else if (candidate.startsWith("-")) {
            // "-CHDWEB"：连字符是分隔符，组名是它后面的部分
            candidate = candidate.substring(1).trim();
        } else if (candidate.indexOf('-') < 0) {
            // 既没有引导符、内部也没有连字符，没有任何证据表明这是发布组
            return null;
        }
        if (candidate.isEmpty()
                || EPISODE_LIKE.matcher(candidate).matches()
                || NOT_GROUP.matcher(candidate).matches()) {
            return null;
        }
        return candidate;
    }
}