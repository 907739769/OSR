package com.osr.openliststrm.rename;

import com.osr.common.utils.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作品标题<b>尾部</b>季号后缀的剥离与解析。纯函数，无 IO，无 Spring 依赖。
 * <p>
 * <b>为什么必须剥掉</b>：TMDb 的条目名里从来不带季号——作品叫「瑞克和莫蒂」，季是它下面的一层。
 * 拿「瑞克和莫蒂 第九季」去 TMDb 搜索，多出的三个字会让整次查询落空，于是最强的信号
 * （中文作品名）作废。这个坑在两条链路上各踩过一次：
 * <ul>
 *   <li><b>刮削侧</b>：{@code [梦魇绝镇 第四季].From.2026.S04E10} 搜不到结果，退到英文名
 *       {@code From}——一个四字母常用词——撞上《怪奇物语：1985故事集》（原名含 "From"、
 *       首播年恰好也是 2026），整季被刮成另一部剧。</li>
 *   <li><b>热门自动订阅的豆瓣源</b>：榜单里的「瑞克和莫蒂 第九季」「百年孤独 第二季」
 *       整批记成「未匹配到 TMDb」，而续季与长寿剧在榜单上相当常见。</li>
 * </ul>
 * <p>
 * <b>为什么独立成类</b>：与 {@link TitleNormalizer} 完全同源的理由——正则原先是
 * {@code TitleProcessor} 的私有字段，豆瓣解析器要用只能复制一份，而复制出来的两份迟早漂移，
 * 漂移的表现是「同一个标题在刮削侧剥掉了、在订阅侧没剥」，从日志里根本看不出来。
 * </p>
 * <p>
 * <b>为什么不在 {@code YearSeasonEpisodeExtractor} 里给中文季正则加中文数字</b>：那条分支
 * 命中后会 early return 并按「第四季」的位置截断标题，{@code S04E10} 根本不会被解析，
 * 集号直接丢失。剥后缀只影响标题，不动抽取管线。
 * </p>
 *
 * @author Jack
 */
public final class SeasonSuffix {

    /** 标题尾部的季号后缀，允许连写多个（「第一季 Season 1」这种双写在豆瓣条目里出现过） */
    private static final Pattern SUFFIX = Pattern.compile(
            "(?:\\s*(?:第\\s*[0-9０-９〇零一二两三四五六七八九十百]+\\s*[季部]|[Ss]eason\\s*\\d{1,2}))+\\s*$");

    /** 后缀段里的<b>单个</b>季号，用于取出数字。两个捕获组分别对应中文写法与 Season 写法 */
    private static final Pattern SINGLE = Pattern.compile(
            "第\\s*([0-9０-９〇零一二两三四五六七八九十百]+)\\s*[季部]|[Ss]eason\\s*(\\d{1,2})");

    /**
     * 季号的合理上界。超出一律当作解析错误返回 null——「第一百季」这种不存在，
     * 真出现了多半是把别的东西误读成了季号，而<b>猜错季号比不给季号糟得多</b>：
     * 前者会订到一季根本不存在的内容并静静地一集都补不到。
     */
    private static final int MAX_SEASON = 99;

    private SeasonSuffix() {
    }

    /**
     * 剥掉尾部季号后缀。
     * <p>
     * <b>剥空则不剥</b>：《第五季》这类以季号为名的作品（比利时片 {@code La cinquième saison}）
     * 整个标题就是这几个字，剥掉会交出一个空标题。
     * </p>
     *
     * @return 剥掉后缀的标题；入参为 null 时返回 null，剥空时返回原值
     */
    public static String strip(String title) {
        if (title == null) {
            return null;
        }
        String stripped = SUFFIX.matcher(title).replaceAll("").trim();
        return stripped.isEmpty() ? title : stripped;
    }

    /**
     * 解析尾部季号后缀里的季号。
     * <p>
     * 连写多个时取<b>最后一个</b>（最靠近结尾的那个）：「进击的巨人 第四季 Season 4」两处说的
     * 是同一件事，取哪个都一样；真不一致时靠后的那个更可能是发布方补的规范写法。
     * </p>
     * <p>
     * <b>与 {@link #strip} 的判据保持一致</b>：strip 会剥空的标题（《第五季》），这里同样返回
     * null——那是作品名而不是季号，把它读成「第 5 季」会去订一部叫《第五季》的电影的第 5 季。
     * </p>
     *
     * @return 季号（1~99）；没有后缀、解析不出或超出合理范围时返回 null
     */
    public static Integer parse(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        Matcher suffix = SUFFIX.matcher(title);
        if (!suffix.find()) {
            return null;
        }
        // 整个标题就是季号时不解析，理由同 strip 的「剥空则不剥」
        if (title.substring(0, suffix.start()).trim().isEmpty()) {
            return null;
        }
        Integer last = null;
        Matcher one = SINGLE.matcher(title.substring(suffix.start()));
        while (one.find()) {
            Integer value = one.group(1) != null ? parseNumber(one.group(1)) : parseNumber(one.group(2));
            if (value != null) {
                last = value;
            }
        }
        return last;
    }

    /** 解析阿拉伯数字（含全角）或中文数字；超出合理范围返回 null */
    private static Integer parseNumber(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        String normalized = toHalfWidthDigits(text.trim());
        Integer value = normalized.chars().allMatch(Character::isDigit)
                ? parseArabic(normalized) : parseChinese(normalized);
        return value != null && value >= 1 && value <= MAX_SEASON ? value : null;
    }

    private static String toHalfWidthDigits(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            sb.append(c >= '０' && c <= '９' ? (char) (c - '０' + '0') : c);
        }
        return sb.toString();
    }

    private static Integer parseArabic(String digits) {
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            // 位数多到溢出 int，本来就远超季号的合理范围
            return null;
        }
    }

    /**
     * 中文数字转阿拉伯数字，覆盖「一」到「九十九」以及「一百」。
     * <p>
     * 「十」「十一」这类省略前导一的写法要按 10、11 解析——「第十季」是最常见的中文写法，
     * 而不是「第一十季」。
     * </p>
     *
     * @return 解析结果；出现清单外的字符时返回 null（宁可不给季号，也不要猜一个）
     */
    private static Integer parseChinese(String text) {
        int total = 0;
        int digit = 0;
        boolean seen = false;
        for (char c : text.toCharArray()) {
            int value = digitOf(c);
            if (value >= 0) {
                digit = value;
                seen = true;
            } else if (c == '十') {
                total += (digit == 0 ? 1 : digit) * 10;
                digit = 0;
                seen = true;
            } else if (c == '百') {
                total += (digit == 0 ? 1 : digit) * 100;
                digit = 0;
                seen = true;
            } else {
                return null;
            }
        }
        return seen ? total + digit : null;
    }

    /** 单个中文数字字符的值；不是数字字符时返回 -1（0 是合法取值，不能拿它当哨兵） */
    private static int digitOf(char c) {
        switch (c) {
            case '〇':
            case '零':
                return 0;
            case '一':
                return 1;
            case '二':
            case '两':
                return 2;
            case '三':
                return 3;
            case '四':
                return 4;
            case '五':
                return 5;
            case '六':
                return 6;
            case '七':
                return 7;
            case '八':
                return 8;
            case '九':
                return 9;
            default:
                return -1;
        }
    }
}
