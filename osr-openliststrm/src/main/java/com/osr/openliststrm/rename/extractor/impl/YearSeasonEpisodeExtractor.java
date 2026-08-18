package com.osr.openliststrm.rename.extractor.impl;

import com.osr.openliststrm.rename.extractor.Extractor;
import com.osr.openliststrm.rename.model.MediaInfo;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 识别年份、季度、集数
 * 优化：增加对中文 "第x季", "第x集" 的支持
 * <p>
 * <b>中文季/集与 SxxExx 是互补关系，不是二选一</b>：两种写法在同一个文件名里并存很常见
 * （{@code [某剧 第4季].Foo.2026.S04E10}），谁都可能只给出其中一半。解析顺序与优先级见
 * {@link #extract}。
 * </p>
 */
public class YearSeasonEpisodeExtractor implements Extractor {
    // 年份：19xx 或 20xx
    private static final Pattern YEAR = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    // 1. 标准 S01E01 格式 (支持 S01E01-03、S01E01-E03、S01E01-S01E04 区间写法，以及 S01E01E02E03 多集拼接写法)
    // 区间分隔符要么是 "-S01E"（季号在区间结尾重复一遍，如 "Furious 2026 S01E01-S01E04"）、
    // 要么 "-E"/"-"，要么单独一个 "E"；"-S\d{1,2}E" 必须排在最前面——alternation 按顺序尝试，
    // 排在后面的话 "-" 分支会先吃掉连字符，剩下的 "S01E04" 因为不是纯数字而让本次重复匹配失败
    // （链条其余部分因此被漏掉，只剩起始集号）。也不能用简单的字符类 [-eE] 一次只吃一个字符，
    // 否则 "-E03" 会因为 "-" 吃掉分隔符后紧跟的 "E" 不是数字而匹配失败（曾经的实际 bug）。
    // group(3) 用 (?:...)*（非捕获组重复）只保留最后一次匹配的子串，因此后续用 CHAIN_NUM 二次扫描
    // 整个链条取出所有集号，而不是依赖 group(3) 单值（否则 E01E02E03 会丢失中间的 E02）。
    private static final Pattern S_E = Pattern.compile("\\bS(\\d{1,2})\\s?E(\\d{1,4})((?:(?:-S\\d{1,2}E|-E|-|E)\\d{1,4})*)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHAIN_NUM = Pattern.compile("(?:-S\\d{1,2}E|-E|-|E)(\\d{1,4})", Pattern.CASE_INSENSITIVE);

    // 2. 纯 S01 或 纯 EP01 (EP01 同样支持 -03/-E03/E02E03 等区间与多集拼接写法)
    private static final Pattern S_ONLY = Pattern.compile("\\bS(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_ONLY = Pattern.compile("\\b(?:EP?|E)\\s?(\\d{1,4})((?:(?:-E|-|E)\\d{1,4})*)\\b", Pattern.CASE_INSENSITIVE);

    // 3. 【新增】中文季集格式：支持 "第10季", "第 10 季", "第2集" 等
    // 解释：第\s* -> 匹配 "第" 字后可能有空格
    // (\d+) -> 提取数字
    // \s*[季部] -> 匹配 "季" 或 "部"
    private static final Pattern CHI_SEASON = Pattern.compile("第\\s*(\\d+)\\s*[季部]");
    private static final Pattern CHI_EPISODE = Pattern.compile("第\\s*(\\d+)\\s*[集话]");

    /**
     * 成对的方括号。只用来判断「某个位置是不是落在括号内部」，见 {@link #insideBracket}。
     */
    private static final Pattern BRACKET_PAIR = Pattern.compile("[\\[【][^\\]】]*[\\]】]");

    /** 没有匹配到时的位置哨兵，参与 {@code min} 求最靠前的截断点 */
    private static final int NO_MATCH = Integer.MAX_VALUE;

    @Override
    public String extract(String name, MediaInfo info) {
        // --- 1. 提取年份 ---
        // 取<b>最后一个</b>匹配而不是第一个：片名本身就是四位年份的作品（《1917》《2012》
        // 《1984》《2046》）在 "1917.2019.1080p..." 这类种子名里会让第一个匹配落在片名上，
        // 于是年份取成 1917、标题又被按它的位置截成空串，PT 侧年份校验必挂、重命名侧
        // 拿空标题去 TMDb 搜索。发行年总是排在片名之后，取最后一个才是它。
        // 分辨率（1080p/1920x1080）此前已被 ResolutionExtractor 摘除，不会混进来。
        Matcher y = YEAR.matcher(name);
        int yearIndex = NO_MATCH;
        while (y.find()) {
            yearIndex = y.start();
            info.setYear(y.group(1));
        }
        // 年份就是片名本身、且种子名里没有另一个发行年（"2012.1080p.BluRay"）时，
        // 按年份位置截断会得到空标题。这种情况下年份值照留，但不参与标题截断——
        // 宁可让标题带上这四位数字，也不能交出一个空标题。
        if (yearIndex != NO_MATCH && name.substring(0, yearIndex).trim().isEmpty()) {
            yearIndex = NO_MATCH;
        }

        // --- 2. 中文「第x季」「第x集」---
        // 值先存局部变量，最后才补进 info：SxxExx 是发布组的规范字段，两者都给出时以它为准。
        String chiSeason = null;
        int chiSeasonIndex = NO_MATCH;
        Matcher mcs = CHI_SEASON.matcher(name);
        if (mcs.find()) {
            chiSeason = formatNumber(mcs.group(1));
            chiSeasonIndex = cutPointOf(name, mcs.start());
        }

        String chiEpisode = null;
        int chiEpisodeIndex = NO_MATCH;
        Matcher mce = CHI_EPISODE.matcher(name);
        if (mce.find()) {
            chiEpisode = formatNumber(mce.group(1));
            chiEpisodeIndex = cutPointOf(name, mce.start());
        }

        // --- 3. 标准 SxxExx。中文季/集命中后<b>仍然要跑这一段</b> ---
        // 早先这里是「中文匹配到了就直接返回」，于是 "[某剧 第4季].Foo.2026.S04E10" 的集号
        // 根本不会被解析（season=04 而 episode=null），下游表现为「重命名后没有集号、
        // 刮削也对不上单集」。中文数字写法（第四季）因为正则只认 \d 反而躲过了这条早退。
        int seIndex = NO_MATCH;
        int soIndex = NO_MATCH;
        int eoIndex = NO_MATCH;
        Matcher se = S_E.matcher(name);
        if (se.find()) {
            seIndex = se.start();
            info.setSeason(formatNumber(se.group(1)));
            info.setEpisode(formatNumber(se.group(2)));
            info.setEpisodeEnd(resolveEpisodeEnd(se.group(2), se.group(3)));
        } else {
            Matcher so = S_ONLY.matcher(name);
            if (so.find()) {
                soIndex = so.start();
                info.setSeason(formatNumber(so.group(1)));
            }
            Matcher eo = E_ONLY.matcher(name);
            if (eo.find()) {
                eoIndex = eo.start();
                info.setEpisode(formatNumber(eo.group(1)));
                info.setEpisodeEnd(resolveEpisodeEnd(eo.group(1), eo.group(2)));
            }
        }

        // --- 4. 中文季/集补位。只填 SxxExx 没给出的字段，不覆盖 ---
        if (info.getSeason() == null && chiSeason != null) {
            info.setSeason(chiSeason);
        }
        if (info.getEpisode() == null && chiEpisode != null) {
            info.setEpisode(chiEpisode);
        }
        // 有集无季一律按第一季（"第10集"、"EP10" 这类命名）
        if (info.getEpisode() != null && StringUtils.isBlank(info.getSeason())) {
            info.setSeason("01");
        }

        // --- 5. 标题截断点：所有标记里最靠前的那个 ---
        int cutIndex = Math.min(Math.min(Math.min(yearIndex, chiSeasonIndex), Math.min(chiEpisodeIndex, seIndex)),
                Math.min(soIndex, eoIndex));
        return cutIndex == NO_MATCH ? name.trim() : name.substring(0, cutIndex).trim();
    }

    /**
     * 把一个匹配位置换算成可用的截断点：落在方括号<b>内部</b>的标记不参与截断，返回 {@link #NO_MATCH}。
     * <p>
     * 部分中文站把「剧名 第X季」整个写进方括号（{@code [某剧 第4季].Foo.2026.S04E10}），
     * 按「第4季」的位置截断会把作品名从中间切开、连方括号都只剩半个：
     * {@code TitleProcessor} 的括号正则要求成对才认，于是作品名整个漏给 englishTitle，
     * 拿去 TMDb 搜索的就成了一个残缺串。
     * </p>
     * <p>
     * 不截也不会把季号留在标题里——{@code TitleProcessor.SEASON_SUFFIX} 会剥掉标题尾部的
     * 「第X季」，两者是同一件事的两半：这里负责不切碎，那里负责去噪。
     * </p>
     */
    private int cutPointOf(String name, int index) {
        return insideBracket(name, index) ? NO_MATCH : index;
    }

    /** 位置是否落在某对方括号的内部（正好是左括号本身不算） */
    private boolean insideBracket(String name, int index) {
        Matcher m = BRACKET_PAIR.matcher(name);
        while (m.find()) {
            if (index > m.start() && index < m.end()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从起始集号 + 尾部区间/多集拼接链条（如 "-03"、"-E03"、"E02E03"）中解析出区间结尾集号。
     * 链条里可能有多段（多集拼接），取其中的最大值作为结尾；
     * 仅当结尾确实大于起始集号才当作区间处理，防止 "E01-1080p" 之类噪声被误判。
     */
    private String resolveEpisodeEnd(String startRaw, String chain) {
        if (StringUtils.isBlank(chain)) {
            return null;
        }
        int start;
        try {
            start = Integer.parseInt(startRaw);
        } catch (NumberFormatException e) {
            return null;
        }
        int max = start;
        Matcher cm = CHAIN_NUM.matcher(chain);
        while (cm.find()) {
            try {
                int v = Integer.parseInt(cm.group(1));
                if (v > max) {
                    max = v;
                }
            } catch (NumberFormatException ignored) {
                // 数字格式异常的分段直接跳过
            }
        }
        return max > start ? formatNumber(String.valueOf(max)) : null;
    }

    private String formatNumber(String num) {
        if (num == null) return null;
        try {
            return String.format("%02d", Integer.parseInt(num));
        } catch (NumberFormatException e) {
            return num;
        }
    }
}