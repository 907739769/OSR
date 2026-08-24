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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

        for (String query : queryVariants(item.getTitle())) {
            PopularItem matched = searchAndMatch(apiKey, type, query, item.getYear(), mediaType);
            if (matched != null) {
                apply(item, matched);
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
     * 查询词：整串优先，整串搜不到时再拿切出来的中文段/拉丁段各试一次。
     * <p>
     * 豆瓣条目标题存在「中文名 English Name」拼接的写法，整串拿去搜 TMDb 必然一条都对不上。
     * <b>早停是有意的</b>：整串命中就不再发后两个请求，常见情况下每个候选只打一次 TMDb。
     * </p>
     */
    static List<String> queryVariants(String title) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(title.trim());
        String cjk = longestMatch(CJK_SEGMENT, title);
        String latin = longestMatch(LATIN_SEGMENT, title);
        // 只有两种文字并存时分段才有意义：纯中文标题切出来的 CJK 段就是它自己，白打一次请求
        if (cjk != null && latin != null) {
            variants.add(cjk);
            variants.add(latin);
        }
        List<String> result = new ArrayList<>();
        for (String variant : variants) {
            if (TitleNormalizer.normalizeForCompare(variant) != null) {
                result.add(variant);
            }
        }
        return result;
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
