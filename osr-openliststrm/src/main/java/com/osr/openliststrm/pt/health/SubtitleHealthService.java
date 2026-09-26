package com.osr.openliststrm.pt.health;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.PtLogText;
import com.osr.openliststrm.pt.media.MediaServerClientFactory;
import com.osr.openliststrm.pt.media.MediaStreamInfo;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 字幕体检：已入库的集里，媒体服务器上查不到中文字幕流的。只读，点「检查」时才向媒体服务器取流信息。
 * <p>
 * 依据是媒体服务器解析出的<b>实际文件</b>里的字幕流（内封与外挂都算），不是种子标题——
 * 标题只能说「没写」，说不了「没有」；而文件里的流能，补完字幕刷新媒体库后再查一次也能确认问题消失。
 * <p>
 * 与缺集体检同属 {@code pt/health/} 的只读诊断层，但口径独立：缺集体检看「还缺哪些集」，
 * 这里看「已经有的集能不能看」，处置方向不同（一个去搜资源，一个去换版本/补字幕），不合并成一张表。
 *
 * @author Jack
 */
@Slf4j
@Service
public class SubtitleHealthService {

    /** 同时查几部作品。媒体服务器多在家用 NAS 上，每部只打一两个请求，并发不必高 */
    private static final int PARALLELISM = 4;

    /** 语言码认得出的中文：ISO 639-2（chi/zho）、639-1（zh 及 zh-CN 等）、方言（cmn 普通话、yue 粤语） */
    private static final Set<String> CHINESE_LANGUAGES = Set.of("chi", "zho", "zh", "chs", "cht", "cmn", "yue");

    /** 语言码缺失或是 und 时，靠轨道标题兜底。英文缩写两侧不能是字母，免得 Scene 这类词误中 */
    private static final Pattern CHINESE_TITLE = Pattern.compile(
            "(?<![a-z])(chs|cht|chinese|mandarin|cantonese)(?![a-z])|[简繁中粤]|國語|国语",
            Pattern.CASE_INSENSITIVE);

    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final IPtMediaServerPlusService mediaServerService;
    private final MediaServerClientFactory clientFactory;

    public SubtitleHealthService(IPtSubscriptionPlusService subscriptionService,
                                 IPtSubscriptionEpisodePlusService episodeService,
                                 IPtMediaServerPlusService mediaServerService,
                                 MediaServerClientFactory clientFactory) {
        this.subscriptionService = subscriptionService;
        this.episodeService = episodeService;
        this.mediaServerService = mediaServerService;
        this.clientFactory = clientFactory;
    }

    /** 一集的判定结果。声明顺序即「多份结果取哪个」的优先级：越靠前越可信 */
    enum Verdict {
        /** 有中文字幕流 */
        CHINESE_SUBTITLE,
        /** 有中文音轨（国配/粤配），不需要字幕 */
        CHINESE_AUDIO,
        /** 有字幕流但认不出语言 */
        UNKNOWN_LANGUAGE,
        /** 解析过，没有中文字幕流 */
        NO_CHINESE,
        /** 服务器没解析过这个文件的媒体信息，判断不了 */
        NO_MEDIA_INFO
    }

    /**
     * @param accessible 当前用户能看哪些订阅，口径同缺集体检
     */
    public SubtitleReport report(Predicate<PtSubscriptionPlus> accessible) {
        long start = System.currentTimeMillis();
        List<PtMediaServerPlus> servers = mediaServerService.listActive();
        if (servers.isEmpty()) {
            return SubtitleReport.noServer();
        }
        Map<Integer, PtSubscriptionPlus> subs = subscriptionService.list().stream()
                .filter(accessible)
                .filter(sub -> !isChineseOrigin(sub))
                .filter(sub -> StringUtils.isNotBlank(sub.getTmdbId()))
                .collect(Collectors.toMap(PtSubscriptionPlus::getId, s -> s, (a, b) -> a));
        if (subs.isEmpty()) {
            return new SubtitleReport(List.of(), 0, 0, List.of(), false);
        }
        Map<Integer, List<Integer>> episodesBySub = episodeService.list(new LambdaQueryWrapper<PtSubscriptionEpisodePlus>()
                        .in(PtSubscriptionEpisodePlus::getSubId, subs.keySet())
                        .in(PtSubscriptionEpisodePlus::getState, SubscriptionEpisodeState.IN_LIBRARY.value(),
                                SubscriptionEpisodeState.UPGRADING.value()))
                .stream()
                .collect(Collectors.groupingBy(PtSubscriptionEpisodePlus::getSubId, LinkedHashMap::new,
                        Collectors.mapping(PtSubscriptionEpisodePlus::getEpisode, Collectors.toList())));

        Map<String, ServerFailure> failures = new ConcurrentHashMap<>();
        Map<Integer, Future<SubtitleIssue>> futures = new LinkedHashMap<>();
        AtomicBoolean anySupported = new AtomicBoolean();
        try (ExecutorService pool = Executors.newFixedThreadPool(PARALLELISM, Thread.ofVirtual().factory())) {
            episodesBySub.forEach((subId, episodes) -> futures.put(subId, pool.submit(Threads.wrapCallable(
                    () -> check(subs.get(subId), episodes, servers, failures, anySupported)))));
        }
        List<SubtitleIssue> issues = new ArrayList<>();
        int checkedEpisodes = 0;
        for (Map.Entry<Integer, Future<SubtitleIssue>> entry : futures.entrySet()) {
            checkedEpisodes += episodesBySub.get(entry.getKey()).size();
            try {
                SubtitleIssue issue = entry.getValue().get();
                if (issue != null) {
                    issues.add(issue);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException e) {
                log.warn("字幕体检：{} 检查出错：{}", PtLogText.subject(subs.get(entry.getKey())),
                        e.getCause().getMessage(), e.getCause());
            }
        }
        issues.sort(Comparator.comparingInt(SubtitleIssue::problemCount).reversed());

        List<String> failureTexts = failures.values().stream().map(ServerFailure::describe).toList();
        failures.values().forEach(f -> log.warn("字幕体检：媒体服务器[{}]有 {} 部作品查询失败，首个原因：{}",
                f.server, f.count, f.firstError.getMessage(), f.firstError));
        log.info("字幕体检完成：检查 {} 部 / {} 集，有问题 {} 部，耗时 {}ms", futures.size(), checkedEpisodes,
                issues.size(), System.currentTimeMillis() - start);
        return new SubtitleReport(issues, futures.size(), checkedEpisodes, failureTexts,
                !anySupported.get() && failures.isEmpty());
    }

    /**
     * 查一部作品：每台服务器各取一次流信息，同一集取各台里最可信的那份结果。
     * 一台查不通只记失败、继续查下一台，与对账「任一台命中即算」同一个取向。
     */
    private SubtitleIssue check(PtSubscriptionPlus sub, List<Integer> episodes, List<PtMediaServerPlus> servers,
                                Map<String, ServerFailure> failures, AtomicBoolean anySupported) {
        boolean movie = isMovie(sub);
        Map<Integer, Verdict> verdicts = new HashMap<>();
        for (PtMediaServerPlus server : servers) {
            Map<Integer, MediaStreamInfo> infos;
            try {
                infos = clientFactory.get(server).listStreamInfo(server, sub.getTmdbId(),
                        movie ? null : sub.getSeason(), movie);
            } catch (Exception e) {
                failures.computeIfAbsent(server.getName(), ServerFailure::new).record(e);
                continue;
            }
            if (infos == null) {
                continue;
            }
            anySupported.set(true);
            for (Integer no : episodes) {
                MediaStreamInfo info = infos.get(no);
                if (info != null) {
                    verdicts.merge(no, classify(info), (a, b) -> a.compareTo(b) <= 0 ? a : b);
                }
            }
        }
        Map<Verdict, List<Integer>> byVerdict = new HashMap<>();
        verdicts.forEach((no, v) -> byVerdict.computeIfAbsent(v, k -> new ArrayList<>()).add(no));
        List<Integer> noChinese = sorted(byVerdict.get(Verdict.NO_CHINESE));
        List<Integer> unknown = sorted(byVerdict.get(Verdict.UNKNOWN_LANGUAGE));
        List<Integer> noInfo = sorted(byVerdict.get(Verdict.NO_MEDIA_INFO));
        if (noChinese.isEmpty() && unknown.isEmpty() && noInfo.isEmpty()) {
            return null;
        }
        return new SubtitleIssue(sub.getId(), sub.getTitle(), sub.getYear(), movie ? null : sub.getSeason(),
                sub.getMediaType(), sub.getTmdbId(), sub.getPosterPath(), noChinese, unknown, noInfo);
    }

    static Verdict classify(MediaStreamInfo info) {
        if (!info.probed()) {
            return Verdict.NO_MEDIA_INFO;
        }
        boolean unknown = false;
        for (MediaStreamInfo.SubtitleTrack track : info.subtitles()) {
            if (isChineseLanguage(track.language()) || isChineseTitle(track.title())) {
                return Verdict.CHINESE_SUBTITLE;
            }
            unknown |= isUnknownLanguage(track.language());
        }
        if (info.audioLanguages().stream().anyMatch(SubtitleHealthService::isChineseLanguage)) {
            return Verdict.CHINESE_AUDIO;
        }
        return unknown ? Verdict.UNKNOWN_LANGUAGE : Verdict.NO_CHINESE;
    }

    static boolean isChineseLanguage(String language) {
        if (StringUtils.isBlank(language)) {
            return false;
        }
        String lang = language.trim().toLowerCase(Locale.ROOT);
        return CHINESE_LANGUAGES.contains(lang) || lang.startsWith("zh-") || lang.startsWith("zh_");
    }

    private static boolean isChineseTitle(String title) {
        return StringUtils.isNotBlank(title) && CHINESE_TITLE.matcher(title).find();
    }

    private static boolean isUnknownLanguage(String language) {
        return StringUtils.isBlank(language) || "und".equalsIgnoreCase(language) || "unknown".equalsIgnoreCase(language);
    }

    /**
     * 华语作品本来就不需要中文字幕，整部跳过，也省掉一轮请求。判据是原名里有汉字、且没有日文假名与韩文——
     * 日剧、日漫的原名也常含汉字，只看汉字会把它们一起放过去。原名缺失时不跳过（宁可多报）；
     * 这时还有「中文音轨」那道判据兜着。
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

    private static boolean isMovie(PtSubscriptionPlus sub) {
        return SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType());
    }

    private static List<Integer> sorted(List<Integer> list) {
        if (list == null) {
            return List.of();
        }
        list.sort(null);
        return List.copyOf(list);
    }

    /** 同一台服务器的失败聚成一条：几十部作品各报一句「连接超时」没有信息量 */
    private static final class ServerFailure {
        private final String server;
        private int count;
        private Exception firstError;

        ServerFailure(String server) {
            this.server = server;
        }

        synchronized void record(Exception e) {
            if (firstError == null) {
                firstError = e;
            }
            count++;
        }

        String describe() {
            String reason = StringUtils.defaultIfBlank(firstError.getMessage(), firstError.getClass().getSimpleName());
            return server + "：" + count + " 部作品查询失败（" + reason + "）";
        }
    }

    /**
     * @param issues              有问题的作品，问题集数多的在前
     * @param checkedSubscriptions 实际查了几部（华语作品、没有已入库集的不算）
     * @param failures            各台服务器的查询失败摘要
     * @param unsupported         启用中的服务器没有一台支持查字幕流（没配、或全是 Plex）
     */
    public record SubtitleReport(List<SubtitleIssue> issues, int checkedSubscriptions, int checkedEpisodes,
                                 List<String> failures, boolean unsupported) {
        static SubtitleReport noServer() {
            return new SubtitleReport(List.of(), 0, 0, List.of(), true);
        }
    }

    /**
     * 一条订阅的字幕问题。
     *
     * @param noChinese       没有中文字幕流的集（电影是 0）
     * @param unknownLanguage 有字幕流但认不出语言的集
     * @param noMediaInfo     媒体服务器没解析过媒体信息、判断不了的集
     */
    public record SubtitleIssue(Integer subId, String title, String year, Integer season, String mediaType,
                                String tmdbId, String posterPath, List<Integer> noChinese,
                                List<Integer> unknownLanguage, List<Integer> noMediaInfo) {
        int problemCount() {
            return noChinese.size() + unknownLanguage.size();
        }
    }
}
