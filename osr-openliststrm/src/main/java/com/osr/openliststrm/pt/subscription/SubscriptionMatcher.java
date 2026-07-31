package com.osr.openliststrm.pt.subscription;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.dto.MatchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
        // parsedTitle/parsedTitleEn 都解析失败（特殊命名格式）时回退到种子原始标题，避免漏判；
        // 与 SearchSupplementService#titleMatches 的兜底策略保持一致，否则会出现
        // RSS 自动匹配漏掉、手动搜索补集却能补回来的不一致体验。
        String t1 = torrent.getParsedTitle();
        String t2 = torrent.getParsedTitleEn();
        String tFallback = (t1 == null && t2 == null) ? torrent.getTitle() : null;
        Set<String> torrentTitles = normalizeAll(t1, t2, tFallback);
        if (torrentTitles.isEmpty()) {
            return null;
        }
        for (PtSubscriptionPlus sub : subscriptions) {
            Set<String> subTitles = normalizeAll(sub.getTitle(), sub.getOriginalTitle(), sub.getEnglishTitle());
            if (Collections.disjoint(torrentTitles, subTitles)) {
                continue;
            }
            MatchResult result = matchEpisode(torrent, sub);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private MatchResult matchEpisode(TorrentInfo torrent, PtSubscriptionPlus sub) {
        // 判断电影只看 media_type：剧集的特别篇在 TMDb 里也是第 0 季，用 season==0 判断会串台
        if (TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            // 带季集信息的一定是剧集，不该匹配电影订阅
            if (torrent.getParsedSeason() != null || torrent.getParsedEpisode() != null) {
                return null;
            }
            // 同名翻拍常见，年份不符宁可漏也不能串台
            if (StringUtils.isBlank(torrent.getParsedYear()) || StringUtils.isBlank(sub.getYear())
                    || !torrent.getParsedYear().equals(sub.getYear())) {
                return null;
            }
            return new MatchResult(sub, MOVIE_EPISODE);
        }

        if (torrent.getParsedSeason() == null || sub.getSeason() == null
                || !torrent.getParsedSeason().equals(sub.getSeason())) {
            return null;
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
     * 标题归一化：转小写、全角空格/连字符/句号转半角、把点/下划线/连字符/连续空白压成单空格、去首尾空白。
     * <p>
     * 归一化后做<b>全等</b>比较而非子串包含——否则「The Office」会吃掉「The Office US」的种子。
     * </p>
     * <p>
     * 日剧/韩剧标题常混用全角空格（U+3000）、全角连字符（－）、全角句号（．），Java 正则的 {@code \s}
     * 不识别全角空格，不预先转换会导致订阅标题与种子标题归一化后不再逐字符相等，本该匹配的候选被漏判。
     * </p>
     */
    private String normalize(String title) {
        if (StringUtils.isBlank(title)) {
            return null;
        }
        String normalized = title.toLowerCase(Locale.ROOT)
                .replace('　', ' ')
                .replace('－', '-')
                .replace('．', '.')
                .replaceAll("[._\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized.isEmpty() ? null : normalized;
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
