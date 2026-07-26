package com.ruoyi.openliststrm.pt.subscription;

import com.ruoyi.common.utils.Threads;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.helper.TgHelper;
import com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.indexer.IndexerCapability;
import com.ruoyi.openliststrm.pt.indexer.IndexerCapabilityCache;
import com.ruoyi.openliststrm.pt.indexer.TorznabClient;
import com.ruoyi.openliststrm.pt.model.TorrentInfo;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

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

    /**
     * 对所有启用索引器并发搜索时的最大同时请求数。索引器数量可能远超此值，多出的排队等待，
     * 避免一次搜索瞬间对所有站点同时发请求触发反爬限流（原实现是无限制并发，见需求背景）。
     */
    private final int maxConcurrency;

    public SearchSupplementService(IPtIndexerPlusService indexerService,
                                   TorznabClient torznabClient,
                                   SubscriptionEngine subscriptionEngine,
                                   IPtSubscriptionPlusService subscriptionService,
                                   IPtSubscriptionEpisodePlusService episodeService,
                                   SubscriptionMatcher matcher,
                                   IndexerCapabilityCache capabilityCache,
                                   @Value("${pt.search.max-concurrency:3}") int maxConcurrency) {
        this.indexerService = indexerService;
        this.torznabClient = torznabClient;
        this.subscriptionEngine = subscriptionEngine;
        this.subscriptionService = subscriptionService;
        this.episodeService = episodeService;
        this.matcher = matcher;
        this.capabilityCache = capabilityCache;
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    /**
     * 对指定订阅的指定目标（集号，或季包/电影的哨兵值）发起一次搜索补集。
     * <p>
     * 三级回退：ID 精确搜索（索引器支持时）→ 中文标题 → 英文/原语言标题，任一级过滤后有
     * 匹配就停止，不再尝试后面的级别；过滤标准（{@link #filterByTarget}）全程不变。
     * </p>
     *
     * @throws IllegalArgumentException 订阅不存在、订阅未在订阅中(ACTIVE)，或 episode 不合法
     */
    public SupplementResult supplement(Integer subId, int episode, String keyword) {
        PtSubscriptionPlus sub = requireSearchable(subId);
        validateEpisode(sub, episode);

        int totalCandidates = 0;

        List<TorrentInfo> idCandidates = searchByExternalId(sub, episode);
        fillParsedAll(idCandidates);
        totalCandidates += idCandidates.size();
        List<TorrentInfo> matched = filterByTarget(sub, episode, idCandidates);

        if (matched.isEmpty()) {
            List<TorrentInfo> candidates = searchAcrossIndexers(keyword);
            fillParsedAll(candidates);
            totalCandidates += candidates.size();
            matched = filterByTarget(sub, episode, candidates);
        }

        if (matched.isEmpty()) {
            String altKeyword = buildAltKeyword(sub, episode);
            if (altKeyword != null) {
                List<TorrentInfo> altCandidates = searchAcrossIndexers(altKeyword);
                fillParsedAll(altCandidates);
                totalCandidates += altCandidates.size();
                matched = filterByTarget(sub, episode, altCandidates);
            }
        }

        boolean pushed = subscriptionEngine.pushBest(sub, episode, matched);

        sub.setLastSearchTime(new Date());
        subscriptionService.updateById(sub);

        log.info("订阅[{}] {} 关键词[{}]搜索补集：候选{}个，{}",
                sub.getId(), sub.getTitle(), keyword, totalCandidates, pushed ? "已推送" : "未推送");
        return new SupplementResult(pushed, totalCandidates);
    }

    /**
     * 建订阅后一次性补搜历史资源：电影只搜一次；剧集做一次全季节搜索获取候选，
     * 先试整季包，季包未命中再对仍缺失的集从同一候选池中逐集匹配推送。
     * <p>
     * 与旧实现（逐集调用 {@link #supplement} 分别搜索）相比，避免了对同级别的
     * 多集缺失时反复向所有索引器发起搜索——由 O(N×M) 降为 O(N+M)，N=三级搜索,
     * M=缺集数,每次只做本地匹配和择优推送。
     * </p>
     * <p>
     * 供 {@link SubscriptionSearchOnCreateTrigger} 异步调用——顶层不抛异常，
     * 任一步的异常被各自 try/catch 捕获，不影响其他步骤继续。
     * 全部目标都没能推送成功时发一次通知，避免用户只能靠翻 pt_search_log
     * 排查"为什么没搜到"。
     * </p>
     */
    public void supplementOnCreate(Integer subId) {
        PtSubscriptionPlus sub = subscriptionService.getById(subId);
        if (sub == null || !SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            return;
        }
        List<PtSubscriptionEpisodePlus> episodes = episodeService.listBySubscription(subId);
        boolean hasMissing = episodes.stream()
                .anyMatch(ep -> SubscriptionService.STATE_MISSING.equals(ep.getState()));
        if (!hasMissing) {
            return;
        }

        boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType());
        if (movie) {
            boolean pushed = false;
            try {
                pushed = supplement(subId, 0, sub.getTitle()).isPushed();
            } catch (Exception e) {
                log.warn("订阅[{}] 建订阅补搜失败：{}", subId, e.getMessage());
            }
            if (!pushed) {
                notifyNoResult(sub);
            }
            return;
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
                    log.warn("订阅[{}] 建订阅补搜整季包推送异常：{}", subId, e.getMessage());
                }
            }
        }

        // 季包未命中：从同一候选池逐集匹配推送
        boolean anyEpisodePushed = false;
        if (!seasonPushed && !candidates.isEmpty()) {
            for (PtSubscriptionEpisodePlus ep : episodes) {
                if (!SubscriptionService.STATE_MISSING.equals(ep.getState())) {
                    continue;
                }
                try {
                    if (subscriptionEngine.pushBest(sub, ep.getEpisode(), candidates)) {
                        anyEpisodePushed = true;
                    }
                } catch (Exception e) {
                    log.warn("订阅[{}] 建订阅补搜第{}集推送失败：{}", subId, ep.getEpisode(), e.getMessage());
                }
            }
        }

        if (!seasonPushed && !anyEpisodePushed) {
            notifyNoResult(sub);
        }
    }

    /**
     * 全季节搜索：三级回退（ID 精确搜索 → 中文标题 → 英文/原语言标题），
     * 返回结果不经 {@code filterByTarget} 过滤，供调用方自行按季包/逐集匹配。
     * 各级搜索到有结果即停止，候选已做过 {@link SubscriptionEngine#fillParsed}。
     *
     * @return 搜索到的全部候选种子；全为空返回空列表
     */
    private List<TorrentInfo> searchSeasonCandidates(PtSubscriptionPlus sub) {
        List<TorrentInfo> candidates = searchByExternalId(sub, SubscriptionMatcher.SEASON_PACK);
        fillParsedAll(candidates);
        if (!candidates.isEmpty()) {
            return candidates;
        }

        String keyword = sub.getTitle() + " S" + pad(sub.getSeason());
        candidates = searchAcrossIndexers(keyword);
        fillParsedAll(candidates);
        if (!candidates.isEmpty()) {
            return candidates;
        }

        String originalTitle = sub.getOriginalTitle();
        if (StringUtils.isNotBlank(originalTitle)
                && !matcher.normalizeAll(originalTitle).equals(matcher.normalizeAll(sub.getTitle()))) {
            String altKeyword = originalTitle + " S" + pad(sub.getSeason());
            candidates = searchAcrossIndexers(altKeyword);
            fillParsedAll(candidates);
        }

        return candidates;
    }

    private void notifyNoResult(PtSubscriptionPlus sub) {
        notifySafely("🔍 订阅[" + sub.getTitle() + "] 建订阅补搜未找到可用资源，"
                + "可等待自动补搜/RSS 命中，或检查索引器配置");
    }

    private void notifySafely(String msg) {
        try {
            TgHelper.sendMsg(msg);
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
    private List<TorrentInfo> searchByExternalId(PtSubscriptionPlus sub, int episode) {
        List<PtIndexerPlus> indexers = indexerService.listEnabled();
        if (indexers.isEmpty()) {
            return List.of();
        }
        boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType());
        Integer season = movie ? null : sub.getSeason();
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
     * 中文关键词搜不到匹配时的英文/原语言标题兜底：originalTitle 为空、或归一化后与 title 相同
     * （中文原生内容，TMDb 原语言标题本来就是中文）时返回 null，跳过补搜。
     * 季/集号后缀按 supplement() 已有的 episode/sub.getSeason() 重新拼，不依赖对入参 keyword
     * 字符串做解析——用户手动改过关键词时也能正确拼出英文版。
     */
    private String buildAltKeyword(PtSubscriptionPlus sub, int episode) {
        String originalTitle = sub.getOriginalTitle();
        if (StringUtils.isBlank(originalTitle)) {
            return null;
        }
        if (matcher.normalizeAll(originalTitle).equals(matcher.normalizeAll(sub.getTitle()))) {
            return null;
        }
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return originalTitle;
        }
        if (episode == SubscriptionMatcher.SEASON_PACK) {
            return originalTitle + " S" + pad(sub.getSeason());
        }
        return originalTitle + " S" + pad(sub.getSeason()) + "E" + pad(episode);
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
     * handleGroup 无差别占位/推送（剧集会永久卡在 IN_FLIGHT，电影会直接下载错内容）。ID 命中的候选同样要
     * 过这层校验——防的是索引器对 ID 参数实现有 bug（比如把 imdbid 当普通关键词分词处理）。
     *
     * <p>电影订阅没有季/集号可比对，改为校验标题（复用 {@link SubscriptionMatcher} 同一套归一化
     * 全等规则）与年份，并排除带季/集信息的候选（说明是剧集/综艺）。</p>
     */
    private List<TorrentInfo> filterByTarget(PtSubscriptionPlus sub, int episode, List<TorrentInfo> candidates) {
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return filterMovieCandidates(sub, candidates);
        }
        Integer subSeason = sub.getSeason();
        List<TorrentInfo> matched = new ArrayList<>();
        for (TorrentInfo candidate : candidates) {
            Integer parsedSeason = candidate.getParsedSeason();
            if (parsedSeason == null || !parsedSeason.equals(subSeason)) {
                continue;
            }
            Integer parsedEpisode = candidate.getParsedEpisode();
            if (episode == SubscriptionMatcher.SEASON_PACK) {
                if (parsedEpisode == null) {
                    matched.add(candidate);
                }
            } else if (parsedEpisode != null && parsedEpisode == episode) {
                matched.add(candidate);
            }
        }
        return matched;
    }

    /**
     * 电影候选校验标准与 {@link SubscriptionMatcher} 的电影分支保持一致：
     * 带季/集信息的一定是剧集/综艺，标题需归一化后与订阅有交集，年份必须完全一致
     * （同名翻拍常见，宁可漏也不能串台）。
     * <p>
     * 注意：{@link SubscriptionEngine#fillParsed} 填入的 {@code parsedTitle} 依赖
     * {@code MediaParser.parseLocal} 的解析结果，特殊格式的种子标题可能解析失败（产生 null）。
     * 此时回退到种子的原始标题 {@code torrent.getTitle()} 做归一化比对，避免漏掉有效候选。
     * </p>
     */
    private List<TorrentInfo> filterMovieCandidates(PtSubscriptionPlus sub, List<TorrentInfo> candidates) {
        Set<String> subTitles = matcher.normalizeAll(sub.getTitle(), sub.getOriginalTitle());
        List<TorrentInfo> matched = new ArrayList<>();
        for (TorrentInfo candidate : candidates) {
            if (candidate.getParsedSeason() != null || candidate.getParsedEpisode() != null) {
                continue;
            }
            // parsedTitle/parsedTitleEn 可能为 null（MediaParser 解析失败），
            // 此时回退到原始种子标题 getTitle() 做归一化
            String t1 = candidate.getParsedTitle();
            String t2 = candidate.getParsedTitleEn();
            String tFallback = (t1 == null && t2 == null) ? candidate.getTitle() : null;
            Set<String> torrentTitles = matcher.normalizeAll(t1, t2, tFallback);
            if (Collections.disjoint(torrentTitles, subTitles)) {
                continue;
            }
            if (StringUtils.isBlank(candidate.getParsedYear()) || StringUtils.isBlank(sub.getYear())
                    || !candidate.getParsedYear().equals(sub.getYear())) {
                continue;
            }
            matched.add(candidate);
        }
        return matched;
    }
}
