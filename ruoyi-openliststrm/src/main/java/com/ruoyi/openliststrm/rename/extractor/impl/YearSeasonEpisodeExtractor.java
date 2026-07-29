package com.ruoyi.openliststrm.rename.extractor.impl;

import com.ruoyi.openliststrm.rename.extractor.Extractor;
import com.ruoyi.openliststrm.rename.model.MediaInfo;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 识别年份、季度、集数
 * 优化：增加对中文 "第x季", "第x集" 的支持
 */
public class YearSeasonEpisodeExtractor implements Extractor {
    // 年份：19xx 或 20xx
    private static final Pattern YEAR = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    // 1. 标准 S01E01 格式 (支持 S01E01-03、S01E01-E03 两种区间写法)
    // 区间分隔符要么是 "-E"/"-"，要么单独一个 "E"；不能用简单的字符类 [-eE] 一次只吃一个字符，
    // 否则 "-E03" 会因为 "-" 吃掉分隔符后紧跟的 "E" 不是数字而匹配失败（曾经的实际 bug）。
    private static final Pattern S_E = Pattern.compile("\\bS(\\d{1,2})\\s?E(\\d{1,4})(?:(?:-E|-|E)(\\d{1,4}))?\\b", Pattern.CASE_INSENSITIVE);

    // 2. 纯 S01 或 纯 EP01
    private static final Pattern S_ONLY = Pattern.compile("\\bS(\\d{1,2})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern E_ONLY = Pattern.compile("\\b(?:EP?|E)\\s?(\\d{1,4})\\b", Pattern.CASE_INSENSITIVE);

    // 3. 【新增】中文季集格式：支持 "第10季", "第 10 季", "第2集" 等
    // 解释：第\s* -> 匹配 "第" 字后可能有空格
    // (\d+) -> 提取数字
    // \s*[季部] -> 匹配 "季" 或 "部"
    private static final Pattern CHI_SEASON = Pattern.compile("第\\s*(\\d+)\\s*[季部]");
    private static final Pattern CHI_EPISODE = Pattern.compile("第\\s*(\\d+)\\s*[集话]");

    @Override
    public String extract(String name, MediaInfo info) {
        // --- 1. 提取年份 ---
        Matcher y = YEAR.matcher(name);
        int yearIndex = Integer.MAX_VALUE;
        if (y.find()) {
            yearIndex = y.start();
            info.setYear(y.group(1));
        }

        // --- 2. 优先尝试提取中文 "第x季" ---
        Matcher mcs = CHI_SEASON.matcher(name);
        int chiSeasonIndex = Integer.MAX_VALUE;
        if (mcs.find()) {
            chiSeasonIndex = mcs.start();
            info.setSeason(formatNumber(mcs.group(1)));
        }

        // --- 3. 优先尝试提取中文 "第x集" ---
        Matcher mce = CHI_EPISODE.matcher(name);
        int chiEpisodeIndex = Integer.MAX_VALUE;
        if (mce.find()) {
            chiEpisodeIndex = mce.start();
            info.setEpisode(formatNumber(mce.group(1)));
            // 如果只有集没有季，默认第一季
            if (info.getSeason() == null) {
                info.setSeason("01");
            }
        }

        // 如果中文匹配到了，就不用跑英文匹配了，直接返回截断后的字符串
        if (chiSeasonIndex != Integer.MAX_VALUE || chiEpisodeIndex != Integer.MAX_VALUE) {
            // 截断逻辑：取年份、中文季、中文集 最靠前的那个位置作为标题结束点
            int cutIndex = Math.min(yearIndex, Math.min(chiSeasonIndex, chiEpisodeIndex));
            if (cutIndex != Integer.MAX_VALUE) {
                return name.substring(0, cutIndex).trim();
            }
            return name; // Should not happen
        }

        // --- 4. 如果没有中文，继续标准的 SxxExx 流程 ---
        Matcher se = S_E.matcher(name);
        int seIndex = Integer.MAX_VALUE;
        if (se.find()) {
            seIndex = se.start();
            info.setSeason(formatNumber(se.group(1)));
            info.setEpisode(formatNumber(se.group(2)));
            // group(3) 是区间结尾集数 (如 S01E01-03 里的 "03")，仅当确实大于起始集数才算区间，
            // 防止把 "E01-1080p" 之类噪声误判为区间
            String endRaw = se.group(3);
            if (StringUtils.isNotBlank(endRaw)) {
                try {
                    if (Integer.parseInt(endRaw) > Integer.parseInt(se.group(2))) {
                        info.setEpisodeEnd(formatNumber(endRaw));
                    }
                } catch (NumberFormatException ignored) {
                    // 数字格式异常时不当作区间处理
                }
            }

            name = name.substring(0, Math.min(seIndex, yearIndex));
            return name.trim();
        }

        Matcher so = S_ONLY.matcher(name);
        int soIndex = Integer.MAX_VALUE;
        if (so.find() && info.getSeason() == null) {
            soIndex = so.start();
            info.setSeason(formatNumber(so.group(1)));
        }

        Matcher eo = E_ONLY.matcher(name);
        int eoIndex = Integer.MAX_VALUE;
        if (eo.find() && info.getEpisode() == null) {
            eoIndex = eo.start();
            if (StringUtils.isBlank(info.getSeason())) {
                info.setSeason("01");
            }
            info.setEpisode(formatNumber(eo.group(1)));
        }

        if (yearIndex == Integer.MAX_VALUE && soIndex == Integer.MAX_VALUE && eoIndex == Integer.MAX_VALUE) {
            return name.trim();
        }

        name = name.substring(0, Math.min(Math.min(yearIndex, soIndex), eoIndex));
        return name.trim();
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