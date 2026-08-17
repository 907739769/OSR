package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.utils.Threads;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.notify.NotifyTarget;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.filter.EpisodeCountResolver;
import com.osr.openliststrm.pt.filter.FilterCriteria;
import com.osr.openliststrm.pt.filter.FilterCriteriaFactory;
import com.osr.openliststrm.pt.filter.SortDimension;
import com.osr.openliststrm.pt.filter.TorrentBlacklist;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.indexer.IndexerCapability;
import com.osr.openliststrm.pt.indexer.IndexerCapabilityCache;
import com.osr.openliststrm.pt.indexer.TorznabClient;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.dto.PushSelectedRequest;
import com.osr.openliststrm.pt.subscription.dto.SearchAndPushSummary;
import com.osr.openliststrm.pt.subscription.dto.SearchCandidateDTO;
import com.osr.openliststrm.pt.subscription.dto.SupplementResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 搜索补集编排：三级回退（ID 精确搜索 → 中文标题 → 英文/原语言标题）找候选，
 * 交给 {@link SubscriptionEngine} 走与 RSS 相同的过滤择优/占位/推送链路。
 * 职责边界同样终止于"把种子推给下载器"。
 *
 * @author Jack
 */
@Slf4j
@Service
public class SearchSupplementService {

    private final IPtIndexerPlusService indexerService;
    private final TorznabClient torznabClient;
    private final SubscriptionEngine subscriptionEngine;
    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final SubscriptionMatcher matcher;
    private final IndexerCapabilityCache capabilityCache;
    private final IPtFilterConfigPlusService filterConfigService;
    private final TorrentFilterEngine filterEngine;
    private final TmdbSearchService tmdbSearchService;
    private final IPtTorrentBlacklistPlusService blacklistService;
    /** 只用来取水位线与回读淘汰原因聚合，不参与落库——落库由 SubscriptionEngine 在过滤现场完成 */
    private final SearchLogService searchLogService;

    /**
     * 单次搜索调用内部的并发上限。
     * <p>
     * <b>这不是对站点的节流</b>——它是每次调用新建的，多个搜索同时进行时各自持有一份许可，
     * 真实并发是它的若干倍。真正的节流在 {@link com.osr.openliststrm.pt.indexer.IndexerRateLimiter}：
     * 全局单例，按索引器串行化并强制最小请求间隔，RSS 轮询与搜索共用同一份配额。
     * 这里保留本闸门只是为了限制单次调用同时在途的任务数量，不承担防封职责。
     * </p>
     */
    private final int maxConcurrency;

    public SearchSupplementService(IPtIndexerPlusService indexerService,
                                   TorznabClient torznabClient,
                                   SubscriptionEngine subscriptionEngine,
                                   IPtSubscriptionPlusService subscriptionService,
                                   IPtSubscriptionEpisodePlusService episodeService,
                                   SubscriptionMatcher matcher,
                                   IndexerCapabilityCache capabilityCache,
                                   IPtFilterConfigPlusService filterConfigService,
                                   TorrentFilterEngine filterEngine,
                                   TmdbSearchService tmdbSearchService,
                                   IPtTorrentBlacklistPlusService blacklistService,
                                   SearchLogService searchLogService,
                                   @Value("${pt.search.max-concurrency:3}") int maxConcurrency) {
        this.indexerService = indexerService;
        this.torznabClient = torznabClient;
        this.subscriptionEngine = subscriptionEngine;
        this.subscriptionService = subscriptionService;
        this.episodeService = episodeService;
        this.matcher = matcher;
        this.capabilityCache = capabilityCache;
        this.filterConfigService = filterConfigService;
        this.filterEngine = filterEngine;
        this.tmdbSearchService = tmdbSearchService;
        this.blacklistService = blacklistService;
        this.searchLogService = searchLogService;
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    /**
     * 搜索补集（自动推送模式，与旧调用兼容）。
     * 等价于 {@link #supplement(Integer, int, String, boolean)} 传 manualSelect=false。
     */
    public SupplementResult supplement(Integer subId, int episode, String keyword) {
        return supplement(subId, episode, keyword, false);
    }

    /**
     * 对指定订阅的指定目标（集号，或季包/电影的哨兵值）发起一次搜索补集。
     * <p>
     * 三级回退：ID 精确搜索（索引器支持时）→ 中文标题 → 英文/原语言标题，任一级过滤后有
     * 匹配就停止，不再尝试后面的级别；过滤标准（{@link #filterByTarget}）全程不变。
     * </p>
     * <p>
     * 当 {@code manualSelect} 为 true 时，不会自动推送最优结果，而是将所有候选种子
     * 以 DTO 形式返回，供前端展示让用户手动选择后再推送。
     * </p>
     *
     * @throws IllegalArgumentException 订阅不存在、订阅未在订阅中(ACTIVE)，或 episode 不合法
     */
    public SupplementResult supplement(Integer subId, int episode, String keyword, boolean manualSelect) {
        PtSubscriptionPlus sub = requireSearchable(subId);
        validateEpisode(sub, episode);

        int totalCandidates = 0;

        // 第一优先级：IMDB/TMDB ID 精确搜索
        List<TorrentInfo> idCandidates = new ArrayList<>(searchByExternalId(sub, episode));
        // 绝对编号的剧集要再搜一次「不带季号」：上面那次把 season=23 传给了索引器，
        // 而这类资源在站上标的是 S01（One Piece S01E1174），带季号过滤在索引器那一层
        // 就被排除在结果之外了，后面的匹配再宽松也无米下锅
        if (!absoluteMapOf(sub).isEmpty()) {
            Set<String> seen = idCandidates.stream()
                    .map(t -> t.getIndexerId() + ":" + t.getGuid())
                    .collect(java.util.stream.Collectors.toSet());
            addDeduped(idCandidates, seen, searchByExternalIdWithoutSeason(sub));
        }
        fillParsedAll(idCandidates);
        totalCandidates += idCandidates.size();

        if (manualSelect) {
            // 手动模式：收集所有来源的候选种子（ID精确 + 关键词 + 英文名），不自动推送
            List<TorrentInfo> allMatched = new ArrayList<>();

            // 目标为整季包时，人工挑选场景不必像自动推送那样严格收窄到"纯季包"——
            // 连载剧集完结前基本没有季包，否则用户在手动模式下会看不到任何候选（见 filterByTargetManual）
            Set<Integer> missingEpisodes = (episode == SubscriptionMatcher.SEASON_PACK
                    && !SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType()))
                    ? missingEpisodeNumbers(sub) : Set.of();

            // ID 搜索结果已由 imdb/tmdb id 精确锁定剧集本身，不需要再核对标题，
            // 但索引器对季参数的支持程度不一，仍需核对季号，避免把别的季当成目标季
            allMatched.addAll(filterIdCandidates(sub, episode, idCandidates, missingEpisodes));

            // 关键词搜索
            List<TorrentInfo> kwCandidates = searchByKeywordWithAbsoluteVariants(sub, episode, keyword);
            fillParsedAll(kwCandidates);
            totalCandidates += kwCandidates.size();
            allMatched.addAll(filterByTargetManual(sub, episode, kwCandidates, missingEpisodes));

            // 英文/原语言标题兜底
            String altKeyword = buildAltKeyword(sub, episode);
            if (altKeyword != null) {
                List<TorrentInfo> altCandidates = searchAcrossIndexers(altKeyword);
                fillParsedAll(altCandidates);
                totalCandidates += altCandidates.size();
                // 去重：已存在的 guid 不重复添加
                Set<String> existingGuids = allMatched.stream()
                        .map(t -> t.getIndexerId() + ":" + t.getGuid())
                        .collect(java.util.stream.Collectors.toSet());
                for (TorrentInfo t : filterByTargetManual(sub, episode, altCandidates, missingEpisodes)) {
                    if (existingGuids.add(t.getIndexerId() + ":" + t.getGuid())) {
                        allMatched.add(t);
                    }
                }
            }

            // 应用 PT 过滤规则：淘汰不满足条件的候选，按配置维度排序。
            // 黑名单必须与自动推送链路（SubscriptionEngine#handleGroup）用同一份：漏传会让已拉黑的
            // 发布组/种子照常出现在候选列表里，而用户真去选中它时，推送侧的黑名单又会把它拦下，
            // 最终只回一个没有原因的 false，用户完全看不出是被自己配的黑名单挡了。
            PtFilterConfigPlus globalConfig = filterConfigService.getConfig();
            FilterCriteria criteria = FilterCriteriaFactory.build(globalConfig, sub.getFilterOverride());
            TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());
            String originalLanguage = tmdbSearchService.getOriginalLanguage(
                    sub.getMediaType(), sub.getTmdbId());
            // 与自动推送链路同理：体积阈值按每集判定时要先知道候选覆盖多少集。
            // 手动搜索列表尤其需要——它是单集、区间包、季包混排的，不折算的话
            // 季包会被体积上限成片淘汰，用户看到的候选列表与实际可选资源对不上
            EpisodeCountResolver.apply(allMatched, sub.getTotalEpisodes(),
                    SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType()));
            List<TorrentFilterEngine.Verdict> verdicts =
                    filterEngine.evaluate(allMatched, criteria, blacklist, originalLanguage);
            List<TorrentInfo> survivors = verdicts.stream()
                    .filter(TorrentFilterEngine.Verdict::accepted)
                    .map(TorrentFilterEngine.Verdict::torrent)
                    .collect(Collectors.toCollection(ArrayList::new));

            // 按配置的排序维度排序（与自动推送模式的择优逻辑一致）
            Comparator<TorrentInfo> sortComparator = null;
            for (SortDimension dimension : criteria.sortPriority()) {
                Comparator<TorrentInfo> next = dimension.comparator(criteria);
                sortComparator = (sortComparator == null) ? next : sortComparator.thenComparing(next);
            }
            if (sortComparator != null) {
                survivors.sort(sortComparator);
            }

            sub.setLastSearchTime(new Date());
            subscriptionService.updateById(sub);

            log.info("订阅[{}] {} 关键词[{}]手动搜索补集：原始{}个，季集匹配后{}个，规则过滤后{}个"
                    + "（开启 DEBUG 日志可看到每个候选具体被哪一步、哪条规则淘汰）",
                    sub.getId(), sub.getTitle(), keyword, totalCandidates, allMatched.size(), survivors.size());
            return new SupplementResult(false, totalCandidates, toCandidateDtos(survivors));
        }

        // 以下为自动推送模式
        boolean pushed = false;
        // ID 搜索结果不需要核对标题，但仍需核对季号（严格模式：目标为整季包时只认真正的季包，
        // 不放行单集——自动推送要保证准确，不像手动模式有人工兜底）
        List<TorrentInfo> idMatched = filterIdCandidates(sub, episode, idCandidates, null);
        if (!idMatched.isEmpty()) {
            pushed = subscriptionEngine.pushBest(sub, episode, idMatched);
        }

        List<TorrentInfo> matched = new ArrayList<>();
        if (!pushed) {
            List<TorrentInfo> candidates = searchByKeywordWithAbsoluteVariants(sub, episode, keyword);
            fillParsedAll(candidates);
            totalCandidates += candidates.size();
            matched = filterByTarget(sub, episode, candidates);
        }

        if (!pushed && matched.isEmpty()) {
            String altKeyword = buildAltKeyword(sub, episode);
            if (altKeyword != null) {
                List<TorrentInfo> altCandidates = searchAcrossIndexers(altKeyword);
                fillParsedAll(altCandidates);
                totalCandidates += altCandidates.size();
                matched = filterByTarget(sub, episode, altCandidates);
            }
        }

        if (!pushed) {
            pushed = subscriptionEngine.pushBest(sub, episode, matched);
        }

        sub.setLastSearchTime(new Date());
        subscriptionService.updateById(sub);

        log.info("订阅[{}] {} 关键词[{}]搜索补集：候选{}个，{}",
                sub.getId(), sub.getTitle(), keyword, totalCandidates, pushed ? "已推送" : "未推送");
        return new SupplementResult(pushed, totalCandidates);
    }

    /**
     * 手动选择模式：将用户选中的候选种子（由前端传递必要信息）推送到下载器。
     * <p>
     * 前端在展示候选列表后，用户点击某个候选，前端将种子的关键信息传回，
     * 本方法构造一个 {@link TorrentInfo} 后走既有推送链路。
     * </p>
     *
     * @return 是否成功推送
     * @throws IllegalArgumentException 订阅不存在、订阅未在订阅中(ACTIVE)，或 episode 不合法
     */
    public boolean pushSelected(Integer subId, int episode, PushSelectedRequest request) {
        PtSubscriptionPlus sub = requireSearchable(subId);
        validateEpisode(sub, episode);

        TorrentInfo torrent = new TorrentInfo();
        torrent.setTitle(request.getTitle());
        torrent.setSize(request.getSize());
        torrent.setSeeders(request.getSeeders());
        torrent.setPeers(request.getPeers());
        torrent.setDownloadVolumeFactor(request.resolveDownloadVolumeFactor());
        torrent.setIndexerId(request.getIndexerId());
        torrent.setGuid(request.getGuid());
        torrent.setDownloadUrl(request.getDownloadUrl());
        torrent.setInfoHash(request.getInfoHash());
        torrent.setDescription(request.getDescription());
        torrent.setPubDate(request.getPubDate());

        subscriptionEngine.fillParsed(torrent);
        int target = resolvePushTarget(sub, episode, torrent);
        boolean pushed = subscriptionEngine.pushBest(sub, target, List.of(torrent));

        log.info("订阅[{}] {} 手动选择推送[{}] 目标{}：{}",
                sub.getId(), sub.getTitle(), torrent.getTitle(),
                target == SubscriptionMatcher.SEASON_PACK ? "整季" : "第" + target + "集",
                pushed ? "已推送" : "推送失败");
        return pushed;
    }

    /**
     * 校正手动推送的占位目标：以<b>用户选中的这个种子实际覆盖哪几集</b>为准，而不是发起搜索时的那个目标。
     * <p>
     * 手动模式的候选列表是季包/区间包/单集混排的（{@link #filterByTargetManual} 刻意放宽，
     * 否则连载剧集完结前用户看不到任何候选），而 {@code pushBest} 原样信任调用方传入的集号去占位，
     * 两者之间此前没有任何校验，于是出现过这样的链路：以整季为目标搜索时列表里出现了单集种子
     * （当时它对应的那一集确实还缺），用户点推送前那一集已被 RSS 补掉，
     * {@code resolveTargets} 的季包分支便把<b>当时剩下的全部缺失集</b>占给了这个单集种子——
     * 它们先被标成 IN_FLIGHT（页面显示"在途"，实际没有任何东西在下），
     * 直到下载器返回文件列表，{@code DownloadTrackService#trySelectFiles} 才发现包内一个目标集都没有，
     * 判失败、删种、把集退回缺失，白跑一轮还发一条"种子内不含任何目标集"的通知。
     * </p>
     * <p>
     * 三条规则：季号对不上直接拒绝；种子解析不出集号（真季包 / 只写 S08 不写集号）时维持原目标，
     * 包内到底有哪几集只有文件列表才知道，交给 {@code trySelectFiles} 兜底；
     * 种子有明确集号时，整季目标改按它的实际集号占位，具体集目标则要求它确实覆盖该集，否则拒绝。
     * </p>
     * <p>电影没有季集号可比对，原样放行（年份/标题校验由手动列表侧的过滤负责）。</p>
     *
     * @return 实际用于占位的目标集号
     * @throws IllegalArgumentException 种子与目标明显不符，拒绝推送（原因会原样回给前端）
     */
    private int resolvePushTarget(PtSubscriptionPlus sub, int episode, TorrentInfo torrent) {
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return episode;
        }
        Integer parsedSeason = torrent.getParsedSeason();
        if (parsedSeason != null && !parsedSeason.equals(sub.getSeason())) {
            throw new IllegalArgumentException("该种子是第 " + parsedSeason + " 季的资源，本订阅是第 "
                    + sub.getSeason() + " 季，已拒绝推送");
        }
        Integer parsedEpisode = torrent.getParsedEpisode();
        if (parsedEpisode == null) {
            return episode;
        }
        if (episode == SubscriptionMatcher.SEASON_PACK) {
            return parsedEpisode;
        }
        if (!episodeInRange(episode, parsedEpisode, torrent.getParsedEpisodeEnd())) {
            throw new IllegalArgumentException("该种子是" + describeParsedEpisodes(torrent)
                    + "的资源，不含第 " + episode + " 集，已拒绝推送");
        }
        return episode;
    }

    /** 候选解析出的集号范围，用于拒绝推送时把原因说清楚 */
    private String describeParsedEpisodes(TorrentInfo torrent) {
        Integer start = torrent.getParsedEpisode();
        Integer end = torrent.getParsedEpisodeEnd();
        return (end != null && end > start) ? "第 " + start + "-" + end + " 集" : "第 " + start + " 集";
    }

    /**
     * 将 TorrentInfo 列表转换为前端展示用的 SearchCandidateDTO 列表，
     * 附带索引器名称用于展示。
     */
    private List<SearchCandidateDTO> toCandidateDtos(List<TorrentInfo> torrents) {
        // 预加载索引器 ID→名称映射，避免逐条查库
        Map<Integer, String> indexerNames = indexerService.list().stream()
                .collect(Collectors.toMap(PtIndexerPlus::getId, PtIndexerPlus::getName,
                        (a, b) -> a));
        return torrents.stream()
                .map(t -> SearchCandidateDTO.builder()
                        .title(t.getTitle())
                        .size(t.getSize())
                        .seeders(t.getSeeders())
                        .peers(t.getPeers())
                        .free(t.isFree())
                        .downloadVolumeFactor(t.getDownloadVolumeFactor())
                        .resolution(t.getParsedResolution())
                        .source(t.getParsedSource())
                        .indexerName(indexerNames.getOrDefault(t.getIndexerId(), "未知"))
                        .indexerId(t.getIndexerId())
                        .guid(t.getGuid())
                        .downloadUrl(t.getDownloadUrl())
                        .infoHash(t.getInfoHash())
                        .parsedYear(t.getParsedYear())
                        .pubDate(t.getPubDate())
                        .parsedEpisode(t.getParsedEpisode())
                        .parsedEpisodeEnd(t.getParsedEpisodeEnd())
                        .build())
                .toList();
    }

    /**
     * 建订阅后一次性补搜历史资源。
     * <p>
     * 供 {@link SubscriptionSearchOnCreateTrigger} 异步调用——顶层不抛异常，
     * 具体搜索/推送逻辑见 {@link #searchAndPushMissing}。全部目标都没能推送成功时
     * 发一次通知，避免用户只能靠翻 pt_search_log 排查"为什么没搜到"。
     * </p>
     */
    public void supplementOnCreate(Integer subId) {
        SearchAndPushSummary summary = searchAndPushMissing(subId);
        if (summary.isSkipped()) {
            return;
        }
        if (!summary.anyPushed()) {
            PtSubscriptionPlus sub = subscriptionService.getById(subId);
            if (sub != null) {
                notifyNoResult(sub, summary.getRejectSummary());
            }
        }
    }

    /**
     * 单次搜索、按季包/散集粒度本地匹配后推送：电影只搜一次；剧集做一次全季节搜索获取候选，
     * 先试整季包，季包未命中再对仍缺失的集从同一候选池中逐集匹配推送。
     * <p>
     * 与逐集调用 {@link #supplement} 分别搜索相比，避免了对同一订阅的多集缺失反复向所有
     * 索引器发起搜索——由 O(N×M) 降为 O(N+M)，N=三级搜索，M=缺集数，每次只做本地匹配和
     * 择优推送。同时供 {@link #supplementOnCreate}（建订阅时机）与定期自动补搜
     * （{@code AutoSearchService}）复用，使自动补搜也能补到散集，而不是像旧实现那样
     * 只搜严格意义的整季包（连载剧集完结前基本没有季包资源，旧实现对这类订阅形同虚设）。
     * </p>
     * <p>
     * 顶层不抛异常，任一步的异常被各自 try/catch 捕获，不影响其他步骤继续；调用方按需
     * 决定如何处理"全部落空"的情况（是否通知、通知频率）。
     * </p>
     */
    public SearchAndPushSummary searchAndPushMissing(Integer subId) {
        PtSubscriptionPlus sub = subscriptionService.getById(subId);
        if (sub == null || !SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            return SearchAndPushSummary.skip();
        }
        List<PtSubscriptionEpisodePlus> episodes = episodeService.listBySubscription(subId);
        boolean hasMissing = episodes.stream()
                .anyMatch(ep -> SubscriptionService.STATE_MISSING.equals(ep.getState()));
        if (!hasMissing) {
            return SearchAndPushSummary.skip();
        }

        // 水位线：本次搜索开始前该订阅日志的最大 id，结束后只聚合这之后新写入的淘汰行，
        // 精确对应「这一次搜索」，不会把上一轮的原因混进来
        long watermark = searchLogService.watermark(subId);

        boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType());
        if (movie) {
            boolean pushed = false;
            try {
                pushed = supplement(subId, 0, sub.getTitle()).isPushed();
            } catch (Exception e) {
                log.warn("订阅[{}] 补搜失败：{}", subId, e.getMessage());
            }
            return new SearchAndPushSummary(false, pushed, 0,
                    pushed ? null : searchLogService.summarizeRejectionsSince(subId, watermark));
        }

        // 单次全季节搜索（三级回退：ID → 中文 → 英文/原语言）
        List<TorrentInfo> candidates = searchSeasonCandidates(sub);

        // 先试季包
        boolean seasonPushed = false;
        if (!candidates.isEmpty()) {
            List<TorrentInfo> seasonCandidates = filterByTarget(sub, SubscriptionMatcher.SEASON_PACK, candidates);
            if (!seasonCandidates.isEmpty()) {
                try {
                    seasonPushed = subscriptionEngine.pushBest(sub, SubscriptionMatcher.SEASON_PACK, seasonCandidates);
                } catch (Exception e) {
                    log.warn("订阅[{}] 补搜整季包推送异常：{}", subId, e.getMessage());
                }
            }
        }

        // 季包未命中：从同一候选池逐集匹配推送。候选池里混有季包种子（parsedEpisode 为 null），
        // pushBest/handleGroup 本身不校验候选是否对应目标集号（信任调用方已用 filterByTarget 收窄），
        // 这里必须先按具体集号过滤，只留下真正的单集资源，否则季包会被当成"这一集的最佳候选"
        // 反复整包下载，每集占位一次却各自下了一遍完整季包。
        int episodesPushed = 0;
        if (!seasonPushed && !candidates.isEmpty()) {
            for (PtSubscriptionEpisodePlus ep : episodes) {
                if (!SubscriptionService.STATE_MISSING.equals(ep.getState())) {
                    continue;
                }
                List<TorrentInfo> episodeCandidates = filterByTarget(sub, ep.getEpisode(), candidates);
                if (episodeCandidates.isEmpty()) {
                    continue;
                }
                try {
                    if (subscriptionEngine.pushBest(sub, ep.getEpisode(), episodeCandidates)) {
                        episodesPushed++;
                    }
                } catch (Exception e) {
                    log.warn("订阅[{}] 补搜第{}集推送失败：{}", subId, ep.getEpisode(), e.getMessage());
                }
            }
        }

        // 剧集分支不走 supplement()，需自行记录本次搜索时间供 AutoSearchService 到期判断
        sub.setLastSearchTime(new Date());
        subscriptionService.updateById(sub);

        boolean anyPushed = seasonPushed || episodesPushed > 0;
        return new SearchAndPushSummary(false, seasonPushed, episodesPushed,
                anyPushed ? null : searchLogService.summarizeRejectionsSince(subId, watermark));
    }

    /**
     * 全季节搜索：三级回退（ID 精确搜索 → 中文标题 → 英文/原语言标题）结果按 (indexerId, guid)
     * 去重后合并返回，而非命中即停——某一级搜索有结果不代表候选池已完整，比如 ID 搜索只对季包
     * 生效时，具体缺失的散集可能只出现在关键词搜索的结果里，命中即停会让散集补全依赖下一轮
     * 自动补搜周期，拉长实际补全时间。返回结果不经 {@code filterByTarget} 过滤，供调用方自行
     * 按季包/逐集匹配。候选已做过 {@link SubscriptionEngine#fillParsed}。
     *
     * @return 搜索到的全部候选种子（已去重）；全为空返回空列表
     */
    private List<TorrentInfo> searchSeasonCandidates(PtSubscriptionPlus sub) {
        List<TorrentInfo> merged = new ArrayList<>();
        Set<String> seenGuids = new HashSet<>();

        List<TorrentInfo> idCandidates = searchByExternalId(sub, SubscriptionMatcher.SEASON_PACK);
        fillParsedAll(idCandidates);
        addDeduped(merged, seenGuids, idCandidates);

        // 绝对编号的剧要再搜一次「不带季号」：上面那次把 season=23 传给了索引器，
        // 而这类资源在站上标的是 S01（One Piece S01E1173），带季号过滤直接就被排除在结果之外，
        // 后面的匹配再宽松也无米下锅。只对确实用绝对编号的订阅多打这一次请求
        if (!absoluteMapOf(sub).isEmpty()) {
            List<TorrentInfo> absCandidates = searchByExternalIdWithoutSeason(sub);
            fillParsedAll(absCandidates);
            addDeduped(merged, seenGuids, absCandidates);
        }

        String keyword = sub.getTitle() + " S" + pad(sub.getSeason());
        List<TorrentInfo> kwCandidates = searchAcrossIndexers(keyword);
        fillParsedAll(kwCandidates);
        addDeduped(merged, seenGuids, kwCandidates);

        // 英文标题 + 原语言标题都搜一遍（去重）：日韩剧的 originalTitle 是日文/韩文本身搜不到种子，
        // 必须靠 englishTitle 才能命中英文种子标题；两者归一化后相同（或与主标题相同）时跳过重复搜索。
        Set<Set<String>> searchedNorms = new HashSet<>();
        searchedNorms.add(matcher.normalizeAll(sub.getTitle()));
        for (String alt : new String[]{sub.getEnglishTitle(), sub.getOriginalTitle()}) {
            if (StringUtils.isBlank(alt)) {
                continue;
            }
            Set<String> altNorm = matcher.normalizeAll(alt);
            if (!searchedNorms.add(altNorm)) {
                continue;
            }
            String altKeyword = alt + " S" + pad(sub.getSeason());
            List<TorrentInfo> altCandidates = searchAcrossIndexers(altKeyword);
            fillParsedAll(altCandidates);
            addDeduped(merged, seenGuids, altCandidates);
        }

        return merged;
    }

    /** 按 (indexerId, guid) 去重后追加，与手动搜索模式（见 {@link #supplement}）用同一去重口径 */
    /**
     * 关键词检索，外加「绝对集号」形式的关键词变体。
     * <p>
     * 用户（以及 buildAltKeyword）给出的关键词形如 {@code 航海王 S23E19}，而站上这类资源叫
     * {@code One Piece S01E1174}——既不含中文名也不含 S23E19，索引器按文本匹配一条都返不回来。
     * 用户实际就是这么搜的，得到「0 个候选」后完全无从判断问题在哪。
     * </p>
     * <p>
     * 变体只用「片名 + 绝对号」而不拼 S01E：Torznab 的 q 参数在 Prowlarr/Jackett 那边是按
     * 空格切词后 AND 匹配，{@code One Piece 1174} 能命中标题里含 One / Piece / 1174 的种子，
     * 而写死 S01E 会把「用别的季号标注同一集」的发布组排除掉。
     * </p>
     */
    private List<TorrentInfo> searchByKeywordWithAbsoluteVariants(PtSubscriptionPlus sub, int episode, String keyword) {
        List<TorrentInfo> merged = new ArrayList<>(searchAcrossIndexers(keyword));
        Set<String> seen = merged.stream()
                .map(t -> t.getIndexerId() + ":" + t.getGuid())
                .collect(java.util.stream.Collectors.toSet());
        for (String variant : absoluteKeywords(sub, episode)) {
            addDeduped(merged, seen, searchAcrossIndexers(variant));
        }
        return merged;
    }

    /**
     * 「片名 + 绝对集号」的关键词变体，中英文各一条；非绝对编号的剧集返回空表（不多打请求）。
     */
    private List<String> absoluteKeywords(PtSubscriptionPlus sub, int episode) {
        if (episode == SubscriptionMatcher.SEASON_PACK
                || SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return List.of();
        }
        AbsoluteEpisodeMap absolutes = absoluteMapOf(sub);
        if (absolutes.isEmpty()) {
            return List.of();
        }
        Integer absolute = absolutes.toAbsolute(episode);
        if (absolute == null || absolute == episode) {
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        if (StringUtils.isNotBlank(sub.getTitle())) {
            keywords.add(sub.getTitle() + " " + absolute);
        }
        String alt = resolveAltTitle(sub);
        if (StringUtils.isNotBlank(alt)) {
            keywords.add(alt + " " + absolute);
        }
        return keywords;
    }

    private void addDeduped(List<TorrentInfo> target, Set<String> seenGuids, List<TorrentInfo> source) {
        for (TorrentInfo t : source) {
            if (seenGuids.add(t.getIndexerId() + ":" + t.getGuid())) {
                target.add(t);
            }
        }
    }

    /**
     * @param rejectSummary 候选被过滤规则淘汰的聚合说明，为 null 表示压根没搜到候选
     *                      （那种情况与过滤规则无关，不能把用户往规则方向引）
     */
    private void notifyNoResult(PtSubscriptionPlus sub, String rejectSummary) {
        if (StringUtils.isNotBlank(rejectSummary)) {
            notifySafely("🔍 订阅[" + StringUtils.escapeHtml(sub.getTitle()) + "] 建订阅补搜未推送任何资源——"
                    + StringUtils.escapeHtml(rejectSummary) + "。请检查过滤规则是否过严", sub);
            return;
        }
        notifySafely("🔍 订阅[" + StringUtils.escapeHtml(sub.getTitle()) + "] 建订阅补搜未找到可用资源，"
                + "可等待自动补搜/RSS 命中，或检查关键词与索引器配置", sub);
    }

    private void notifySafely(String msg, PtSubscriptionPlus sub) {
        try {
            // SUBSCRIPTION_SEARCH 而不是 GENERAL：GENERAL 是索引器故障、复制超时那类系统告警，
            // 补搜落空是某条订阅自己的事，处置方向也不同（去调过滤规则或关键词）
            TgHelper.sendMsg(NotificationType.SUBSCRIPTION_SEARCH, msg,
                    NotifyTarget.owner(sub == null ? null : sub.getOwnerUserId()));
        } catch (Exception e) {
            log.debug("发送通知失败（不影响主流程）：{}", e.getMessage());
        }
    }

    /**
     * 并发向所有启用索引器发起关键词搜索，合并结果。单索引器超时/异常只记 log，不影响其他索引器。
     * 并发数受 {@link #maxConcurrency} 限制，索引器数量超出时排队等待，避免瞬间打爆所有站点。
     */
    public List<TorrentInfo> searchAcrossIndexers(String keyword) {
        List<PtIndexerPlus> indexers = indexerService.listEnabled();
        if (indexers.isEmpty()) {
            return List.of();
        }
        List<TorrentInfo> merged = new CopyOnWriteArrayList<>();
        Semaphore limiter = new Semaphore(maxConcurrency);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = indexers.stream()
                    .map(indexer -> CompletableFuture.runAsync(Threads.wrap(() ->
                            runLimited(limiter, () -> {
                                try {
                                    merged.addAll(torznabClient.search(indexer, keyword));
                                } catch (Exception e) {
                                    log.warn("索引器[{}]关键词搜索失败：{}", indexer.getName(), e.getMessage());
                                }
                            })), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        return new ArrayList<>(merged);
    }

    /**
     * 第一优先级：对每个启用索引器，若 {@code t=caps} 探测到支持则用 IMDb ID（优先）或
     * TMDB ID（订阅无 IMDb ID 或索引器不支持 imdbid 时）发起精确搜索；两者都不满足的索引器
     * 直接跳过，不发请求（也不占并发名额——resolveIdParam 在拿许可证之前判定）。
     */
    /**
     * 不带季号的外部 ID 检索，供绝对编号的剧使用。
     * <p>
     * 走 {@link #searchByExternalId} 的电影分支：那条分支恰好就是「season/ep 都传 null」，
     * 语义上等价于「这部作品的全部资源」。不新写一份并发检索逻辑。
     * </p>
     */
    private List<TorrentInfo> searchByExternalIdWithoutSeason(PtSubscriptionPlus sub) {
        return searchByExternalId(sub, SubscriptionMatcher.SEASON_PACK, true);
    }

    private List<TorrentInfo> searchByExternalId(PtSubscriptionPlus sub, int episode) {
        return searchByExternalId(sub, episode, false);
    }

    private List<TorrentInfo> searchByExternalId(PtSubscriptionPlus sub, int episode, boolean ignoreSeason) {
        List<PtIndexerPlus> indexers = indexerService.listEnabled();
        if (indexers.isEmpty()) {
            return List.of();
        }
        boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType());
        Integer season = (movie || ignoreSeason) ? null : sub.getSeason();
        Integer ep = (movie || episode == SubscriptionMatcher.SEASON_PACK) ? null : episode;

        List<TorrentInfo> merged = new CopyOnWriteArrayList<>();
        Semaphore limiter = new Semaphore(maxConcurrency);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = indexers.stream()
                    .map(indexer -> CompletableFuture.runAsync(Threads.wrap(() -> {
                        IdSearchParam param = resolveIdParam(sub, indexer, movie);
                        if (param == null) {
                            return;
                        }
                        runLimited(limiter, () -> {
                            try {
                                merged.addAll(torznabClient.searchByExternalId(
                                        indexer, movie, param.name(), param.value(), season, ep));
                            } catch (Exception e) {
                                log.warn("索引器[{}]按{}搜索失败：{}", indexer.getName(), param.name(), e.getMessage());
                            }
                        });
                    }), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        return new ArrayList<>(merged);
    }

    /**
     * 在信号量许可证下执行任务，把"抢许可证-跑任务-还许可证"的样板收敛到一处。
     * 等待许可证时被中断则放弃本次任务并恢复中断标志，不让异常从 CompletableFuture 里裸抛出去。
     */
    private void runLimited(Semaphore limiter, Runnable task) {
        try {
            limiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            task.run();
        } finally {
            limiter.release();
        }
    }

    private record IdSearchParam(String name, String value) {
    }

    private IdSearchParam resolveIdParam(PtSubscriptionPlus sub, PtIndexerPlus indexer, boolean movie) {
        IndexerCapability capability = capabilityCache.get(indexer);
        boolean imdbSupported = movie ? capability.movieImdbSupported() : capability.tvImdbSupported();
        boolean tmdbSupported = movie ? capability.movieTmdbSupported() : capability.tvTmdbSupported();
        if (imdbSupported && StringUtils.isNotBlank(sub.getImdbId())) {
            return new IdSearchParam("imdbid", sub.getImdbId());
        }
        if (tmdbSupported && StringUtils.isNotBlank(sub.getTmdbId())) {
            return new IdSearchParam("tmdbid", sub.getTmdbId());
        }
        return null;
    }

    private void fillParsedAll(List<TorrentInfo> candidates) {
        for (TorrentInfo torrent : candidates) {
            subscriptionEngine.fillParsed(torrent);
        }
    }

    /**
     * 中文关键词搜不到匹配时的英文/原语言标题兜底：优先用真正的英文标题（{@code englishTitle}，
     * PT 站种子标题绝大多数是英文/罗马字，对日剧/韩剧尤其关键——它们的 originalTitle 是日文/韩文，
     * 拿去搜索站点基本搜不到任何结果）；englishTitle 为空时退回 originalTitle（旧订阅未回填该字段，
     * 或作品原始语言本来就是中文/英文的场景）。候选标题为空、或归一化后与 title 相同时返回 null，
     * 跳过补搜。季/集号后缀按 supplement() 已有的 episode/sub.getSeason() 重新拼，不依赖对入参
     * keyword 字符串做解析——用户手动改过关键词时也能正确拼出英文版。
     */
    private String buildAltKeyword(PtSubscriptionPlus sub, int episode) {
        String altTitle = resolveAltTitle(sub);
        if (altTitle == null) {
            return null;
        }
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return altTitle;
        }
        if (episode == SubscriptionMatcher.SEASON_PACK) {
            return altTitle + " S" + pad(sub.getSeason());
        }
        return altTitle + " S" + pad(sub.getSeason()) + "E" + pad(episode);
    }

    /**
     * 兜底候选标题：englishTitle 存在且非中文原生内容时优先用它，否则退回 originalTitle。
     */
    private String resolveAltTitle(PtSubscriptionPlus sub) {
        Set<String> titleNorm = matcher.normalizeAll(sub.getTitle());
        String englishTitle = sub.getEnglishTitle();
        if (StringUtils.isNotBlank(englishTitle) && !matcher.normalizeAll(englishTitle).equals(titleNorm)) {
            return englishTitle;
        }
        String originalTitle = sub.getOriginalTitle();
        if (StringUtils.isBlank(originalTitle) || matcher.normalizeAll(originalTitle).equals(titleNorm)) {
            return null;
        }
        return originalTitle;
    }

    private String pad(Integer number) {
        int n = number == null ? 0 : number;
        return n < 10 ? "0" + n : String.valueOf(n);
    }

    private PtSubscriptionPlus requireSearchable(Integer subId) {
        PtSubscriptionPlus sub = subscriptionService.getById(subId);
        if (sub == null) {
            throw new IllegalArgumentException("订阅不存在：" + subId);
        }
        if (!SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            throw new IllegalArgumentException("订阅未在订阅中(当前状态 " + sub.getStatus() + ")，无法搜索补集");
        }
        return sub;
    }

    /**
     * 是否一个启用中的索引器都没有。供接口层在发起手动搜索前提示用户。
     * <p>
     * {@code searchAcrossIndexers} / {@code searchByExternalId} 在没有索引器时都会立刻返回空表，
     * 于是日志和页面都显示「原始 0 个，季集匹配后 0 个」——这与「搜了但站上确实没有」
     * 长得一模一样，用户会照着去翻过滤规则、改关键词，而真正的原因是压根没发出去过请求。
     * 用户实际就这么排查过一轮（索引器被停用/删除后，仍以为是集号匹配逻辑的问题）。
     * </p>
     * <p>
     * 判断放在这里而不是塞进 {@code supplement()}：后者被自动补搜与建订阅触发共用，
     * 在那里抛异常会把「后台任务本轮无事可做」也变成异常路径。自动侧的处理见
     * {@code AutoSearchService#run}，它整轮跳过并记一条说明性的 warn。
     * </p>
     */
    public boolean hasNoEnabledIndexer() {
        return indexerService.listEnabled().isEmpty();
    }

    private void validateEpisode(PtSubscriptionPlus sub, int episode) {
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            if (episode != 0) {
                throw new IllegalArgumentException("电影订阅只能传 episode=0");
            }
            return;
        }
        if (episode == SubscriptionMatcher.SEASON_PACK) {
            return;
        }
        Integer totalEpisodes = sub.getTotalEpisodes();
        if (episode < 1 || totalEpisodes == null || episode > totalEpisodes) {
            throw new IllegalArgumentException("集号超出范围：" + episode);
        }
    }

    /**
     * 数据一致性校验：搜索补集的候选来自模糊全文搜索或 ID 搜索，未经过 {@link SubscriptionMatcher} 确认，
     * 必须在交给 {@link SubscriptionEngine#pushBest} 之前自行校验候选是否真的匹配目标订阅，否则错配种子会被
     * handleGroup 无差别占位/推送（剧集会永久卡在 IN_FLIGHT，电影会直接下载错内容）。
     *
     * <p>电影订阅没有季/集号可比对，改为校验标题（复用 {@link SubscriptionMatcher} 同一套归一化
     * 全等规则）与年份，并排除带季/集信息的候选（说明是剧集/综艺）。剧集订阅除了核对季/集号，
     * 同样要核对标题——只比对季号会导致任意一部"恰好也在第 N 季"的不相关剧集被当成候选放行
     * （实测案例：关键词搜索返回的候选实际是完全无关的另一部剧,仅因为季号都是 5 就会被放行）。</p>
     */
    private List<TorrentInfo> filterByTarget(PtSubscriptionPlus sub, int episode, List<TorrentInfo> candidates) {
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return filterMovieCandidates(sub, candidates);
        }
        Integer subSeason = sub.getSeason();
        Set<String> subTitles = matcher.normalizeAll(sub.getTitle(), sub.getOriginalTitle(), sub.getEnglishTitle());
        AbsoluteEpisodeMap absolutes = absoluteMapOf(sub);
        List<TorrentInfo> matched = new ArrayList<>();
        for (TorrentInfo candidate : candidates) {
            if (!titleMatches(subTitles, candidate)) {
                continue;
            }
            Integer parsedSeason = candidate.getParsedSeason();
            if (parsedSeason == null || !parsedSeason.equals(subSeason)) {
                // 季号对不上时再看绝对编号：One Piece S01E1173 其实是第 23 季第 18 集。
                // 判据与 RSS 链路的 SubscriptionMatcher#matchByAbsolute 保持一致
                Integer localEpisode = absoluteEpisodeOf(candidate, sub, absolutes);
                if (localEpisode != null && episode != SubscriptionMatcher.SEASON_PACK
                        && localEpisode == episode) {
                    matched.add(candidate);
                }
                continue;
            }
            Integer parsedEpisode = candidate.getParsedEpisode();
            if (episode == SubscriptionMatcher.SEASON_PACK) {
                if (parsedEpisode == null) {
                    matched.add(candidate);
                }
            } else if (episodeInRange(episode, parsedEpisode, candidate.getParsedEpisodeEnd())) {
                matched.add(candidate);
            }
        }
        return matched;
    }

    /** 该订阅的绝对编号映射，非绝对编号的剧返回空对象 */
    private AbsoluteEpisodeMap absoluteMapOf(PtSubscriptionPlus sub) {
        if (sub.getId() == null) {
            return AbsoluteEpisodeMap.EMPTY;
        }
        return AbsoluteEpisodeMap.from(episodeService.list(
                new LambdaQueryWrapper<PtSubscriptionEpisodePlus>()
                        .eq(PtSubscriptionEpisodePlus::getSubId, sub.getId())
                        .isNotNull(PtSubscriptionEpisodePlus::getTmdbEpisodeNumber)));
    }

    /** 候选按绝对编号解释时对应的本地集号，解释不通返回 null。约束同 SubscriptionMatcher#matchByAbsolute */
    private Integer absoluteEpisodeOf(TorrentInfo candidate, PtSubscriptionPlus sub, AbsoluteEpisodeMap absolutes) {
        if (absolutes.isEmpty()) {
            return null;
        }
        Integer season = candidate.getParsedSeason();
        if (season != null && season != 1) {
            return null;
        }
        Integer parsed = candidate.getParsedEpisode();
        if (parsed == null) {
            return null;
        }
        return absolutes.toLocal(parsed);
    }

    /**
     * 目标集号是否落在候选种子解析出的集号区间内：单集资源 parsedEpisodeEnd 为 null，
     * 区间等价于 [parsedEpisode, parsedEpisode]；区间打包资源（如 S01E01-E02）此前只按
     * {@code parsedEpisode == episode} 精确比较，导致搜某一集缺失时，若唯一候选是把该集和
     * 别的集打包在一起的区间种子（该集不是区间起始集），会被误判为"没有候选"而永远搜不到
     * ——不是显示错误，是真的从未推送成功。
     */
    private boolean episodeInRange(int episode, Integer parsedEpisode, Integer parsedEpisodeEnd) {
        if (parsedEpisode == null) {
            return false;
        }
        int rangeEnd = (parsedEpisodeEnd != null && parsedEpisodeEnd > parsedEpisode) ? parsedEpisodeEnd : parsedEpisode;
        return episode >= parsedEpisode && episode <= rangeEnd;
    }

    /**
     * 手动模式下目标为整季包时的候选筛选：除了真正的季包，还纳入该订阅当前仍缺失的单集资源。
     * <p>
     * 与自动推送模式（{@link #filterByTarget}）不同，人工挑选场景不需要靠"只收纯季包"这么严格
     * 的收窄来防误推——用户本身就是最后一道校验。连载剧集完结前 PT 站基本没有季包资源，
     * 严格收窄会导致手动模式几乎永远看不到候选（历史问题：某订阅一次搜到 103 个原始候选，
     * 因为全被当作非季包剔除，最终只剩 1 个）。标题校验不放松，理由同 {@link #filterByTarget}。
     * </p>
     * <p>电影、或目标本身就是具体集号时，行为与 {@link #filterByTarget} 完全一致。</p>
     */
    private List<TorrentInfo> filterByTargetManual(PtSubscriptionPlus sub, int episode,
                                                     List<TorrentInfo> candidates, Set<Integer> missingEpisodes) {
        if (episode != SubscriptionMatcher.SEASON_PACK
                || SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return filterByTarget(sub, episode, candidates);
        }
        Integer subSeason = sub.getSeason();
        Set<String> subTitles = matcher.normalizeAll(sub.getTitle(), sub.getOriginalTitle(), sub.getEnglishTitle());
        List<TorrentInfo> matched = new ArrayList<>();
        for (TorrentInfo candidate : candidates) {
            Integer parsedSeason = candidate.getParsedSeason();
            if (parsedSeason == null || !parsedSeason.equals(subSeason)) {
                log.debug("候选被季号过滤：{} —— 解析季号={}，订阅季号={}",
                        candidate.getTitle(), parsedSeason, subSeason);
                continue;
            }
            if (!titleMatches(subTitles, candidate)) {
                log.debug("候选被标题过滤：{} —— 与订阅《{}》标题不匹配", candidate.getTitle(), sub.getTitle());
                continue;
            }
            Integer parsedEpisode = candidate.getParsedEpisode();
            if (parsedEpisode == null || rangeIntersectsMissing(parsedEpisode, candidate.getParsedEpisodeEnd(), missingEpisodes)) {
                matched.add(candidate);
            } else {
                log.debug("候选被集号过滤：{} —— 解析集号={} 不在缺失集合内", candidate.getTitle(), parsedEpisode);
            }
        }
        return matched;
    }

    /**
     * ID 搜索候选的季号校验（不含标题）：命中 imdb/tmdb id 已经精确锁定剧集本身，不需要再核对标题，
     * 但索引器对 season 参数的支持程度不一（有的按季返回全季资源，不严格卡集号），仍需在本地核对
     * 季号，否则别的季的资源可能被当成目标季误推。电影订阅信任 ID 搜索结果，原样放行。
     *
     * @param missingEpisodes 目标为整季包时允许放行的"当前缺失集号"集合；传 {@code null} 表示严格模式——
     *                        只放行真正的季包，不放行任何单集（供自动推送场景使用，人工兜底场景传实际缺失集合）
     */
    private List<TorrentInfo> filterIdCandidates(PtSubscriptionPlus sub, int episode,
                                                  List<TorrentInfo> candidates, Set<Integer> missingEpisodes) {
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return candidates;
        }
        Integer subSeason = sub.getSeason();
        List<TorrentInfo> matched = new ArrayList<>();
        for (TorrentInfo candidate : candidates) {
            Integer parsedSeason = candidate.getParsedSeason();
            if (parsedSeason == null || !parsedSeason.equals(subSeason)) {
                log.debug("ID搜索候选被季号过滤：{} —— 解析季号={}，订阅季号={}",
                        candidate.getTitle(), parsedSeason, subSeason);
                continue;
            }
            Integer parsedEpisode = candidate.getParsedEpisode();
            if (episode == SubscriptionMatcher.SEASON_PACK) {
                if (parsedEpisode == null || (missingEpisodes != null
                        && rangeIntersectsMissing(parsedEpisode, candidate.getParsedEpisodeEnd(), missingEpisodes))) {
                    matched.add(candidate);
                }
            } else if (episodeInRange(episode, parsedEpisode, candidate.getParsedEpisodeEnd())) {
                matched.add(candidate);
            }
        }
        return matched;
    }

    /**
     * 候选种子解析出的集号区间是否与"当前缺失集号集合"有交集，供整季包场景放行区间打包资源
     * （如 S01E01-E02，只要区间内有一集仍缺失就该放行，不能像单集那样只看起始集号是否在集合里）。
     */
    private boolean rangeIntersectsMissing(Integer parsedEpisode, Integer parsedEpisodeEnd, Set<Integer> missingEpisodes) {
        if (parsedEpisode == null) {
            return false;
        }
        int rangeEnd = (parsedEpisodeEnd != null && parsedEpisodeEnd > parsedEpisode) ? parsedEpisodeEnd : parsedEpisode;
        for (int e = parsedEpisode; e <= rangeEnd; e++) {
            if (missingEpisodes.contains(e)) {
                return true;
            }
        }
        return false;
    }

    /** 订阅当前处于缺失(MISSING)状态的集号集合，供 {@link #filterByTargetManual}/{@link #filterIdCandidates} 放行单集候选使用 */
    private Set<Integer> missingEpisodeNumbers(PtSubscriptionPlus sub) {
        return episodeService.listBySubscription(sub.getId()).stream()
                .filter(ep -> SubscriptionService.STATE_MISSING.equals(ep.getState()))
                .map(PtSubscriptionEpisodePlus::getEpisode)
                .collect(Collectors.toSet());
    }

    /**
     * TV 候选标题校验：解析标题（中/英文任一命中即可）与订阅标题归一化后有交集即视为匹配；
     * parsedTitle/parsedTitleEn 都解析失败时回退到种子原始标题，避免特殊命名格式漏判。
     */
    private boolean titleMatches(Set<String> subTitles, TorrentInfo candidate) {
        String t1 = candidate.getParsedTitle();
        String t2 = candidate.getParsedTitleEn();
        String tFallback = (t1 == null && t2 == null) ? candidate.getTitle() : null;
        Set<String> torrentTitles = matcher.normalizeAll(t1, t2, tFallback);
        return !Collections.disjoint(torrentTitles, subTitles);
    }

    /**
     * 电影候选校验标准与 {@link SubscriptionMatcher} 的电影分支保持一致：
     * 带季/集信息的一定是剧集/综艺，标题需归一化后与订阅有交集，年份允许 1 年以内偏差
     * （见 {@link SubscriptionMatcher#movieYearMatches}——电影节首映 vs 正式公映、跨年上映
     * 都会让同一部电影在不同来源差一年；但任一侧缺年份仍判不匹配，同名翻拍宁可漏也不能串台）。
     * <p>
     * 注意：{@link SubscriptionEngine#fillParsed} 填入的 {@code parsedTitle} 依赖
     * {@code MediaParser.parseLocal} 的解析结果，特殊格式的种子标题可能解析失败（产生 null）。
     * 此时回退到种子的原始标题 {@code torrent.getTitle()} 做归一化比对，避免漏掉有效候选。
     * </p>
     */
    private List<TorrentInfo> filterMovieCandidates(PtSubscriptionPlus sub, List<TorrentInfo> candidates) {
        Set<String> subTitles = matcher.normalizeAll(sub.getTitle(), sub.getOriginalTitle(), sub.getEnglishTitle());
        List<TorrentInfo> matched = new ArrayList<>();
        for (TorrentInfo candidate : candidates) {
            if (candidate.getParsedSeason() != null || candidate.getParsedEpisode() != null) {
                continue;
            }
            if (!titleMatches(subTitles, candidate)) {
                continue;
            }
            // 年份判定走 SubscriptionMatcher 的共享方法（允许 1 年偏差），不要在这里另写一份：
            // RSS 自动匹配与搜索补集对「这个候选是不是这部电影」必须给出同一个答案
            if (!matcher.movieYearMatches(sub.getYear(), candidate.getParsedYear())) {
                continue;
            }
            matched.add(candidate);
        }
        return matched;
    }
}
