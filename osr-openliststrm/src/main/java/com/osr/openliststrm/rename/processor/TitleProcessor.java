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

    /** 方括号/中括号包裹的片段：站点标签、发布组、质量标注、部分中文站的作品名都是这个形态 */
    private static final Pattern BRACKET = Pattern.compile("[\\[【]([^\\]】]+)[\\]】]");

    /** 片段里是否真的含中文 */
    private static final Pattern HAS_CHINESE = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 连续中文块（含中点分隔符） */
    private static final Pattern CHINESE_RUN = Pattern.compile("([\\u4e00-\\u9fa5·．]+)");

    /**
     * 标题<b>尾部</b>的季号后缀：{@code 第四季}、{@code 第 2 部}、{@code Season 3}，允许连写多个。
     * <p>
     * <b>为什么必须剥掉</b>：{@link com.osr.openliststrm.tmdb.TMDbClient#search} 拿这个标题去 TMDb
     * 搜索，而 TMDb 的条目名里从来不带季号——多出的三个字会让整次查询落空，于是最强的信号
     * （中文作品名）作废，链路降级到 englishTitle 那个弱得多的候选。真实事故：
     * {@code [梦魇绝镇 第四季].From.2026.S04E10} 里 {@code 梦魇绝镇 第四季} 搜不到任何结果，
     * 退到英文名 {@code From}——一个四字母常用词——撞上《怪奇物语：1985故事集》
     * （{@code Stranger Things: Tales From '85}，原名里含 "From"、首播年恰好也是 2026），
     * 整季被刮成另一部剧。
     * </p>
     * <p>
     * <b>为什么不在 {@code YearSeasonEpisodeExtractor} 里给中文季正则加中文数字</b>：那条分支
     * 命中后会 early return 并按「第四季」的位置截断标题，{@code S04E10} 根本不会被解析，
     * 集号直接丢失。剥后缀只影响标题，不动抽取管线。
     * </p>
     * <p>
     * <b>只匹配结尾、且剥空则不剥</b>：《第五季》这类以季号为名的作品（比利时片
     * {@code La cinquième saison}）整个标题就是这几个字，剥掉会交出一个空标题。
     * </p>
     */
    private static final Pattern SEASON_SUFFIX = Pattern.compile(
            "(?:\\s*(?:第\\s*[0-9０-９〇零一二两三四五六七八九十百]+\\s*[季部]|[Ss]eason\\s*\\d{1,2}))+\\s*$");

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

        // 季号后缀对「这是哪部作品」没有任何贡献，却足以让 TMDb 查询整个落空（见 SEASON_SUFFIX 注释）。
        // PT 订阅侧同样受益：SubscriptionEngine 用 originalTitle 当种子的 parsedTitle 去比对订阅标题，
        // 而订阅存的是不带季号的作品名。
        info.setOriginalTitle(stripSeasonSuffix(info.getOriginalTitle()));
        info.setEnglishTitle(stripSeasonSuffix(info.getEnglishTitle()));

//        if (info.getTitle() == null) {
//            info.setTitle(info.getOriginalTitle());
//        }
    }

    /**
     * 剥掉标题尾部的季号后缀。剥完为空时返回原值——宁可多带三个字，也不能交出空标题。
     *
     * @param title 允许为 null
     */
    private String stripSeasonSuffix(String title) {
        if (title == null) {
            return null;
        }
        String stripped = SEASON_SUFFIX.matcher(title).replaceAll("").trim();
        return stripped.isEmpty() ? title : stripped;
    }

    private String removeRange(String s, int a, int b) {
        return (s.substring(0, a) + s.substring(b)).trim();
    }
}
