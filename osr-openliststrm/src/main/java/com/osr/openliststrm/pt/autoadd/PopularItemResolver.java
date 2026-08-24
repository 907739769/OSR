package com.osr.openliststrm.pt.autoadd;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.pt.autoadd.source.PopularItem;
import com.osr.openliststrm.pt.autoadd.source.TmdbItemMapper;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.rename.TitleNormalizer;
import com.osr.openliststrm.tmdb.TMDbApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把只有标题的候选（豆瓣源）补全成带 tmdbId 的候选：按标题搜 TMDb，按<b>严格判据</b>采纳。
 * <p>
 * <b>这里是整个豆瓣数据源唯一会造成实际损失的地方。</b>误匹配的后果不是"没订上"，而是订错剧
 * 并真的开始占用索引器与下载器去下一部用户没要的作品，而日志里每一步看着都正常。因此判据取
 * 「归一化标题<b>全等</b>」而不是包含或相似度——与 TMDb 刮削侧「全等命中自成一档」同一条取向，
 * 宁可漏也不可错。漏掉的会以 {@code SKIPPED_NO_MATCH} 落进执行日志，用户看得见、能手动补订；
 * 错订的没有任何一层能发现。
 * </p>
 * <p>
 * <b>顺带把过滤字段一并补上</b>：TMDb 的 search 结果里本就带 genre_ids / vote_average /
 * vote_count，映射走与榜单源共用的 {@link TmdbItemMapper}。于是规则上那三个过滤器对豆瓣源
 * 照常生效，且口径与 TMDb 源完全一致——不会出现同一个「最低评分 7」在两个源里含义不同。
 * 豆瓣自己的评分刻意不用：它是另一套口径，混进来只会让那个数字失去意义。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class PopularItemResolver {

    /**
     * 每个查询词最多检验前几个候选。TMDb search 按 popularity 倒序，正确答案不在前 10
     * 却又能通过标题全等检验的情况可以忽略；放开只会让长尾里的同名作品有更多机会撞上。
     */
    private static final int MAX_CANDIDATES_EXAMINED = 10;

    /**
     * 年份允许的偏差。豆瓣与 TMDb 都记首播年，但跨年播出的剧两边可能各记一年，差 1 是常态；
     * 放到 2 就会开始吃进相邻年份的同名重拍版。
     */
    private static final int YEAR_TOLERANCE = 1;

    /** 最长的中日韩连续块，用于从「中文名 English Name」里切出中文名 */
    private static final Pattern CJK_SEGMENT = Pattern.compile(
            "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]"
                    + "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}0-9\u00B7\u30FB：:！!？?…、\\s]*");

    /** 「片名 + 尾部裸数字」，如「问心2」「速度与激情 9」。数字与片名之间的空格可有可无 */
    private static final Pattern BARE_SEASON = Pattern.compile("(.*?)\\s*([0-9０-９]{1,2})\\s*$");

    /** 裸数字季号的上界，与 {@code SeasonSuffix} 保持一致 */
    private static final int MAX_BARE_SEASON = 99;

    /** 最长的拉丁连续块，用于切出英文名 */
    private static final Pattern LATIN_SEGMENT = Pattern.compile("[A-Za-z][A-Za-z0-9'&:!?.,\\-\\s]*");

    @Autowired
    private TMDbApiService tmDbApiService;

    @Autowired
    private OpenlistConfig openlistConfig;

    /**
     * 原地补全 item 的 tmdbId 与过滤所需字段。
     * <p>
     * 成功时 item 的 title/year 会被换成 TMDb 侧的值（那才是订阅里将要显示的名字），
     * doubanId / sourceUrl 保持不变以便回查。
     * </p>
     *
     * @param mediaType 规则上配置的 TV / MOVIE，决定搜哪一边
     * @return null 表示补全成功；否则返回可直接写进执行日志的失败原因
     */
    public String resolve(PopularItem item, String mediaType) {
        String apiKey = openlistConfig.getTmdbApiKey();
        if (StringUtils.isBlank(apiKey)) {
            return "TMDb API Key 未配置";
        }
        if (StringUtils.isBlank(item.getTitle())) {
            return "候选条目没有标题";
        }
        boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(mediaType);
        String type = movie ? "movie" : "tv";

        for (QueryVariant variant : queryVariants(item.getTitle(), movie)) {
            PopularItem matched = searchAndMatch(apiKey, type, variant.query(), item.getYear(), mediaType);
            if (matched != null) {
                apply(item, matched);
                // 靠「剥掉尾部裸数字」才命中的，那个数字就是季号。来源已经给出季号时不覆盖
                // （DoubanRssParser 解析「第九季」得到的值更确定）
                if (item.getSeasonNumber() == null && variant.season() != null) {
                    item.setSeasonNumber(variant.season());
                }
                return null;
            }
        }
        return "TMDb 未搜到标题一致的" + (movie ? "电影" : "剧集");
    }

    /**
     * 搜一次并按严格判据挑选。
     * <p>
     * <b>年份不传给 TMDb，只在本地校验。</b>传过去是精确过滤，两边年份差 1 时结果直接为空，
     * 而差 1 恰恰是跨年剧的常态；留在本地判才能给出 {@link #YEAR_TOLERANCE} 的余量。
     * </p>
     */
    private PopularItem searchAndMatch(String apiKey, String type, String query, String expectYear, String mediaType) {
        JSONArray results;
        try {
            JSONObject json = JSONObject.parseObject(tmDbApiService.search(apiKey, type, query, null));
            results = json == null ? null : json.getJSONArray("results");
        } catch (Exception e) {
            log.warn("按标题[{}]搜 TMDb 失败：{}", query, e.getMessage(), e);
            return null;
        }
        if (results == null || results.isEmpty()) {
            return null;
        }
        String normalizedQuery = TitleNormalizer.normalizeForCompare(query);
        if (normalizedQuery == null) {
            return null;
        }
        int examined = Math.min(results.size(), MAX_CANDIDATES_EXAMINED);
        for (int i = 0; i < examined; i++) {
            PopularItem candidate = TmdbItemMapper.toItem(results.getJSONObject(i), mediaType);
            if (titleEquals(candidate, normalizedQuery) && yearAcceptable(expectYear, candidate.getYear())) {
                return candidate;
            }
        }
        return null;
    }

    /** 候选的中文名与原始语言名任一与查询词归一化后全等即可——中文作品的 name 是中文、original_name 也是中文，日番则一中一日 */
    private boolean titleEquals(PopularItem candidate, String normalizedQuery) {
        return normalizedQuery.equals(TitleNormalizer.normalizeForCompare(candidate.getTitle()))
                || normalizedQuery.equals(TitleNormalizer.normalizeForCompare(candidate.getOriginalTitle()));
    }

    /**
     * 期望年份为空时不检验——RSSHub 的豆瓣路由多数不给年份，要求必须有年份等于把这个源废掉。
     * 期望年份存在而候选年份缺失时<b>拒绝</b>：能拿到一半信息却对不上，比两边都没有更可疑。
     */
    private boolean yearAcceptable(String expectYear, String candidateYear) {
        if (StringUtils.isBlank(expectYear)) {
            return true;
        }
        if (StringUtils.isBlank(candidateYear)) {
            return false;
        }
        try {
            return Math.abs(Integer.parseInt(expectYear) - Integer.parseInt(candidateYear)) <= YEAR_TOLERANCE;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 一个查询词，以及采用它时隐含的季号（只有「剥掉尾部裸数字」那个变体有值）。
     */
    record QueryVariant(String query, Integer season) {
    }

    /**
     * 查询词：整串优先，整串搜不到时再依次拿中文段/拉丁段、剥掉尾部裸数字的标题各试一次。
     * <p>
     * 豆瓣条目标题存在「中文名 English Name」拼接的写法，整串拿去搜 TMDb 必然一条都对不上。
     * <b>早停是有意的</b>：整串命中就不再发后面的请求，常见情况下每个候选只打一次 TMDb。
     * </p>
     */
    static List<QueryVariant> queryVariants(String title, boolean movie) {
        Map<String, Integer> variants = new LinkedHashMap<>();
        variants.put(title.trim(), null);
        String cjk = longestMatch(CJK_SEGMENT, title);
        String latin = longestMatch(LATIN_SEGMENT, title);
        // 只有两种文字并存时分段才有意义：纯中文标题切出来的 CJK 段就是它自己，白打一次请求
        if (cjk != null && latin != null) {
            variants.putIfAbsent(cjk, null);
            variants.putIfAbsent(latin, null);
        }
        BareSeason bare = movie ? null : parseBareSeason(title.trim());
        if (bare != null) {
            variants.putIfAbsent(bare.title(), bare.season());
        }
        List<QueryVariant> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : variants.entrySet()) {
            if (TitleNormalizer.normalizeForCompare(entry.getKey()) != null) {
                result.add(new QueryVariant(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    /** {@link #parseBareSeason} 的结果：剥掉尾部数字的标题 + 那个数字 */
    private record BareSeason(String title, Integer season) {
    }

    /**
     * 「片名 + 裸数字」写法里的季号，如豆瓣的「问心2」「庆余年2」。
     * <p>
     * <b>为什么不能像「第九季」那样直接剥掉，而必须做成排在最后的回退查询</b>：
     * 尾部裸数字究竟是季号还是<b>片名的一部分</b>，正则判不出来——「问心2」是《问心》第 2 季，
     * 而「速度与激情9」的 9 是片名自带的（TMDb 上的条目名就长这样）。两者形态完全一致。
     * 做成回退之后这个歧义就不需要判了：完整标题先搜，《速度与激情9》直接命中、根本走不到这里；
     * 只有完整标题搜不到时才试剥数字，而那正是「问心2」的处境。剥完仍然要过标题全等判定。
     * </p>
     * <p>
     * <b>为什么不放进 {@code SeasonSuffix}</b>：那个类是刮削链路与订阅链路共用的，
     * 而刮削侧是拿解析出的标题直接查 TMDb、没有「先试完整再试剥掉」这一层回退。
     * 把裸数字规则放进去，《速度与激情9》在刮削侧会被剥成《速度与激情》——一部不同的电影。
     * 这条规则只在「有回退兜底」的前提下才成立，因此只属于本类。
     * </p>
     * <p>
     * <b>电影不启用</b>（{@code movie} 为真时压根不调用）：电影续集在 TMDb 上的条目名通常就带
     * 数字，完整标题本就能命中；而剥数字对电影的失败方向是<b>订到前作</b>——「问心2」剥成「问心」
     * 若真匹配上一部同名电影，下的就是错的片子。剧集没有这个问题，季是作品下面的一层。
     * </p>
     *
     * @return 尾部没有裸数字、或剥完不合格时返回 null
     */
    private static BareSeason parseBareSeason(String title) {
        Matcher matcher = BARE_SEASON.matcher(title);
        if (!matcher.matches()) {
            return null;
        }
        String stripped = matcher.group(1).trim();
        // 剥完仍以数字结尾说明这串数字本来就是一个整体（「1917」会被切成「19」+「17」），
        // 长度不足 2 的残片同样不值得再打一次请求
        if (stripped.length() < 2 || Character.isDigit(stripped.charAt(stripped.length() - 1))) {
            return null;
        }
        int season = Integer.parseInt(toHalfWidthDigits(matcher.group(2)));
        // 季号 1 不产生新查询词（「问心1」剥成「问心」是对的，但第 1 季本就是默认值），
        // 上界与 SeasonSuffix 一致
        return season >= 2 && season <= MAX_BARE_SEASON ? new BareSeason(stripped, season) : null;
    }

    private static String toHalfWidthDigits(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            sb.append(c >= '０' && c <= '９' ? (char) (c - '０' + '0') : c);
        }
        return sb.toString();
    }

    /** 取最长的一段并 trim；长度不足 2 视为噪声（"Re:" 这类残片） */
    private static String longestMatch(Pattern pattern, String title) {
        Matcher matcher = pattern.matcher(title);
        String best = null;
        while (matcher.find()) {
            String candidate = matcher.group().trim();
            if (candidate.length() >= 2 && (best == null || candidate.length() > best.length())) {
                best = candidate;
            }
        }
        return best;
    }

    /** 把 TMDb 侧的字段盖到原候选上，保留来源侧标识 */
    private void apply(PopularItem item, PopularItem matched) {
        item.setTmdbId(matched.getTmdbId());
        item.setMediaType(matched.getMediaType());
        item.setTitle(matched.getTitle());
        item.setOriginalTitle(matched.getOriginalTitle());
        item.setYear(matched.getYear());
        item.setVoteAverage(matched.getVoteAverage());
        item.setVoteCount(matched.getVoteCount());
        item.setGenreIds(matched.getGenreIds());
        item.setPosterPath(matched.getPosterPath());
    }
}
