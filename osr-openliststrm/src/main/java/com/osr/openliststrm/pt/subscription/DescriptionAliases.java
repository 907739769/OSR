package com.osr.openliststrm.pt.subscription;

import com.osr.common.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 Torznab 条目的 {@code description} 里抽取<b>作品别名</b>，专治「标题用的名字订阅里没有」。
 * <p>
 * 存在的理由是一类实测搜不到的资源：国内站发布的日本动画常用<b>罗马音</b>命名，
 * 而 TMDb 给订阅的三个标题（中文名 / 原语言名 / 英文名）里恰恰没有罗马音这一种：
 * </p>
 * <pre>
 * title       Re Zero kara Hajimeru Isekai Seikatsu S01 2016 1080p BluRay Remux AVC FLAC 2.0-FROGE
 * description Re：从零开始的异世界生活 / Re:ゼロから始める異世界生活 / Re:Zero kara Hajimeru Isekai Seikatsu / Re:ZERO -Starting Life in Another World- | S01E51-E66 | 内封简繁字幕
 * 订阅         title=Re：从零开始的异世界生活  originalTitle=Re:ゼロから始める異世界生活  englishTitle=Re:ZERO -Starting Life in Another World-
 * </pre>
 * <p>
 * 三个订阅标题与种子标题两两归一化后没有一个相等，{@link SubscriptionMatcher} 在标题那一步
 * 就把整条种子淘汰了——而<b>能把两边对上的那个名字一直摆在 description 的第一段里</b>，
 * 站点模板正是拿它当别名列表用的（同一模板的第二段是集号，见 {@link DescriptionEpisode}）。
 * </p>
 * <p>
 * <b>别名只做兜底，标题匹配永远优先</b>（两轮匹配见 {@code SubscriptionMatcher#match}）。
 * description 是站点自填的自由文本，可靠性天然低于遵循命名规范的标题；两轮而不是一轮的
 * 理由是排序：一轮把别名并进候选集合的话，靠别名命中的订阅可能抢在靠标题命中的订阅前面，
 * 而后者才是正确答案。
 * </p>
 * <p>
 * 判据刻意收得很紧，取向与 {@link DescriptionEpisode} 完全一致——description 里还混着
 * 剧情简介、演职员表、促销说明与 {@code 类型: 剧情/奇幻/冒险} 这类同样用 {@code /} 分隔
 * 的字段，宽松的切分会把「冒险」「剧情」当成作品名，而<b>假别名比没有别名糟得多</b>：
 * 没有别名只是退回现状（这条种子匹配不上，用户手动搜），假别名会让种子被错误的订阅认领，
 * 下错内容且没有任何一层能发现。约束见 {@link #parse} 的注释。
 * </p>
 *
 * @author Jack
 */
public final class DescriptionAliases {

    /**
     * 别名列表与后续字段的分隔符。站点模板固定为
     * {@code 别名列表 | 集号 | 画质说明 | 类型: …}，<b>只取第一段</b>是本类最重要的一条约束：
     * 往后每一段都可能含 {@code /}（{@code 类型: 剧情/奇幻/冒险} 是实测存在的写法），
     * 整串切开必然产出一批题材词当别名。
     */
    private static final String[] SEGMENT_SEPARATORS = {"|", "｜", "\n", "\r"};

    /** 别名之间的分隔符，半角与全角斜杠 */
    private static final String ALIAS_SEPARATOR = "[/／]";

    /**
     * 别名段的长度上限。没有 {@code |} 的 description 会让「第一段」等于整段文本，
     * 而那多半是一段剧情简介；简介必然远长于一串别名（实测别名段在 120 字符上下）。
     */
    private static final int MAX_SEGMENT_LENGTH = 200;

    /** 单个别名的长度上限，超出的一律不是作品名 */
    private static final int MAX_ALIAS_LENGTH = 80;

    /**
     * 单个别名的长度下限。归一化前按字符数算，取 2 而不是 1：
     * 单字别名的收益极小，而它撞上另一部作品的概率极大。
     */
    private static final int MIN_ALIAS_LENGTH = 2;

    /**
     * 别名个数上限，<b>超出即整段放弃而不是截断取前 N 个</b>。
     * 「用 {@code /} 切出十几段」本身就是「这不是别名列表」的信号（题材串、演员表、
     * 音轨列表都是这个形态），此时取前 N 个只是把噪声换成更少的噪声。
     */
    private static final int MAX_ALIASES = 10;

    /**
     * 出现即判定「这是句子不是作品名」的字符。只列中日文句读与全角叹号问号——
     * 剧情简介里必然有它们，而作品名里几乎不会有。
     * <p>
     * <b>刻意不列半角 {@code , . ; !} 等</b>：英文片名里它们完全合法
     * （{@code Crazy, Stupid, Love}、{@code Dr. No}），排除掉会漏掉一批真别名。
     * 半角句子那一侧由段长上限兜底。
     * </p>
     */
    private static final String SENTENCE_MARKS = "。，、；！？…";

    private DescriptionAliases() {
    }

    /**
     * @param description Torznab 条目的 description 原文，可为 null
     * @return 别名列表（保持原文，未归一化，调用方自行走
     *         {@code TitleNormalizer}）；判不出来返回空列表，绝不返回 null
     */
    public static List<String> parse(String description) {
        if (StringUtils.isBlank(description)) {
            return List.of();
        }
        String segment = firstSegment(description).trim();
        if (segment.isEmpty() || segment.length() > MAX_SEGMENT_LENGTH) {
            return List.of();
        }
        String[] parts = segment.split(ALIAS_SEPARATOR);
        if (parts.length > MAX_ALIASES) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>(parts.length);
        for (String part : parts) {
            String alias = part.trim();
            if (isAlias(alias)) {
                aliases.add(alias);
            }
        }
        return aliases;
    }

    /**
     * 取别名列表所在的第一段。任一分隔符先出现就在那里截断——换行同样算段边界，
     * 因为部分站点的 description 是多行文本而不是单行 {@code |} 分隔。
     */
    private static String firstSegment(String description) {
        int end = description.length();
        for (String separator : SEGMENT_SEPARATORS) {
            int index = description.indexOf(separator);
            if (index >= 0 && index < end) {
                end = index;
            }
        }
        return description.substring(0, end);
    }

    private static boolean isAlias(String alias) {
        if (alias.length() < MIN_ALIAS_LENGTH || alias.length() > MAX_ALIAS_LENGTH) {
            return false;
        }
        for (int i = 0; i < alias.length(); i++) {
            if (SENTENCE_MARKS.indexOf(alias.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }
}
