package com.osr.openliststrm.pt.subscription;

import com.osr.common.utils.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从「解析出季号但没有集号」的种子标题里，保守地抽取集数区间。
 * <p>
 * 存在的理由：{@link SubscriptionMatcher} 把「有季无集」一律当整季包处理，而
 * {@code SubscriptionEngine#resolveTargets} 会给整季包占位<b>该订阅全部缺失集</b>。
 * 一旦种子实际只含其中一段（分成上/中/下、跨年续播的番剧极常见），包外的那些集
 * 会被标成在途却永远下不到——补搜与 RSS 都只认 MISSING，它们不会再被搜索，
 * 也没有活跃下载记录可供失败回滚，等于永久卡死。
 * </p>
 * <p>
 * <b>只认括号包裹与中文集数两种明确写法</b>，刻意不认裸的 {@code 01-26}：
 * 标题里的裸区间与年份区间（2019-2021）、分辨率（1920-1080）、促销时间无从分辨，
 * 误判的代价是把一个真整季包切成一小段、剩下的集反复空搜。判不出来就当整季包，
 * 由 {@code DownloadTrackService} 拿下载器的真实文件列表做事后对账兜底——
 * 那才是精确判据，本类只是把窗口期缩小到「从推送到元数据解析完成」这一段。
 * </p>
 * <p>
 * {@code S01E01-26}、{@code EP01-26} 这类写法不在这里处理：
 * {@code YearSeasonEpisodeExtractor} 已经能解析出 episode/episodeEnd，
 * 走到本类时 parsedEpisode 必然非 null。
 * </p>
 *
 * @author Jack
 */
public final class SeasonPackRange {

    /**
     * 括号包裹的区间：{@code [01-26]}、{@code 【01-26】}、{@code [01-26END]}、{@code [01-13Fin]}、{@code [01-26完]}。
     * 集号限 1-3 位，天然排除四位年份被当成集号（{@code [2019-2021]} 不会命中）。
     */
    private static final Pattern BRACKET_RANGE = Pattern.compile(
            "[\\[【(（]\\s*(\\d{1,3})\\s*[-~～－]\\s*(\\d{1,3})\\s*(?:END|FIN|完|全|话|話|集)?\\s*[\\]】)）]",
            Pattern.CASE_INSENSITIVE);

    /** 中文集数区间：{@code 第01-26话}、{@code 第1-26集}、{@code 第01~13話} */
    private static final Pattern CHINESE_RANGE = Pattern.compile(
            "第\\s*(\\d{1,3})\\s*[-~～－]\\s*(\\d{1,3})\\s*[集话話]");

    private SeasonPackRange() {
    }

    /**
     * 解析出的集数区间，{@code start < end} 恒成立。
     */
    public record Range(int start, int end) {
    }

    /**
     * @param title 种子原始标题
     * @return 解析出的区间；判不出来（或写法不在白名单内）返回 {@code null}，调用方按整季包处理
     */
    public static Range parse(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        Range range = firstMatch(BRACKET_RANGE, title);
        return range != null ? range : firstMatch(CHINESE_RANGE, title);
    }

    /**
     * 取第一个<b>通过校验</b>的匹配，而不是第一个匹配就返回。
     * 标题里可能先出现一个不合法的区间（如 {@code [1-1]}），后面才是真正的集数区间。
     */
    private static Range firstMatch(Pattern pattern, String title) {
        Matcher matcher = pattern.matcher(title);
        while (matcher.find()) {
            Integer start = toInt(matcher.group(1));
            Integer end = toInt(matcher.group(2));
            // end 必须严格大于 start 才算区间：[01-01] 这种既不是区间也没有信息量。
            // 不额外限制区间长度——一部 100 集的长番的季包就是这么长的
            if (start != null && end != null && end > start) {
                return new Range(start, end);
            }
        }
        return null;
    }

    private static Integer toInt(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            // 正则已经限定了 1-3 位数字，走不到这里；纯防御
            return null;
        }
    }
}
