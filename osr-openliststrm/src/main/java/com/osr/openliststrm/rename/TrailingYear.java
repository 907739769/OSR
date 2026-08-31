package com.osr.openliststrm.rename;

import com.osr.common.utils.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 作品标题<b>尾部</b>年份括号（{@code 给阿嬷的情书 (2026)}）的剥离与解析。纯函数，无 IO，无 Spring 依赖。
 * <p>
 * <b>为什么必须剥掉</b>：年份对「这是哪部作品」没有贡献，却足以让一切<b>标题全等</b>判定落空——
 * 而本项目里判定「是不是同一部作品」的地方一律做全等而不是包含（理由见 {@link TitleNormalizer}）。
 * 两条链路各踩过一次：
 * <ul>
 *   <li><b>热门自动订阅的豆瓣源</b>：条目标题写作「某某 (2026) 8.5」，年份不剥则
 *       {@code PopularItemResolver} 的标题全等判定必然落空，整条记成「TMDb 未搜到标题一致的条目」。</li>
 *   <li><b>PT 的 RSS 匹配</b>：国内站把作品的中文名放在种子 {@code description} 里当别名列表
 *       （{@code DescriptionAliases}），而部分站点的模板会在名字后面缀上年份。实测条目
 *       {@code Gei a ma de qing shu 2026 1080p WEB-DL…} + {@code 给阿嬷的情书 (2026)}：
 *       标题是罗马音拼音，TMDb 给订阅的三个标题里没有这一种，第一轮必然落空；第二轮的别名
 *       本该救回来，却因为归一化后多出一个 {@code 2026} 而与订阅标题不相等，整条种子被淘汰。</li>
 * </ul>
 * <p>
 * <b>只剥括号里的年份，绝不放宽成「尾部四位数字」</b>：《速度与激情 9》一类序号就长在尾部，
 * 剥错之后标题全等判定同样落空，且完全静默——这个取向与 {@code DoubanRssParser} 的
 * 「尾部评分必须带小数点」是同一条。
 * </p>
 * <p>
 * <b>为什么独立成类</b>：与 {@link SeasonSuffix}、{@link TitleNormalizer} 完全同源的理由——
 * 正则原先是 {@code DoubanRssParser} 的私有字段，PT 匹配侧要用只能复制一份，而复制出来的
 * 两份迟早漂移，漂移的表现是「同一个标题在豆瓣侧剥掉了、在 RSS 匹配侧没剥」，
 * 从日志里根本看不出来。
 * </p>
 *
 * @author Jack
 */
public final class TrailingYear {

    /** 标题尾部的年份括号，半角与全角都收 */
    private static final Pattern SUFFIX = Pattern.compile("[(（]\\s*((?:19|20)\\d{2})\\s*[)）]\\s*$");

    private TrailingYear() {
    }

    /**
     * 剥掉尾部年份括号。
     * <p>
     * <b>剥空则不剥</b>：整个标题就是一个年份括号时剥掉什么都不剩，拿空串去搜 TMDb
     * 只会白打一次请求，拿空串去比对订阅标题更是谁都匹配不上。判据与 {@link #parse} 一致。
     * </p>
     *
     * @param title 允许为 null
     * @return 剥掉后缀的标题；入参为 null 时返回 null，剥空时返回原值
     */
    public static String strip(String title) {
        if (title == null) {
            return null;
        }
        Matcher suffix = SUFFIX.matcher(title);
        if (!suffix.find()) {
            return title;
        }
        String stripped = title.substring(0, suffix.start()).trim();
        return stripped.isEmpty() ? title : stripped;
    }

    /**
     * 解析尾部年份括号里的年份。
     *
     * @param title 允许为 null/空白
     * @return 四位年份原文（如 {@code "2026"}）；没有后缀或 {@link #strip} 会剥空时返回 null
     */
    public static String parse(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        Matcher suffix = SUFFIX.matcher(title);
        if (!suffix.find()) {
            return null;
        }
        // 整个标题就是年份括号时不解析，理由同 strip 的「剥空则不剥」
        if (title.substring(0, suffix.start()).trim().isEmpty()) {
            return null;
        }
        return suffix.group(1);
    }
}
