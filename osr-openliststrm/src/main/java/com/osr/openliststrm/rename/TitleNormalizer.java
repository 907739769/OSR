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

    private TitleNormalizer() {
    }

    /**
     * 转小写 → 把标点与空白（含全角）压成单个空格 → 去首尾空白。
     *
     * @param title 原始标题，允许为 null/空白
     * @return 归一化结果；入参为空或归一化后为空时返回 {@code null}，便于调用方直接丢弃
     */
    public static String normalizeForCompare(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        String normalized = NOISE.matcher(title.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
