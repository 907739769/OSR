package com.osr.openliststrm.pt.filter;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.pt.indexer.GuidHasher;
import com.osr.openliststrm.pt.model.TorrentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 种子过滤与择优引擎。纯逻辑，不读数据库、不发网络请求——生效条件由调用方
 * 通过 {@link FilterCriteria} 传入（见 {@link FilterCriteriaFactory}），
 * 黑名单规则由调用方通过 {@link TorrentBlacklist} 传入。
 *
 * @author Jack
 */
@Slf4j
@Component
public class TorrentFilterEngine {

    /**
     * 中文字幕标识正则——匹配种子标题或描述中的常见中文字幕标注。
     * <p>
     * 覆盖以下常见 PT 站命名惯例：
     * <ul>
     *   <li>CHS / CHT（简中/繁中）</li>
     *   <li>SUBCHS / SUBCHT（内嵌中字变体）</li>
     *   <li>中字 / 繁中 / 简中</li>
     *   <li>Chinese Subtitle / Chinese Subtitles</li>
     *   <li>简英 / 繁英 / 简繁英（双语字幕）</li>
     *   <li>国粤 / 双语（中文字幕隐含）</li>
     * </ul>
     * </p>
     */
    private static final Pattern CHINESE_SUBTITLE_PATTERN = Pattern.compile(
            "\\b(CHS|CHT|SUBCHS|SUBCHT)\\b" +
            "|中字|繁中|简中|繁体中文字幕|简体中文字幕" +
            "|\\bChinese\\s+Subtitle(s)?\\b" +
            "|简英|繁英|简繁英|国粤|双语",
            Pattern.CASE_INSENSITIVE);

    /**
     * 候选种子的过滤裁决：{@code rejectReason} 为 null 表示通过。
     */
    public record Verdict(TorrentInfo torrent, String rejectReason) {
        public boolean accepted() {
            return rejectReason == null;
        }
    }

    /**
     * 逐条给出候选的过滤裁决与具体原因，供调用方落库供前端排查
     * （见 {@link com.osr.openliststrm.pt.subscription.SubscriptionEngine}）。
     * {@link #filter} 基于本方法实现，两者的淘汰判定逻辑保证一致。
     * <p>本重载等价于「无黑名单 + 不做中字检查」，只适合纯逻辑单测这类确实没有这两项输入的场景。
     * 业务链路一律用四参版本显式传参。</p>
     */
    public List<Verdict> evaluate(List<TorrentInfo> candidates, FilterCriteria criteria) {
        return evaluate(candidates, criteria, TorrentBlacklist.EMPTY, null);
    }

    /**
     * 黑名单 + 原始语言中字检查同时生效。
     * <p>
     * <b>本类刻意不提供三参重载。</b>历史上这里同时存在 {@code (…, TorrentBlacklist)} 与
     * {@code (…, String originalLanguage)} 两个三参版本，只靠第三个参数的类型区分——
     * {@code SearchSupplementService} 的手动搜索路径本该传黑名单，却调到了 String 版本，
     * 使得已拉黑的发布组/种子照常出现在候选列表里，用户选中后推送又被真正带黑名单的
     * {@code SubscriptionEngine#pushBest} 拦下，前端只能看到一句没有原因的"推送失败"。
     * 调用方必须两个参数都显式写出来，才不会再有"少传了哪个"这种静默错配。
     * </p>
     *
     * @param blacklist        生效的种子/发布组黑名单，无黑名单传 {@link TorrentBlacklist#EMPTY}
     * @param originalLanguage 影片的原始语言代码（如 "en"、"zh"），为 null 则跳过中字检查
     */
    public List<Verdict> evaluate(List<TorrentInfo> candidates, FilterCriteria criteria,
                                   TorrentBlacklist blacklist, String originalLanguage) {
        List<Verdict> verdicts = new ArrayList<>();
        for (TorrentInfo torrent : candidates) {
            verdicts.add(new Verdict(torrent, rejectReason(torrent, criteria, blacklist, originalLanguage)));
        }
        return verdicts;
    }

    /**
     * 硬性过滤：淘汰不满足条件的候选，保留原顺序。
     * <p>
     * 被淘汰的种子不落库，只记 debug 日志并带上具体原因（哪条规则、阈值、实际值）——
     * 这些日志是后续调优过滤规则的主要素材。
     * </p>
     *
     * @return 新的可变列表，调用方修改它不会影响入参
     */
    public List<TorrentInfo> filter(List<TorrentInfo> candidates, FilterCriteria criteria) {
        return filter(candidates, criteria, TorrentBlacklist.EMPTY, null);
    }

    /**
     * 黑名单 + 原始语言中字检查同时生效。三参重载被刻意移除，理由见
     * {@link #evaluate(List, FilterCriteria, TorrentBlacklist, String)}。
     */
    public List<TorrentInfo> filter(List<TorrentInfo> candidates, FilterCriteria criteria,
                                     TorrentBlacklist blacklist, String originalLanguage) {
        List<TorrentInfo> survivors = new ArrayList<>();
        for (Verdict verdict : evaluate(candidates, criteria, blacklist, originalLanguage)) {
            if (verdict.accepted()) {
                survivors.add(verdict.torrent());
            } else {
                log.debug("种子被过滤：{} —— {}", verdict.torrent().getTitle(), verdict.rejectReason());
            }
        }
        return survivors;
    }

    /**
     * 从候选中挑出最优的一个。
     * <p>
     * 按 {@link FilterCriteria#sortPriority()} 的维度顺序，把各维度的比较器用
     * thenComparing 串联后取排在最前的那个。维度顺序由配置决定，因此同一批候选
     * 在不同配置下会选出不同的赢家——这正是「排序权重可调」的实现方式。
     * </p>
     * <p>
     * 全部维度都判同级时返回列表中的第一个（比较过程不改变入参列表的顺序）。
     * </p>
     *
     * @return 最优候选；候选为空时返回 null
     */
    public TorrentInfo pickBest(List<TorrentInfo> candidates, FilterCriteria criteria) {
        if (candidates.isEmpty()) {
            return null;
        }
        Comparator<TorrentInfo> comparator = null;
        for (SortDimension dimension : criteria.sortPriority()) {
            Comparator<TorrentInfo> next = dimension.comparator(criteria);
            comparator = (comparator == null) ? next : comparator.thenComparing(next);
        }
        if (comparator == null) {
            // FilterCriteria 保证 sortPriority 非空，这里只是防御
            return candidates.get(0);
        }

        TorrentInfo best = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            // 严格小于才替换，保证同级时保留先出现的那个
            if (comparator.compare(candidates.get(i), best) < 0) {
                best = candidates.get(i);
            }
        }
        log.debug("择优结果：{}（候选 {} 个，维度顺序 {}）",
                best.getTitle(), candidates.size(), criteria.sortPriority());
        return best;
    }

    /**
     * 返回淘汰原因；返回 null 表示通过。判定顺序：
     * GUID 黑名单 → 做种数 → 体积上下限 → 免费 → H&R 规避 → 分辨率白名单 → 来源白名单 → 标题为空
     * → 发布组黑名单 → 排除标签 → 必需标签 → 排除词 → 包含词 → 外语电影中字检查。
     * <p>
     * GUID 判定放最前：不依赖标题解析、不依赖任何统计字段，是最便宜的判定，
     * 而且"拉黑一个具体种子"是用户的强确定性意图，语义上应该比软性阈值更早生效。
     * 发布组、质量标签判定放在"标题为空"之后：它们依赖 {@code parsedReleaseGroup}/
     * {@code parsedTags}，这些字段本质上是标题解析的产物，与 excludeKeywords/includeKeywords
     * 一样要求标题非空。
     * </p>
     *
     * @param originalLanguage 影片的原始语言代码（如 "en"、"zh"），为 null 则跳过中字检查
     */
    private String rejectReason(TorrentInfo torrent, FilterCriteria criteria,
                                 TorrentBlacklist blacklist, String originalLanguage) {
        if (!blacklist.guidHashes().isEmpty()) {
            String guid = torrent.getGuid();
            if (StringUtils.isNotBlank(guid) && blacklist.guidHashes().contains(GuidHasher.hash(guid))) {
                return "该种子已被手动拉黑（GUID）";
            }
        }
        if (torrent.getSeeders() < criteria.minSeeders()) {
            return "做种数 " + torrent.getSeeders() + " 低于下限 " + criteria.minSeeders();
        }
        if (criteria.minSize() > 0 && torrent.getSize() < criteria.minSize()) {
            return "体积 " + torrent.getSize() + " 小于下限 " + criteria.minSize();
        }
        if (criteria.maxSize() > 0 && torrent.getSize() > criteria.maxSize()) {
            return "体积 " + torrent.getSize() + " 超过上限 " + criteria.maxSize();
        }
        if (criteria.freeOnly() && !torrent.isFree()) {
            return "非免费种(下载量系数 " + torrent.getDownloadVolumeFactor() + ")，而配置为仅要免费";
        }
        // 与做种数/体积/免费一样是不依赖标题解析的站点级判定，放在同一段
        if (criteria.avoidHitAndRun() && torrent.isHitAndRun()) {
            return "来源站点有 H&R 考核，而配置为规避 H&R";
        }
        List<String> whitelist = criteria.resolutionWhitelist();
        if (!whitelist.isEmpty()) {
            String resolution = torrent.getParsedResolution();
            // 解析不出分辨率时无法判定是否在白名单内，不能放行；只有白名单为空(不限)才不受此约束
            if (StringUtils.isBlank(resolution) || !containsIgnoreCase(whitelist, resolution.trim())) {
                String actual = StringUtils.isBlank(resolution) ? "(未知)" : resolution;
                return "分辨率 " + actual + " 不在白名单 " + whitelist + " 内";
            }
        }

        // 媒介来源白名单：与分辨率白名单完全同构（含"解析不出即淘汰"的取向），
        // 紧挨着放是为了让两条对称的规则在代码和淘汰原因里都对称，方便用户对照理解。
        List<String> sourceWhitelist = criteria.sourceWhitelist();
        if (!sourceWhitelist.isEmpty()) {
            String source = torrent.getParsedSource();
            if (StringUtils.isBlank(source) || !containsIgnoreCase(sourceWhitelist, source.trim())) {
                String actual = StringUtils.isBlank(source) ? "(未知)" : source;
                return "媒介来源 " + actual + " 不在白名单 " + sourceWhitelist + " 内";
            }
        }

        String title = torrent.getTitle();
        // 标题缺失的条目无法做关键词判定，一律淘汰而非放行
        if (StringUtils.isBlank(title)) {
            return "标题为空，无法判定";
        }

        if (!blacklist.releaseGroupsUpper().isEmpty()) {
            String group = torrent.getParsedReleaseGroup();
            if (StringUtils.isNotBlank(group) && blacklist.releaseGroupsUpper().contains(group.toUpperCase(Locale.ROOT))) {
                return "发布组「" + group + "」已被手动拉黑";
            }
        }

        // 质量标签判定放在这里而不是跟分辨率/来源白名单一起：标签同样是标题解析的产物，
        // 与 excludeKeywords/includeKeywords 一样要求标题非空——标题为空时 parsedTags 必然是
        // 空列表，先判"标题为空"能给出更有解释力的淘汰原因，而不是含糊的"缺少必需标签"。
        List<String> tags = torrent.getParsedTags();
        for (String excluded : criteria.excludeTags()) {
            if (containsIgnoreCase(tags, excluded)) {
                return "命中排除标签「" + excluded + "」";
            }
        }
        // 必需标签是 AND 语义：配了 HDR,ATMOS 就是"既要 HDR 又要 ATMOS"。
        // 想表达"任选其一"请用标题包含词，那一项本就是 OR 语义。
        for (String required : criteria.requiredTags()) {
            if (!containsIgnoreCase(tags, required)) {
                return "缺少必需标签「" + required + "」（已解析到的标签：" + describeTags(tags) + "）";
            }
        }

        String lower = title.toLowerCase(Locale.ROOT);

        for (String keyword : criteria.excludeKeywords()) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return "命中排除词「" + keyword + "」";
            }
        }
        if (!criteria.includeKeywords().isEmpty() && !containsAny(lower, criteria.includeKeywords())) {
            return "未命中任何包含词 " + criteria.includeKeywords();
        }

        // 外语电影中字检测
        if (criteria.requireChineseSubtitle() && isForeignLanguage(originalLanguage)) {
            if (!hasChineseSubtitle(torrent)) {
                return "外语电影(originalLanguage=" + originalLanguage + ")，标题/描述中未检测到中文字幕标识";
            }
        }

        return null;
    }

    /**
     * 判断是否为外语电影（需中文字幕检查适用的范围）。
     * <p>
     * originalLanguage 为 null 时（API 不可用/未配置），跳过检查（安全失败）；
     * 以 "zh" 开头（zh / zh-CN / zh-TW 等）视为中文电影，不需要检查中文字幕。
     * </p>
     */
    private boolean isForeignLanguage(String originalLanguage) {
        return originalLanguage != null && !originalLanguage.toLowerCase(Locale.ROOT).startsWith("zh");
    }

    /**
     * 检测种子标题或描述中是否包含常见的中文字幕标识。
     */
    private boolean hasChineseSubtitle(TorrentInfo torrent) {
        String text = torrent.getTitle();
        if (CHINESE_SUBTITLE_PATTERN.matcher(text).find()) {
            return true;
        }
        String description = torrent.getDescription();
        if (StringUtils.isNotBlank(description) && CHINESE_SUBTITLE_PATTERN.matcher(description).find()) {
            return true;
        }
        return false;
    }

    private boolean containsAny(String lowerTitle, List<String> keywords) {
        for (String keyword : keywords) {
            if (lowerTitle.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 集合命中判定：整词相等而非子串包含，大小写不敏感——索引器标题里 1080P 与 1080p、
     * WEB-DL 与 web-dl、ATMOS 与 Atmos 都出现过。
     * <p>
     * 分辨率白名单、来源白名单、质量标签三处共用。整词相等这一点对标签尤其重要：
     * 子串包含会让 "HDR" 命中 "HDR10"、让 "DV" 命中任何含 dv 的字样。
     * </p>
     *
     * @param candidates 待匹配集合，允许为 null（视作空集合，恒不命中）
     */
    private boolean containsIgnoreCase(List<String> candidates, String value) {
        if (candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && candidate.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /** 淘汰原因里展示已解析到的标签，空集合时给个明确说法而不是空的方括号 */
    private String describeTags(List<String> tags) {
        return (tags == null || tags.isEmpty()) ? "无" : String.join("/", tags);
    }
}
