package com.osr.openliststrm.pt.subscription;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.model.ExternalIds;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.dto.MatchResult;
import com.osr.openliststrm.rename.TitleNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把一条已本地解析的种子匹配到某个订阅的某一集。
 *
 * @author Jack
 */
@Slf4j
@Component
public class SubscriptionMatcher {

    /** 季包的集号哨兵值：种子含整季 */
    public static final int SEASON_PACK = -1;

    /** 电影的集号哨兵值 */
    public static final int MOVIE_EPISODE = 0;

    private static final String TYPE_MOVIE = "MOVIE";

    /**
     * @return 匹配结果；匹配不上返回 null
     */
    public MatchResult match(TorrentInfo torrent, List<PtSubscriptionPlus> subscriptions) {
        return match(torrent, subscriptions, Map.of());
    }

    /**
     * @param absoluteMaps 订阅ID → 绝对集号映射，用于识别按绝对编号命名的种子
     *                     （见 {@link AbsoluteEpisodeMap}）。传空 Map 即退回纯季集匹配
     */
    public MatchResult match(TorrentInfo torrent, List<PtSubscriptionPlus> subscriptions,
                             Map<Integer, AbsoluteEpisodeMap> absoluteMaps) {
        // 外部 ID 一轮排在标题之前，见 identityOf 的注释
        MatchResult byId = matchById(torrent, subscriptions, absoluteMaps);
        if (byId != null) {
            return byId;
        }
        Set<String> torrentTitles = torrentTitles(torrent);
        if (!torrentTitles.isEmpty()) {
            MatchResult byTitle = matchAgainst(torrent, subscriptions, absoluteMaps, torrentTitles);
            if (byTitle != null) {
                return byTitle;
            }
        }
        // 标题一轮全落空，才拿 description 里的别名再走一遍，见 descriptionAliases 的注释
        Set<String> aliases = descriptionAliases(torrent, torrentTitles);
        if (aliases.isEmpty()) {
            return null;
        }
        return matchAgainst(torrent, subscriptions, absoluteMaps, aliases);
    }

    /**
     * 拿一组归一化后的种子标题遍历全部订阅，命中即返回。
     * <p>
     * 抽出来是为了让「标题一轮、别名一轮」这件事在 {@link #match} 里读得出来——
     * 两轮的<b>顺序</b>是有语义的，不是可以随手合并的重复代码：把别名并进同一个集合跑一轮的话，
     * 靠别名命中的订阅会抢在靠标题命中的订阅前面（本方法取首个命中），而后者才是正确答案。
     * </p>
     */
    private MatchResult matchAgainst(TorrentInfo torrent, List<PtSubscriptionPlus> subscriptions,
                                     Map<Integer, AbsoluteEpisodeMap> absoluteMaps, Set<String> torrentTitles) {
        for (PtSubscriptionPlus sub : subscriptions) {
            Set<String> subTitles = normalizeAll(sub.getTitle(), sub.getOriginalTitle(), sub.getEnglishTitle());
            if (Collections.disjoint(torrentTitles, subTitles)) {
                continue;
            }
            // 标题对得上、ID 却说是另一部作品：同名剧/同名翻拍，标题这一维在构造上分不开它们
            if (identityOf(torrent, sub) == Identity.DIFFERENT) {
                continue;
            }
            MatchResult result = matchEpisode(torrent, sub,
                    absoluteMaps.getOrDefault(sub.getId(), AbsoluteEpisodeMap.EMPTY));
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * 按外部 ID 认领：种子带着的 IMDb/TMDb ID 与某条订阅一致，就不再要求标题相同。
     * <p>
     * 这一轮只放宽「是不是这部作品」，季号、集号、年份判定原样照走 {@link #matchEpisode}。
     * </p>
     */
    private MatchResult matchById(TorrentInfo torrent, List<PtSubscriptionPlus> subscriptions,
                                  Map<Integer, AbsoluteEpisodeMap> absoluteMaps) {
        if (torrent.getImdbId() == null && torrent.getTmdbId() == null) {
            return null;
        }
        for (PtSubscriptionPlus sub : subscriptions) {
            if (identityOf(torrent, sub) != Identity.SAME) {
                continue;
            }
            MatchResult result = matchEpisode(torrent, sub,
                    absoluteMaps.getOrDefault(sub.getId(), AbsoluteEpisodeMap.EMPTY));
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /** 外部 ID 对「种子是不是这条订阅的作品」给出的结论 */
    public enum Identity {
        /** 至少有一种可比的 ID 两侧相同 */
        SAME,
        /** 有可比的 ID，且全部两侧不同 */
        DIFFERENT,
        /** 没有任何一种 ID 两侧都有（或 TMDb 那种分不清电影/剧集），ID 说不出结论 */
        UNKNOWN
    }

    /**
     * 用外部 ID 判断种子是不是这条订阅的作品。<b>RSS 匹配与搜索补集共用这一份判据</b>。
     * <p>
     * 引入它是因为「标题全等」这道判据两头都会出错：译名不同、罗马音/拼音命名、解析器切错标题时
     * 会<b>漏</b>——而搜索侧的 ID 精确检索压根不校验标题，于是出现「RSS 订不到、手动搜索能搜到」；
     * 同名作品（《人生复本》与《暗物质》英文名都叫 Dark Matter）时又会<b>串</b>。
     * 索引器（Jackett/Prowlarr）对许多站点会在条目上附带 imdb/tmdbid 属性，那才是能把两件事都
     * 说清楚的信号。
     * </p>
     * <ul>
     *   <li><b>SAME 只要一种 ID 相同，DIFFERENT 要求全部可比的 ID 都不同。</b>站点上的 IMDb 链接
     *       是发布者手填的，剧集有时填成了<b>单集</b>的 IMDb 页（与剧集页编号不同）。只凭 IMDb
     *       不同就否决，会把这类完全正确的种子挡掉，且没有任何错误现象。TMDb 同时给出且相同时，
     *       以相同为准。</li>
     *   <li><b>TMDb ID 只有在分类判得出电影/剧集、且与订阅类型一致时才可比。</b>TMDb 的电影与剧集是
     *       两套独立编号，{@code movie/1399} 与 {@code tv/1399} 毫不相干；分类缺失或两类都有时一律
     *       当作没给。IMDb 编号全局唯一，没有这个问题。</li>
     *   <li><b>判不出来就是 UNKNOWN</b>，调用方退回标题判定——绝大多数种子（尤其是没有 IMDb 链接的
     *       国内资源）走的仍是标题那条路，行为与引入前完全一致。</li>
     * </ul>
     */
    public Identity identityOf(TorrentInfo torrent, PtSubscriptionPlus sub) {
        boolean comparable = false;
        String subImdb = ExternalIds.normalizeImdb(sub.getImdbId());
        if (torrent.getImdbId() != null && subImdb != null) {
            if (torrent.getImdbId().equals(subImdb)) {
                return Identity.SAME;
            }
            comparable = true;
        }
        String subTmdb = ExternalIds.normalizeTmdb(sub.getTmdbId());
        if (torrent.getTmdbId() != null && subTmdb != null && kindMatches(torrent, sub)) {
            if (torrent.getTmdbId().equals(subTmdb)) {
                return Identity.SAME;
            }
            comparable = true;
        }
        return comparable ? Identity.DIFFERENT : Identity.UNKNOWN;
    }

    private boolean kindMatches(TorrentInfo torrent, PtSubscriptionPlus sub) {
        ExternalIds.Kind kind = ExternalIds.kindOf(torrent.getCategories());
        if (kind == ExternalIds.Kind.UNKNOWN) {
            return false;
        }
        return (kind == ExternalIds.Kind.MOVIE) == TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType());
    }

    /**
     * 种子侧的候选标题集合（归一化后）。
     * <p>
     * parsedTitle/parsedTitleEn 都解析失败（特殊命名格式）时回退到种子原始标题，避免漏判；
     * 与 {@code SearchSupplementService#titleMatches} 共用本方法，否则会出现
     * RSS 自动匹配漏掉、手动搜索补集却能补回来的不一致体验。
     * </p>
     */
    Set<String> torrentTitles(TorrentInfo torrent) {
        String t1 = torrent.getParsedTitle();
        String t2 = torrent.getParsedTitleEn();
        String tFallback = (t1 == null && t2 == null) ? torrent.getTitle() : null;
        return normalizeAll(t1, t2, tFallback);
    }

    /**
     * 种子 description 里的作品别名（归一化后），已排除与 {@code known} 重复的项。
     * <p>
     * 国内站发布的日本动画常用罗马音命名，而 TMDb 给订阅的三个标题（中文名/原语言名/英文名）
     * 里没有罗马音这一种，两边在标题这一步就对不上——能把它们对上的那个名字一直摆在
     * description 的别名列表里。抽取规则与其中的取舍见 {@link DescriptionAliases}。
     * </p>
     * <p>
     * 排除 {@code known} 不只是省一遍循环：剩下的集合为空就意味着「别名没带来任何新信息」，
     * 第二轮必然与第一轮同样落空，直接短路掉。
     * </p>
     */
    Set<String> descriptionAliases(TorrentInfo torrent, Set<String> known) {
        Set<String> aliases = new LinkedHashSet<>();
        for (String alias : DescriptionAliases.parse(torrent.getDescription())) {
            String normalized = normalize(alias);
            if (normalized != null && !known.contains(normalized)) {
                aliases.add(normalized);
            }
        }
        return aliases;
    }

    private MatchResult matchEpisode(TorrentInfo torrent, PtSubscriptionPlus sub, AbsoluteEpisodeMap absolutes) {
        // 判断电影只看 media_type：剧集的特别篇在 TMDb 里也是第 0 季，用 season==0 判断会串台
        if (TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            // 带季集信息的一定是剧集，不该匹配电影订阅
            if (torrent.getParsedSeason() != null || torrent.getParsedEpisode() != null) {
                return null;
            }
            // 同名翻拍常见，年份不符宁可漏也不能串台
            if (!movieYearMatches(sub.getYear(), torrent.getParsedYear())) {
                return null;
            }
            return new MatchResult(sub, MOVIE_EPISODE);
        }

        // 同名剧集串台的唯一防线：标题全等 + 季号相等两道判据对《人生复本》(Dark Matter 2024)
        // 与《暗物质》(Dark Matter 2016) 这类同名剧<b>完全无效</b>——两部剧的英文名逐字相同，
        // 都有第 2 季，于是 2016 那部的 S02 季包被 2024 这部的 S02 订阅认领，一路推到下载器，
        // 而日志里每一步都"正常"。这里补上年份这一维，判据见 seriesYearPlausible
        if (!seriesYearPlausible(sub.getYear(), torrent.getParsedYear())) {
            return null;
        }

        if (torrent.getParsedSeason() == null || sub.getSeason() == null
                || !torrent.getParsedSeason().equals(sub.getSeason())) {
            // 季号对不上不代表不是这部剧的资源：长篇动画按绝对号发布时季号恒为 1
            // （One Piece S01E1173 其实是第 23 季第 18 集）
            return matchByAbsolute(torrent, sub, absolutes);
        }
        // 有季无集 = 季包
        if (torrent.getParsedEpisode() == null) {
            return new MatchResult(sub, SEASON_PACK);
        }
        // 集数区间（如 S01E01-03）：区间结尾必须严格大于起始集号才算区间，否则按单集处理
        Integer episodeEnd = torrent.getParsedEpisodeEnd();
        if (episodeEnd != null && episodeEnd > torrent.getParsedEpisode()) {
            return new MatchResult(sub, torrent.getParsedEpisode(), episodeEnd);
        }
        return new MatchResult(sub, torrent.getParsedEpisode());
    }

    /**
     * 绝对集号兜底匹配。标题已经确认是这部剧，这里只解决「集号用的不是同一套编号」。
     * <p>
     * 判据整套在 {@link AbsoluteEpisodeMap#toLocalRange}——三个约束、区间两端的处理、
     * 季包不参与，全在那里，<b>不要在这里另写一份</b>：搜索补集侧曾各写一份且只认单集，
     * 于是同一个绝对号区间在两条链路上给出相反结论。本方法只负责把结果包成 {@link MatchResult}。
     * </p>
     */
    private MatchResult matchByAbsolute(TorrentInfo torrent, PtSubscriptionPlus sub, AbsoluteEpisodeMap absolutes) {
        if (absolutes == null) {
            return null;
        }
        AbsoluteEpisodeMap.LocalRange range = absolutes.toLocalRange(
                torrent.getParsedSeason(), torrent.getParsedEpisode(), torrent.getParsedEpisodeEnd());
        if (range == null) {
            return null;
        }
        return range.isRange() ? new MatchResult(sub, range.start(), range.end())
                : new MatchResult(sub, range.start());
    }

    /**
     * 电影年份容差（年）。取 1 而不是 0：同一部电影的「年份」在不同来源本就可能差一年——
     * 电影节首映年 vs 正式公映年、年末上映跨年、TMDb 记的是首映地上映日而发布组按本地上映年标注。
     * 严格相等会把这些完全正确的候选整条淘汰，而这一类占比不低。
     * <p>
     * 不放宽到 2 及以上：容差每放宽一年，同名翻拍被串台的风险就实打实地增加一分，
     * 而「正好差两年」的同一部电影几乎不存在。这个取值也与
     * {@code TMDbClient#scoreCandidate} 里「差 1 年仍给正分、差更多开始扣分」的口径一致。
     * </p>
     */
    static final int MOVIE_YEAR_TOLERANCE = 1;

    /**
     * 电影候选的年份是否可接受：允许 {@link #MOVIE_YEAR_TOLERANCE} 年以内的偏差。
     * <p>
     * 任一侧缺失年份一律判为不匹配——电影没有季集号可供交叉验证，年份是唯一能把同名作品
     * 区分开的信号，判不出来时宁可漏也不能串台（这一点相对严格相等的旧实现没有放宽）。
     * </p>
     * <p>
     * 包内可见供 {@link SearchSupplementService#filterMovieCandidates} 复用：RSS 自动匹配与
     * 搜索补集两条链路对「这个候选是不是这部电影」必须给出同一个答案，各写一份迟早漂移
     * （标题归一化 {@link #normalizeAll} 共用同一份也是这个理由）。
     * </p>
     *
     * @param subYear     订阅记录的年份
     * @param torrentYear 从种子标题解析出的年份
     */
    boolean movieYearMatches(String subYear, String torrentYear) {
        if (StringUtils.isBlank(subYear) || StringUtils.isBlank(torrentYear)) {
            return false;
        }
        String sub = subYear.trim();
        String torrent = torrentYear.trim();
        if (sub.equals(torrent)) {
            return true;
        }
        try {
            return Math.abs(Integer.parseInt(torrent) - Integer.parseInt(sub)) <= MOVIE_YEAR_TOLERANCE;
        } catch (NumberFormatException e) {
            // 解析不出数字就没有「相差几年」可言，字符串相等在上面已经判过，走到这里必然是不匹配
            return false;
        }
    }

    /**
     * 剧集年份的<b>向前</b>容差（年）。见 {@link #seriesYearPlausible} 对「为什么只有一个方向」的推导。
     * <p>
     * 取 1 是为了吸收「首播日跨年」这一类真实偏差：TMDb 记的 {@code first_air_date} 是
     * 2025-01-02，而发布组按开播前的宣发年标了 2024。差 2 年及以上早于首播年，对剧集而言
     * 没有合理解释，多半是解析错了年份或者根本不是这部剧。
     * </p>
     */
    static final int SERIES_YEAR_BACKWARD_TOLERANCE = 1;

    /**
     * 剧集候选的年份是否讲得通：种子年份<b>明显早于</b>订阅首播年即判定不是这部剧。
     * <p>
     * <b>这条判据是单向的，这正是它能成立的原因。</b>订阅的 {@code year} 来自
     * {@code first_air_date}，是<b>整部剧的首播年</b>，与订阅的是第几季无关
     * （{@code SubscriptionService#subscribe} 写入）；而发布组在剧集种子上标的通常是
     * <b>本季的播出年</b>。后者只可能晚于或等于前者——第 2 季不会在第 1 季之前播出。
     * 于是「种子年份比首播年早很多」是一个确定的错误信号，而「晚很多」完全正常
     * （长寿剧的第 N 季可以晚首播年十几二十年），一律放行。
     * </p>
     * <p>
     * 与电影侧的 {@link #movieYearMatches} 有两处刻意的不同，不要"统一"掉：
     * </p>
     * <ol>
     *   <li><b>电影是双向 ±1，剧集只卡向前一侧。</b>电影的年份两侧说的是同一件事
     *       （这部片子哪年上映），偏差只可能来自口径差异；剧集两侧说的是不同的事
     *       （整部剧首播年 vs 本季播出年），向后的差距是语义本身，不是误差。</li>
     *   <li><b>电影任一侧缺年份即判不匹配，剧集缺失一律放行。</b>电影没有季集号可交叉验证，
     *       年份是唯一能区分同名作品的信号，判不出来只能宁可漏；剧集还有季号和集号两道判据，
     *       而剧集种子不标年份相当常见（{@code Some.Show.S02E05.1080p.WEB-DL} 是最常见的命名），
     *       缺失即淘汰会把一大批完全正确的候选整批清掉——那是比串台更频繁的故障。</li>
     * </ol>
     * <p>
     * 包内可见供 {@code SearchSupplementService} 的三个候选过滤器复用：RSS 自动匹配与搜索补集
     * 对「这个候选是不是这部剧」必须给出同一个答案，各写一份迟早漂移（理由同
     * {@link #movieYearMatches} 与 {@link #normalizeAll}）。
     * </p>
     *
     * @param subYear     订阅记录的年份（整部剧首播年）
     * @param torrentYear 从种子标题解析出的年份
     * @return 年份讲得通、或任一侧判不出年份时返回 true
     */
    boolean seriesYearPlausible(String subYear, String torrentYear) {
        if (StringUtils.isBlank(subYear) || StringUtils.isBlank(torrentYear)) {
            return true;
        }
        try {
            return Integer.parseInt(torrentYear.trim())
                    >= Integer.parseInt(subYear.trim()) - SERIES_YEAR_BACKWARD_TOLERANCE;
        } catch (NumberFormatException e) {
            // 解析不出数字就没有「早了几年」可言，与缺失同等对待：放行，交给季集号去判
            return true;
        }
    }

    /**
     * 标题归一化，实现收口在 {@link TitleNormalizer}——与 TMDb 刮削侧
     * （{@code TMDbClient#titleMatchLevel}）共用同一份字符类。
     * <p>
     * <b>不要在这里另写一份。</b>历史上本方法只处理 {@code . _ -} 与三个全角字符，
     * 而刮削侧剥掉了全部标点，于是《神探夏洛克：可恶的新娘》这类带标点的作品在刮削侧能匹配、
     * 在订阅匹配侧却因为一个全角冒号被漏搜，同一部作品两条链路给出相反结论。
     * </p>
     * <p>
     * 归一化后本类做<b>全等</b>比较而非子串包含——否则「The Office」会吃掉「The Office US」的种子。
     * 这一点与刮削侧不同：刮削侧允许「长包含短」是因为它只用来决定「证据够不够采纳」，
     * 而这里的结论直接决定往下载器推哪个种子，推错就是下错内容。
     * </p>
     */
    private String normalize(String title) {
        return TitleNormalizer.normalizeForCompare(title);
    }

    /**
     * 把多个原始标题（中文/英文）各自归一化后收进集合，null/空串归一化结果被丢弃。
     * <p>
     * 种子候选标题与订阅候选标题各自求出这样一个集合，两个集合有交集即视为标题匹配——
     * 中英双标题任一命中即可，天然规避子串包含误匹配（求交集要求归一化后完全相等）。
     * </p>
     * 包内可见：{@link SearchSupplementService} 复用同一套归一化规则校验电影候选标题，
     * 避免两条链路各写一份、标准不一致。
     */
    Set<String> normalizeAll(String... titles) {
        Set<String> result = new LinkedHashSet<>();
        for (String title : titles) {
            String normalized = normalize(title);
            if (normalized != null) {
                result.add(normalized);
            }
        }
        return result;
    }
}
