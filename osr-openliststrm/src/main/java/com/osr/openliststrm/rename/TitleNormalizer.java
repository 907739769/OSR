package com.osr.openliststrm.rename;

import com.osr.common.utils.StringUtils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 作品标题的<b>比较用</b>归一化。纯函数，无 IO，无 Spring 依赖。
 * <p>
 * 只用于「这两个标题是不是同一部作品」的判定，<b>绝不参与任何输出</b>——重命名产出的名字、
 * 通知里展示的标题、落库的字段一律用原始值。
 * </p>
 * <p>
 * <b>为什么独立成类：</b>判定标题相等这件事在系统里有两个入口——
 * PT 订阅匹配（{@code SubscriptionMatcher#normalizeAll}，拿种子标题比订阅标题）与
 * TMDb 刮削（{@code TMDbClient#titleMatchLevel}，拿解析标题比 TMDb 条目名）。
 * 两边各写一份的下场已经出现过：TMDb 侧剥掉了标点、PT 侧只处理 {@code . _ -}，
 * 于是《神探夏洛克：可恶的新娘》的种子在刮削侧能匹配、在订阅匹配侧却因为一个全角冒号被漏掉，
 * 同一部作品两条链路给出相反结论。收口到这里之后，字符类只有一份，不可能再分叉。
 * </p>
 * <p>
 * <b>标点替换成空格而不是删除</b>：删除会让 {@code M*A*S*H} 塌成 {@code mash} 从而误撞另一部
 * 叫 MASH 的作品；替换成空格得到 {@code m a s h}，两者仍然可分。调用方做全等比较时，
 * 这一点决定了「宽松到能吃下写法差异」与「宽松到开始串台」之间的边界。
 * </p>
 *
 * @author Jack
 */
public final class TitleNormalizer {

    /**
     * 比较时要抹掉的噪声字符。
     * <ul>
     *   <li>{@code \p{Punct}}：ASCII 标点，额外覆盖 {@code ~ + = < > | $ ^ `} 这些不属于 Unicode
     *       Punctuation 类别（它们是 Symbol）的字符</li>
     *   <li>{@code \p{IsPunctuation}}：Unicode 标点，覆盖中日韩全角标点（{@code ：、。！？「」（）【】《》}）、
     *       各类破折号与引号、{@code · ・ …}，以及连接符 {@code _}</li>
     *   <li>{@code \p{IsWhite_Space}}：含全角空格 U+3000——Java 的 {@code \s} 不认它，
     *       而日剧/韩剧标题里全角空格极常见</li>
     *   <li>{@code ～}（～ 全角波浪号）与 {@code 〜}（〜 波浪线）：Unicode 里归为 Symbol
     *       而非 Punctuation，必须显式列出</li>
     * </ul>
     */
    private static final Pattern NOISE = Pattern.compile(
            "[\\p{Punct}\\p{IsPunctuation}\\p{IsWhite_Space}\\uFF5E\\u301C]+");

    /**
     * {@code &} 与单词 {@code and} 是同一个意思，比较前统一写成 {@code and}。
     * <p>
     * 不能只当普通标点抹掉：{@code All Creatures Great & Small} 抹完是 {@code great small}，
     * 与文件名里的 {@code Great and Small} 既不全等也不互相包含。事故：
     * {@code All.Creatures.Great.and.Small.2020.S07E01} 被刮成 1978 年老版——老版原名逐字是
     * {@code ... and Small}（全等档），2020 版原名写的是 {@code &}（0 分），年份吻合的 +40
     * 跨不过全等分档，老版稳赢。{@code ＆}（U+FF06 全角）同理。
     * </p>
     */
    private static final Pattern AMPERSAND = Pattern.compile("[&\uFF06]");

    /**
     * \u5939\u5728\u4E24\u4E2A\u5B57\u6BCD\u4E4B\u95F4\u7684\u6487\u53F7\u76F4\u63A5\u5220\u6389\uFF0C\u800C<b>\u4E0D\u662F</b>\u50CF\u5176\u5B83\u6807\u70B9\u90A3\u6837\u6362\u6210\u7A7A\u683C\u3002
     * <p>
     * \u6487\u53F7\u662F\u6807\u70B9\u91CC\u552F\u4E00\u300C\u5199\u4E0D\u5199\u90FD\u662F\u540C\u4E00\u4E2A\u8BCD\u300D\u7684\uFF1A\u53D1\u5E03\u7EC4\u547D\u540D\u901A\u5E38\u76F4\u63A5\u7701\u6389\u5B83\u3002\u4E8B\u6545\uFF1A
     * {@code JoJos Bizarre Adventure S06E02 \u2026} \u5F52\u4E00\u5316\u6210 {@code jojos bizarre adventure}\uFF0C
     * \u8BA2\u9605\u82F1\u6587\u540D {@code JoJo's Bizarre Adventure} \u5374\u6210\u4E86 {@code jojo s bizarre adventure}\uFF0C
     * PT \u4FA7\u53EA\u8BA4\u5168\u7B49\uFF0C\u7AD9\u4E0A\u660E\u660E\u6709\u8D44\u6E90\u3001RSS \u6BCF\u8F6E\u90FD\u62C9\u5230\u4E86\uFF0C\u5C31\u662F\u4E00\u76F4\u300C\u672A\u5339\u914D\u5230\u4EFB\u4F55\u8BA2\u9605\u300D\u3002
     * \u53EA\u5220\u4E24\u4FA7\u90FD\u662F\u5B57\u6BCD\u7684\uFF1A{@code Rock 'n' Roll} \u8FD9\u79CD\u5F15\u53F7\u7528\u6CD5\u4ECD\u6309\u666E\u901A\u6807\u70B9\u5904\u7406\u3002
     * \u8986\u76D6\u76F4\u6487\u53F7\u3001\u5F2F\u6487\u53F7\uFF08{@code \u2019 \u2018}\uFF09\u4E0E\u4FEE\u9970\u5B57\u6BCD\u6487\u53F7\uFF08U+02BC\uFF09\u3002
     * </p>
     */
    private static final Pattern APOSTROPHE = Pattern.compile("(?<=\\p{L})['\u2019\u2018\u02BC](?=\\p{L})");

    /**
     * 撇号被写成分隔符的所有格（{@code Marvel.s.Daredevil}）在抹完标点后是 {@code marvel s daredevil}，
     * 把这个孤立的 {@code s} 并回前一个词，与 {@code Marvel's} / {@code Marvels} 归到同一个结果。
     * 前一个词至少两个拉丁字母：{@code M*A*S*H} 抹完是 {@code m a s h}，那里的 {@code s} 不是所有格，
     * 并掉会让它塌向另一部作品（见类注释）。
     */
    private static final Pattern DETACHED_POSSESSIVE = Pattern.compile("(?<=\\p{IsLatin}{2}) s(?= |$)");

    private TitleNormalizer() {
    }

    /**
     * 转小写 → {@code &} 写成 {@code and} → 删掉词内撇号 → 把标点与空白（含全角）压成单个空格 → 去首尾空白
     * → 孤立的所有格 {@code s} 并回前一个词。
     *
     * @param title 原始标题，允许为 null/空白
     * @return 归一化结果；入参为空或归一化后为空时返回 {@code null}，便于调用方直接丢弃
     */
    public static String normalizeForCompare(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        String lower = AMPERSAND.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" and ");
        lower = APOSTROPHE.matcher(lower).replaceAll("");
        String normalized = NOISE.matcher(lower).replaceAll(" ").trim();
        normalized = DETACHED_POSSESSIVE.matcher(normalized).replaceAll("s");
        return normalized.isEmpty() ? null : normalized;
    }
}
