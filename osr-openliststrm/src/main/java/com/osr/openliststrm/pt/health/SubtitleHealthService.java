package com.osr.openliststrm.pt.health;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.health.SubtitleDetector.SubtitleStatus;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 字幕体检：已入库的集里，下载它的那个种子没识别到中文字幕的。只读、不打外部请求。
 * <p>
 * 与缺集体检同属 {@code pt/health/} 的只读诊断层，但口径独立：缺集体检看「还缺哪些集」，
 * 这里看「已经有的集能不能看」。两者不合并成一张表——处置方向完全不同（一个去搜资源，一个去换版本/补字幕）。
 * <p>
 * 只认<b>经 OSR 下载入库</b>的集（{@code download_id} 非空）：订阅建立前就在媒体库里的集不知道是哪个种子来的，
 * 猜不出字幕情况，宁可不报。
 *
 * @author Jack
 */
@Service
public class SubtitleHealthService {

    /** IN 子句一次放多少个下载记录 id */
    private static final int CHUNK = 500;

    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final IPtDownloadRecordPlusService recordService;

    public SubtitleHealthService(IPtSubscriptionPlusService subscriptionService,
                                 IPtSubscriptionEpisodePlusService episodeService,
                                 IPtDownloadRecordPlusService recordService) {
        this.subscriptionService = subscriptionService;
        this.episodeService = episodeService;
        this.recordService = recordService;
    }

    /**
     * @param accessible 当前用户能看哪些订阅，口径同缺集体检
     */
    public List<SubtitleIssue> report(Predicate<PtSubscriptionPlus> accessible) {
        Map<Integer, PtSubscriptionPlus> subs = subscriptionService.list().stream()
                .filter(accessible)
                .filter(sub -> !isChineseOrigin(sub))
                .collect(Collectors.toMap(PtSubscriptionPlus::getId, s -> s, (a, b) -> a));
        if (subs.isEmpty()) {
            return List.of();
        }
        List<PtSubscriptionEpisodePlus> episodes = episodeService.list(new LambdaQueryWrapper<PtSubscriptionEpisodePlus>()
                .in(PtSubscriptionEpisodePlus::getSubId, subs.keySet())
                .in(PtSubscriptionEpisodePlus::getState, SubscriptionEpisodeState.IN_LIBRARY.value(),
                        SubscriptionEpisodeState.UPGRADING.value())
                .isNotNull(PtSubscriptionEpisodePlus::getDownloadId));
        Map<Integer, PtDownloadRecordPlus> records = loadRecords(episodes.stream()
                .map(PtSubscriptionEpisodePlus::getDownloadId).filter(Objects::nonNull).distinct().toList());

        Map<Integer, Builder> bySub = new TreeMap<>();
        for (PtSubscriptionEpisodePlus ep : episodes) {
            PtDownloadRecordPlus record = records.get(ep.getDownloadId());
            if (record == null) {
                continue;
            }
            SubtitleStatus status = SubtitleStatus.of(record.getSubtitle());
            boolean titleOnly = status == null;
            if (titleOnly) {
                // 完成于字幕识别上线之前：拿不到文件列表了，只能按标题判
                status = SubtitleDetector.detect(record.getTitle(), null, List.of());
            }
            if (status == SubtitleStatus.ZH) {
                continue;
            }
            Builder b = bySub.computeIfAbsent(ep.getSubId(), id -> new Builder(subs.get(id)));
            (status == SubtitleStatus.OTHER ? b.unknownLanguage : b.noChinese).add(ep.getEpisode());
            b.titleOnly |= titleOnly;
        }
        return bySub.values().stream()
                .map(Builder::build)
                .sorted(Comparator.comparingInt((SubtitleIssue i) -> i.noChinese().size() + i.unknownLanguage().size())
                        .reversed())
                .toList();
    }

    /**
     * 华语作品本来就不需要中文字幕，全部跳过。判据是原名里有汉字、且没有日文假名与韩文——
     * 日剧、日漫的原名也常含汉字，只看汉字会把它们一起放过去。原名缺失时不跳过（宁可多报）。
     */
    static boolean isChineseOrigin(PtSubscriptionPlus sub) {
        String name = sub.getOriginalTitle();
        if (StringUtils.isBlank(name)) {
            return false;
        }
        boolean han = false;
        for (int i = 0; i < name.length(); ) {
            int cp = name.codePointAt(i);
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                return false;
            }
            han |= script == Character.UnicodeScript.HAN;
            i += Character.charCount(cp);
        }
        return han;
    }

    /**
     * 只取判定要用的三列。投影<b>必须用 {@code QueryWrapper} 传列名</b>：{@code LambdaQueryWrapper#select}
     * 会立刻解析 MP 的实体 lambda 缓存，纯单测里那份缓存不存在（同 {@code SearchLogService#prune} 的注释）。
     */
    private Map<Integer, PtDownloadRecordPlus> loadRecords(List<Integer> ids) {
        Map<Integer, PtDownloadRecordPlus> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i += CHUNK) {
            recordService.list(new QueryWrapper<PtDownloadRecordPlus>()
                            .select("id", "title", "subtitle")
                            .in("id", ids.subList(i, Math.min(i + CHUNK, ids.size()))))
                    .forEach(r -> result.put(r.getId(), r));
        }
        return result;
    }

    private static final class Builder {
        private final PtSubscriptionPlus sub;
        private final List<Integer> noChinese = new ArrayList<>();
        private final List<Integer> unknownLanguage = new ArrayList<>();
        private boolean titleOnly;

        Builder(PtSubscriptionPlus sub) {
            this.sub = sub;
        }

        SubtitleIssue build() {
            noChinese.sort(null);
            unknownLanguage.sort(null);
            boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType());
            return new SubtitleIssue(sub.getId(), sub.getTitle(), sub.getYear(), movie ? null : sub.getSeason(),
                    sub.getMediaType(), sub.getTmdbId(), sub.getPosterPath(),
                    List.copyOf(noChinese), List.copyOf(unknownLanguage), titleOnly);
        }
    }

    /**
     * 一条订阅的字幕问题。
     *
     * @param noChinese       未识别到中文字幕的集（电影是 0）
     * @param unknownLanguage 有外挂字幕但认不出语言的集
     * @param titleOnly       其中有集的判断只按标题（下载完成于字幕识别上线之前）
     */
    public record SubtitleIssue(Integer subId, String title, String year, Integer season, String mediaType,
                                String tmdbId, String posterPath, List<Integer> noChinese,
                                List<Integer> unknownLanguage, boolean titleOnly) {
    }
}
