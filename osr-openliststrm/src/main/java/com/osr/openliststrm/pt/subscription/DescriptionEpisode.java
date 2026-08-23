package com.osr.openliststrm.pt.subscription;

import com.osr.common.utils.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Torznab 条目的 {@code description} 里抽取集号，专治「标题不写集号」的发布组。
 * <p>
 * 存在的理由是一类实测存在的资源：日更剧的发布组（HHWEB 一类）把同一季<b>每一集</b>都发成
 * 标题逐字相同的种子——{@code Mystic Nine S01 2026 2160p YK WEB-DL H265 DTS5.1-HHWEB}，
 * 从 E01 到 E30 全是这一个标题，集号只出现在 description 里：
 * </p>
 * <pre>
 * 九门 / 老九门2 / 老九门贰 | 第21集 | 4K 高码 杜比视界 | 类型: 剧情/奇幻/冒险
 * </pre>
 * <p>
 * 同一位置的另一种常见写法是 {@code SxxExx}，标题标成整季、真实范围只在 description 里：
 * </p>
 * <pre>
 * title       Re Zero kara Hajimeru Isekai Seikatsu S01 2016 1080p BluRay Remux AVC FLAC 2.0-FROGE
 * description Re：从零开始的异世界生活 / … | S01E51-E66 | 内封简繁字幕
 * </pre>
 * <p>
 * 这一段的第一个字段是<b>作品别名</b>，由 {@link DescriptionAliases} 负责——两者读的是同一份
 * 站点模板的相邻字段，改一个之前先看一眼另一个。
 * </p>
 * <p>
 * 不解析它的后果是连锁的：{@link SubscriptionMatcher} 把「有季无集」一律判成整季包 →
 * {@code SubscriptionEngine#resolveTargets} 占位该订阅<b>全部缺失集</b> → 推送 →
 * {@code DownloadTrackService#trySelectFiles} 拿到文件列表才发现包里只有第 21 集、
 * 与本次要补的集毫无交集 → 整条记录判 {@code NO_TARGET_EPISODE} 中止、8 个占位集来回震荡。
 * 而同一个种子还会被转发到多个站，同样的白跑要按索引器数量再乘一遍。这些代价全部来自
 * 「推送那一刻不知道包里是第几集」，而这条信息其实一直摆在 description 里。
 * </p>
 * <p>
 * <b>标题解析出的集号永远优先，本类只在标题判不出集号时补位</b>（调用点见
 * {@code SubscriptionEngine#applyDescriptionEpisode}）。description 是站点自填的自由文本，
 * 可靠性天然低于遵循命名规范的标题。
 * </p>
 * <p>
 * 判据刻意收得很紧——description 里混着剧情简介、演职员表、促销说明，宽松的正则会从
 * 「主演出演过第3集」这类句子里捞出假集号，而假集号比没有集号糟得多：没有集号只是退回
 * 现状（当季包处理，事后由文件列表对账兜底），假集号会让种子去匹配一个错误的集，
 * 下错内容且没有任何一层能发现。各自的约束见 {@link #SXXEXX_RANGE}、{@link #EPISODE}
 * 与 {@link #RANGE} 的注释。
 * </p>
 *
 * @author Jack
 */
public final class DescriptionEpisode {

    /**
     * 集数区间：{@code 第01-26集}、{@code 第1~26话}。与 {@link SeasonPackRange} 里的中文区间
     * 同一写法，但这里作用于 description 而非标题。必须先于 {@link #EPISODE} 尝试——
     * 单集正则要求「第」与「集」贴着数字，{@code 第01-26集} 不会命中它，但顺序写反了不好读。
     */
    private static final Pattern RANGE = Pattern.compile(
            "第\\s*(\\d{1,3})\\s*[-~～－]\\s*(\\d{1,3})\\s*[集话話期]");

    /**
     * {@code SxxExx} 区间：{@code S01E51-E66}、{@code S01E51-66}、{@code S01E51-S01E66}、
     * {@code E51-E66}、{@code EP51-EP66}。国内站在 description 的元信息区用这种写法标注
     * 「这个包里是哪几集」，与中文写法并存（实测 {@code … | S01E51-E66 | 内封简繁字幕}）。
     * <p>
     * <b>比中文写法可靠，因此排在它前面</b>：{@code E} 与数字构成的锚定在自由文本里几乎不可能
     * 偶然出现，而 {@code 第\d+集} 再怎么加约束也是在中文散文里捞数字。同一段 description 里
     * 两种写法给出不同答案的情况罕见到可以不考虑，真出现时信这一个。
     * </p>
     * <p>
     * 三条约束：<b>前置否定后瞻</b> {@code (?<![A-Za-z0-9])} 要求 {@code S}/{@code E} 是词首，
     * 挡掉 {@code HEVC}、{@code TrueHD7} 这类编码串里的字母数字组合；<b>集号允许 4 位</b>
     * （与中文写法限 3 位不同——{@code E1173} 这种绝对编号是长篇动画的常态，而有 {@code E} 锚定
     * 之后四位数被误当年份的可能性没有了）；<b>区间结尾省略 {@code E} 前缀时只许 3 位</b>，
     * 否则 {@code E01-2021} 里的年份会被当成结尾集号。
     * </p>
     */
    private static final Pattern SXXEXX_RANGE = Pattern.compile(
            "(?<![A-Za-z0-9])(?:S(\\d{1,2}))?EP?(\\d{1,4})\\s*[-~～－]\\s*(?:S\\d{1,2})?(EP?)?(\\d{1,4})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    /** {@code SxxExx} 单集：{@code S01E21}、{@code E21}、{@code EP21}。约束同 {@link #SXXEXX_RANGE} */
    private static final Pattern SXXEXX_EPISODE = Pattern.compile(
            "(?<![A-Za-z0-9])(?:S(\\d{1,2}))?EP?(\\d{1,4})(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    /** 区间结尾省略 {@code E} 前缀时允许的最大位数，见 {@link #SXXEXX_RANGE} 的注释 */
    private static final int MAX_BARE_RANGE_END_DIGITS = 3;

    /**
     * 单集：{@code 第21集}、{@code 第 21 话}。
     * <p>
     * 三条约束，每一条都对应一种实测会出现的假阳性：
     * </p>
     * <ul>
     *   <li><b>前置否定后瞻</b>排除 {@code 更新至第30集}、{@code 共第30集}、{@code 全第30集}、
     *       {@code 至第30集}——那些说的是「这部剧一共/已经更新到多少集」，是<b>总集数</b>而不是
     *       本种子的集号。把总集数当集号，种子会去认领一个它根本没有的集。</li>
     *   <li><b>限 1-3 位数字</b>，与 {@link SeasonPackRange} 同一取向：四位数几乎必然是年份
     *       （{@code 第2019集} 不存在），放开位数只会把年份放进来。</li>
     *   <li><b>「集/话/期」必须紧跟数字</b>（中间只容空白），且<b>后面不能再跟汉字</b>。
     *       只要前一半约束的话，{@code 导演在第21话说的是另一件事} 照样命中——「话」确实紧跟着
     *       21，只是它在这里是「说话」的话。而站点模板里集号总是独立字段
     *       （{@code … | 第21集 | 4K 高码 …}），后面跟的是空白或分隔符。代价是
     *       {@code 第21集完} 这类紧接收尾字的写法会漏掉，方向上可以接受：漏掉只是退回
     *       「当整季包、事后按文件列表对账」的既有行为，认错集则会实打实下错内容。</li>
     * </ul>
     */
    private static final Pattern EPISODE = Pattern.compile(
            "(?<![至到共全总新])第\\s*(\\d{1,3})\\s*[集话話期](?![\\u4e00-\\u9fa5])");

    private DescriptionEpisode() {
    }

    /**
     * 解析结果。单集时 {@code end == start}；区间时 {@code end > start} 恒成立。
     *
     * @param season description 里一并写出的季号（{@code S01E51} 的 01），中文写法与裸
     *               {@code E51} 都给不出季号，此时为 {@code null}。调用方拿它与标题解析出的
     *               季号交叉校验（见 {@code SubscriptionEngine#applyDescriptionEpisode}）——
     *               两边不一致说明必有一方解析错了，此时宁可不补集号
     */
    public record Episodes(Integer season, int start, int end) {

        /** 是不是一个真区间（跨多集），单集返回 false */
        public boolean isRange() {
            return end > start;
        }
    }

    /**
     * @param description Torznab 条目的 description 原文，可为 null
     * @return 解析出的集号；判不出来返回 {@code null}，调用方维持既有行为（当季包处理）
     */
    public static Episodes parse(String description) {
        if (StringUtils.isBlank(description)) {
            return null;
        }
        // SxxExx 排在中文写法之前，理由见 SXXEXX_RANGE 的注释；两种写法内部都是先区间后单集
        Episodes episodes = firstSxxExxRange(description);
        if (episodes == null) {
            episodes = firstSxxExxEpisode(description);
        }
        if (episodes == null) {
            episodes = firstRange(description);
        }
        return episodes != null ? episodes : firstEpisode(description);
    }

    /**
     * 取第一个通过校验的 {@code SxxExx} 区间。理由同 {@link #firstRange}：前面可能先撞上一个
     * {@code E01-E01} 这样没有信息量的写法，不能就此收手。
     */
    private static Episodes firstSxxExxRange(String description) {
        Matcher matcher = SXXEXX_RANGE.matcher(description);
        while (matcher.find()) {
            String endDigits = matcher.group(4);
            // 结尾没写 E 前缀时只认 3 位以内，否则 E01-2021 里的年份会被当成结尾集号
            if (matcher.group(3) == null && endDigits.length() > MAX_BARE_RANGE_END_DIGITS) {
                continue;
            }
            Integer start = toInt(matcher.group(2));
            Integer end = toInt(endDigits);
            if (start != null && start > 0 && end != null && end > start) {
                return new Episodes(toInt(matcher.group(1)), start, end);
            }
        }
        return null;
    }

    /** 取第一个 {@code SxxExx} 单集匹配，理由同 {@link #firstEpisode} */
    private static Episodes firstSxxExxEpisode(String description) {
        Matcher matcher = SXXEXX_EPISODE.matcher(description);
        while (matcher.find()) {
            Integer episode = toInt(matcher.group(2));
            // 第 0 集不存在，出现即说明匹到的不是集号
            if (episode != null && episode > 0) {
                return new Episodes(toInt(matcher.group(1)), episode, episode);
            }
        }
        return null;
    }

    /**
     * 取第一个<b>通过校验</b>的区间匹配。与 {@link SeasonPackRange} 取首个合法匹配同一理由：
     * 前面可能先撞上一个 {@code 第1-1集} 这样没有信息量的写法，不能就此收手。
     */
    private static Episodes firstRange(String description) {
        Matcher matcher = RANGE.matcher(description);
        while (matcher.find()) {
            Integer start = toInt(matcher.group(1));
            Integer end = toInt(matcher.group(2));
            if (start != null && end != null && end > start) {
                return new Episodes(null, start, end);
            }
        }
        return null;
    }

    /**
     * 取<b>第一个</b>单集匹配。站点模板把集号放在 description 开头的元信息区
     * （{@code 标题 | 第21集 | 画质说明 | 类型…}），而剧情简介、演职员表排在后面——
     * 越靠前的匹配越可能是元信息，取第一个是这个格式下最稳的选择。
     */
    private static Episodes firstEpisode(String description) {
        Matcher matcher = EPISODE.matcher(description);
        while (matcher.find()) {
            Integer episode = toInt(matcher.group(1));
            // 第 0 集不存在，出现即说明匹到的不是集号
            if (episode != null && episode > 0) {
                return new Episodes(null, episode, episode);
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
