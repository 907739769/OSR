package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.filter.FilterCriteria;
import com.osr.openliststrm.pt.filter.FilterCriteriaFactory;
import com.osr.openliststrm.pt.filter.TorrentBlacklist;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.indexer.GuidHasher;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.MatchResult;
import com.osr.openliststrm.pt.task.DownloadRecordState;
import com.osr.openliststrm.pt.ws.PtStatusWebSocket;
import com.osr.openliststrm.rename.MediaParser;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.common.utils.Threads;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 订阅推送引擎：把一批 RSS 种子变成「推给下载器的决策」并落好账。
 * <p>
 * 不加 {@code @Transactional}——方法体内含推送下载器的网络调用，长事务是反模式。
 * 各步写库各自独立，推送失败时显式回滚（删记录 + 集状态改回 MISSING）。
 * </p>
 * <p>
 * 分组处理阶段（{@link #handleGroup}）各组间无写冲突，使用虚拟线程并行执行，
 * 避免单组网络调用阻塞其它组的匹配/推送。匹配+分组阶段（纯内存计算）保持串行。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class SubscriptionEngine {

    private static final String STATE_MISSING = SubscriptionEpisodeState.MISSING.value();
    private static final String STATE_IN_FLIGHT = SubscriptionEpisodeState.IN_FLIGHT.value();
    private static final String RECORD_PUSHED = DownloadRecordState.PUSHED.value();
    private static final String RECORD_DOWNLOADING = DownloadRecordState.DOWNLOADING.value();

    /** 唯一标签前缀，用于把下载记录回映到下载器里的种子 */
    private static final String TAG_PREFIX = "osr-pt-";

    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final IPtDownloadRecordPlusService recordService;
    private final IPtDownloaderPlusService downloaderService;
    private final IPtFilterConfigPlusService filterConfigService;
    private final DownloaderClientFactory downloaderClientFactory;
    private final TorrentFilterEngine filterEngine;
    private final SubscriptionMatcher matcher;
    private final SearchLogService searchLogService;
    private final IPtTorrentBlacklistPlusService blacklistService;
    private final TmdbSearchService tmdbSearchService;

    /**
     * 本地标题解析器。parseLocal 只做本地正则抽取，不查 TMDb、不调 AI，所以传 null 客户端即可；
     * 而且 MediaParser 不是 Spring bean（它一直靠 new + RenameClientProvider 管理），
     * 若通过构造器注入会导致 SubscriptionEngine 装配时找不到 MediaParser bean 而启动失败。
     */
    private final MediaParser mediaParser = new MediaParser(null, null);

    public SubscriptionEngine(IPtSubscriptionPlusService subscriptionService,
                              IPtSubscriptionEpisodePlusService episodeService,
                              IPtDownloadRecordPlusService recordService,
                              IPtDownloaderPlusService downloaderService,
                              IPtFilterConfigPlusService filterConfigService,
                              DownloaderClientFactory downloaderClientFactory,
                              TorrentFilterEngine filterEngine,
                              SubscriptionMatcher matcher,
                              SearchLogService searchLogService,
                              IPtTorrentBlacklistPlusService blacklistService,
                              TmdbSearchService tmdbSearchService) {
        this.subscriptionService = subscriptionService;
        this.episodeService = episodeService;
        this.recordService = recordService;
        this.downloaderService = downloaderService;
        this.filterConfigService = filterConfigService;
        this.downloaderClientFactory = downloaderClientFactory;
        this.filterEngine = filterEngine;
        this.matcher = matcher;
        this.searchLogService = searchLogService;
        this.blacklistService = blacklistService;
        this.tmdbSearchService = tmdbSearchService;
    }

    /**
     * 处理一批种子：匹配订阅 → 分组 → 过滤择优 → 占位 → 推送 → 落账。
     * <p>
     * 匹配+分组阶段（纯内存计算）保持串行；分组处理阶段（含网络调用）
     * 各组间无写冲突，使用虚拟线程并行执行。
     * </p>
     *
     * @return 成功推送给下载器的种子数
     */
    public int process(List<TorrentInfo> torrents) {
        List<PtSubscriptionPlus> subscriptions = subscriptionService.listActive();
        if (subscriptions.isEmpty() || torrents.isEmpty()) {
            return 0;
        }
        PtFilterConfigPlus globalConfig = filterConfigService.getConfig();
        TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());

        // 匹配+分组：纯内存计算，保持串行
        Map<String, List<TorrentInfo>> groups = new LinkedHashMap<>();
        Map<String, MatchResult> groupMatch = new LinkedHashMap<>();
        for (TorrentInfo torrent : torrents) {
            fillParsed(torrent);
            MatchResult match = matcher.match(torrent, subscriptions);
            if (match == null) {
                log.debug("种子未匹配到任何订阅：{}", torrent.getTitle());
                continue;
            }
            String key = match.getSubscription().getId() + "#" + match.getEpisode();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(torrent);
            groupMatch.putIfAbsent(key, match);
        }

        if (groups.isEmpty()) {
            return 0;
        }

        // 分组处理：各组间无写冲突，用虚拟线程并行
        Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache = new ConcurrentHashMap<>();
        List<PtDownloaderPlus> enabledDownloaders = loadEnabledDownloaders();
        Map<Integer, Long> downloaderLoadCache = new ConcurrentHashMap<>(
                loadDownloaderLoadCounts(enabledDownloaders));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Boolean>> futures = groups.entrySet().stream()
                    .map(entry -> CompletableFuture.supplyAsync(Threads.wrapSupplier(() -> {
                        MatchResult match = groupMatch.get(entry.getKey());
                        return handleGroup(match, entry.getValue(), globalConfig,
                                episodeCache, enabledDownloaders, downloaderLoadCache,
                                SearchLogService.SOURCE_RSS, blacklist);
                    }), executor))
                    .toList();
            int pushed = 0;
            for (CompletableFuture<Boolean> future : futures) {
                if (future.join()) {
                    pushed++;
                }
            }
            return pushed;
        }
    }

    /**
     * 供搜索补集复用：已知目标订阅与集号（-1=季包，电影恒为0），跳过 RSS 的批量匹配阶段，
     * 直接对候选种子走过滤择优 → 原子占位 → 落库 → 推送，与 {@link #process} 共用同一段核心逻辑。
     *
     * @return 是否成功推送了一个种子
     */
    public boolean pushBest(PtSubscriptionPlus sub, int episode, List<TorrentInfo> candidates) {
        PtFilterConfigPlus globalConfig = filterConfigService.getConfig();
        TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());
        // 调用方（如 SearchSupplementService）通常已经调用过 fillParsed，这里幂等地补一遍，
        // 防止遗漏解析导致 parsedReleaseGroup 缺失、发布组黑名单形同虚设
        for (TorrentInfo candidate : candidates) {
            fillParsed(candidate);
        }
        MatchResult match = new MatchResult(sub, episode);
        Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache = new LinkedHashMap<>();
        List<PtDownloaderPlus> enabledDownloaders = loadEnabledDownloaders();
        Map<Integer, Long> downloaderLoadCache = loadDownloaderLoadCounts(enabledDownloaders);
        return handleGroup(match, candidates, globalConfig, episodeCache,
                enabledDownloaders, downloaderLoadCache, SearchLogService.SOURCE_SUPPLEMENT, blacklist);
    }

    /**
     * @return 是否成功推送了一个种子
     */
    boolean handleGroup(MatchResult match, List<TorrentInfo> candidates,
                                PtFilterConfigPlus globalConfig,
                                Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache,
                                List<PtDownloaderPlus> enabledDownloaders,
                                Map<Integer, Long> downloaderLoadCache,
                                String source,
                                TorrentBlacklist blacklist) {
        PtSubscriptionPlus sub = match.getSubscription();
        List<PtSubscriptionEpisodePlus> allEpisodes = episodeCache.computeIfAbsent(
                sub.getId(), episodeService::listBySubscription);

        List<PtSubscriptionEpisodePlus> targets = resolveTargets(match, allEpisodes);
        if (targets.isEmpty()) {
            log.debug("订阅[{}] 集{} 无可占位的缺失集，跳过", sub.getId(), match.getEpisode());
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, "无可占位的缺失集（可能已入库或在途）");
            return false;
        }

        List<TorrentInfo> fresh = excludeAlreadyRecorded(candidates);
        if (fresh.isEmpty()) {
            if (candidates.isEmpty()) {
                log.debug("订阅[{}] 集{} 无可用候选种子（搜索未返回结果），跳过", sub.getId(), match.getEpisode());
                searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, "搜索未返回任何候选种子");
            } else {
                log.debug("订阅[{}] 集{} 的候选都有已有下载记录，跳过", sub.getId(), match.getEpisode());
                searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, "候选种子都已推送过，本轮跳过");
            }
            return false;
        }

        FilterCriteria criteria = FilterCriteriaFactory.build(globalConfig, sub.getFilterOverride());
        String originalLanguage = tmdbSearchService.getOriginalLanguage(sub.getMediaType(), sub.getTmdbId());
        List<TorrentFilterEngine.Verdict> verdicts = filterEngine.evaluate(fresh, criteria, blacklist, originalLanguage);
        searchLogService.recordVerdicts(sub.getId(), match.getEpisode(), source, verdicts);
        List<TorrentInfo> survivors = verdicts.stream()
                .filter(TorrentFilterEngine.Verdict::accepted)
                .map(TorrentFilterEngine.Verdict::torrent)
                .toList();
        TorrentInfo best = filterEngine.pickBest(survivors, criteria);
        if (best == null) {
            return false;
        }

        // 选中的种子可能是区间打包（如 E01-E02），实际覆盖的集数比调用方传入的单集目标（match.episode）更多——
        // pushBest()/SearchSupplementService 按缺失单集逐一搜索时，MatchResult 只带调用方传入的那一个集号，
        // episodeEnd 恒为 null（区间信息只在 RSS 路径的 SubscriptionMatcher.match() 里才会算出来）。
        // 这里按 best 实际解析出的区间重新计算 match 本身（而不仅仅是 targets），否则区间内除目标集外的
        // 其它缺失集：1) 不会被标成 IN_FLIGHT，永远显示"缺失"；2) trySelectFiles() 按 download_id 关联的
        // 集号做文件过滤，没占位的集会被当成"非目标文件"整个排除下载，实际根本没有在下载；3) 若只改
        // targets 不改 match，buildRecord 落库的 episode/episodeEnd 仍是旧的单集范围，
        // DownloadRecordAdminService#resetBlockedEpisodes 之后按这个范围重置会漏掉那一集，
        // 下载失败/被拉黑后该集会永久卡在 IN_FLIGHT 且没有对应下载记录可追踪回退。
        if (match.getEpisode() != SubscriptionMatcher.SEASON_PACK
                && best.getParsedEpisode() != null && best.getParsedEpisodeEnd() != null
                && best.getParsedEpisodeEnd() > best.getParsedEpisode()) {
            match = new MatchResult(sub, best.getParsedEpisode(), best.getParsedEpisodeEnd());
            targets = resolveTargets(match, allEpisodes);
        }

        // 「选下载器 + 判容量 + 占位自增」必须在同一把锁内原子完成：process() 用虚拟线程并行处理
        // 各分组，若三步分开做，两个分组可能都在对方自增前读到同一个"最闲"下载器，
        // 负载均衡形同虚设。downloaderLoadCache 在一次 process()/pushBest() 调用内唯一，
        // 用它本身做锁对象天然把锁粒度限制在本次调用范围。
        PtDownloaderPlus downloader;
        synchronized (downloaderLoadCache) {
            downloader = resolveDownloader(sub, enabledDownloaders, downloaderLoadCache);
            if (downloader == null) {
                log.warn("没有可用的下载器，订阅[{}] 本轮跳过", sub.getId());
                searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, "没有可用的下载器");
                return false;
            }

            if (isOverCapacity(downloader, downloaderLoadCache)) {
                log.debug("下载器[{}] 已达最大并发 {}，订阅[{}] 集{} 本轮跳过",
                        downloader.getId(), downloader.getMaxConcurrent(), sub.getId(), match.getEpisode());
                searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, "下载器并发已达上限");
                return false;
            }

            // 选中即先行 +1：让同一批次内后续分组（哪怕并发执行）也能立刻感知到这次占用，
            // 避免全部涌向"批次开始时最闲"的下载器；后续任何失败路径都要对称地 -1 回滚。
            downloaderLoadCache.merge(downloader.getId(), 1L, Long::sum);
        }

        // 原子占位：条件更新按影响行数判断，防止并发轮询给同一集推两个种子
        List<PtSubscriptionEpisodePlus> claimed = new ArrayList<>();
        for (PtSubscriptionEpisodePlus target : targets) {
            if (claim(target)) {
                claimed.add(target);
            }
        }
        if (claimed.isEmpty()) {
            log.debug("订阅[{}] 集{} 已被并发轮询占位，跳过", sub.getId(), match.getEpisode());
            downloaderLoadCache.merge(downloader.getId(), -1L, Long::sum);
            return false;
        }

        String guidHash = GuidHasher.hash(best.getGuid());
        PtDownloadRecordPlus record = buildRecord(sub, match, best, guidHash, downloader);
        boolean saved;
        try {
            saved = recordService.save(record);
        } catch (Exception e) {
            // 并发轮询下同一 guid 可能同时通过 excludeAlreadyRecorded 检查，
            // 落库时撞到 uk_indexer_guid 唯一索引会抛异常而非返回 false，需和 false 分支一样回滚，
            // 否则已占位的集会永久卡在 IN_FLIGHT 且没有对应下载记录可供后续追踪回退。
            log.warn("保存下载记录失败，已回滚：{}", best.getTitle(), e);
            saved = false;
        }
        if (!saved) {
            releaseAll(claimed);
            downloaderLoadCache.merge(downloader.getId(), -1L, Long::sum);
            return false;
        }

        try {
            String tags = downloader.getTag() + "," + record.getTrackingTag();
            downloaderClientFactory.get(downloader)
                    .addTorrent(downloader, best.getDownloadUrl(), downloader.getSavePath(), tags);
        } catch (Exception e) {
            log.error("推送种子到下载器失败，已回滚：{}", best.getTitle(), e);
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source,
                    "推送到下载器失败：" + e.getMessage());
            recordService.removeById(record.getId());
            releaseAll(claimed);
            downloaderLoadCache.merge(downloader.getId(), -1L, Long::sum);
            return false;
        }

        for (PtSubscriptionEpisodePlus ep : claimed) {
            ep.setDownloadId(record.getId());
            ep.setState(STATE_IN_FLIGHT);
        }
        episodeService.updateBatchById(claimed);

        sub.setLastMatchTime(new Date());
        subscriptionService.updateById(sub);
        PtStatusWebSocket.pushSubscriptionEvent(sub);

        log.info("订阅[{}] {} 已推送种子：{}（占位 {} 集）",
                sub.getId(), sub.getTitle(), best.getTitle(), claimed.size());
        return true;
    }

    /** 用本地解析结果填充种子的 parsedXxx 字段，不发任何网络请求 */
    void fillParsed(TorrentInfo torrent) {
        MediaInfo info = mediaParser.parseLocal(torrent.getTitle());
        // 注意：parseLocal 不做 TMDb 富化，MediaInfo.title 恒为 null
        // （TitleProcessor.processTitle 只写 originalTitle/englishTitle，见该类第46-48行的注释代码）。
        // 必须用 originalTitle，否则本地解析出的种子标题永远匹配不到任何订阅。
        torrent.setParsedTitle(info.getOriginalTitle());
        torrent.setParsedTitleEn(info.getEnglishTitle());
        torrent.setParsedYear(info.getYear());
        torrent.setParsedSeason(toInt(info.getSeason()));
        torrent.setParsedEpisode(toInt(info.getEpisode()));
        torrent.setParsedEpisodeEnd(toInt(info.getEpisodeEnd()));
        torrent.setParsedResolution(info.getResolution());
        torrent.setParsedSource(info.getSource());
        torrent.setParsedReleaseGroup(info.getReleaseGroup());
    }

    private Integer toInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 确定要占位的集：普通集就它自己，季包则是该订阅所有 MISSING 的集，
     * 区间匹配（如 S01E01-03）则是区间内所有 MISSING 的集。
     */
    private List<PtSubscriptionEpisodePlus> resolveTargets(MatchResult match,
                                                           List<PtSubscriptionEpisodePlus> allEpisodes) {
        List<PtSubscriptionEpisodePlus> targets = new ArrayList<>();
        if (match.getEpisode() == SubscriptionMatcher.SEASON_PACK) {
            for (PtSubscriptionEpisodePlus ep : allEpisodes) {
                if (STATE_MISSING.equals(ep.getState())) {
                    targets.add(ep);
                }
            }
            return targets;
        }
        if (match.getEpisodeEnd() != null) {
            for (PtSubscriptionEpisodePlus ep : allEpisodes) {
                if (ep.getEpisode() >= match.getEpisode() && ep.getEpisode() <= match.getEpisodeEnd()
                        && STATE_MISSING.equals(ep.getState())) {
                    targets.add(ep);
                }
            }
            return targets;
        }
        for (PtSubscriptionEpisodePlus ep : allEpisodes) {
            if (ep.getEpisode() == match.getEpisode() && STATE_MISSING.equals(ep.getState())) {
                targets.add(ep);
            }
        }
        return targets;
    }

    /**
     * 剔除已有下载记录的候选。按 {@code (indexer_id, guid_hash)} 判断——这正是表上的唯一约束，
     * 提前剔除既避免重复下载，也避免插入时撞约束。
     * <p>
     * 查询时按索引器分组批量查询 {@code WHERE indexer_id = ? AND guid_hash IN (...)}，
     * 严格匹配唯一约束语义，避免不同索引器的同 hash 种子互相误杀
     * （索引器降级使用 downloadUrl 作为 guid 时可能因 apikey 等参数模式不同而碰撞）。
     * </p>
     */
    private List<TorrentInfo> excludeAlreadyRecorded(List<TorrentInfo> candidates) {
        if (candidates.isEmpty()) {
            // 空列表直接返回：MyBatis-Plus 的 in() 遇到空集合会生成 "IN ()"，MySQL 语法错误。
            // RSS 路径下 candidates 恒非空（由 process() 的分组逻辑保证），但搜索补集路径
            // （SearchSupplementService）可能以空结果调用到这里，必须在查库前短路。
            return candidates;
        }
        // 按 indexer_id 分组，每组批量查一次，严格匹配唯一约束
        Map<Integer, Set<String>> indexerHashes = new HashMap<>();
        for (TorrentInfo t : candidates) {
            indexerHashes.computeIfAbsent(t.getIndexerId(), k -> new HashSet<>())
                    .add(GuidHasher.hash(t.getGuid()));
        }
        // indexer_id + guid_hash 组合键作为已存在标记
        Set<String> taken = new HashSet<>();
        for (Map.Entry<Integer, Set<String>> entry : indexerHashes.entrySet()) {
            List<PtDownloadRecordPlus> existing = recordService.list(
                    new QueryWrapper<PtDownloadRecordPlus>()
                            .eq("indexer_id", entry.getKey())
                            .in("guid_hash", entry.getValue()));
            for (PtDownloadRecordPlus record : existing) {
                taken.add(entry.getKey() + ":" + record.getGuidHash());
            }
        }
        List<TorrentInfo> fresh = new ArrayList<>();
        for (TorrentInfo torrent : candidates) {
            if (!taken.contains(torrent.getIndexerId() + ":" + GuidHasher.hash(torrent.getGuid()))) {
                fresh.add(torrent);
            }
        }
        return fresh;
    }

    /** 条件更新占位：只有仍是 MISSING 才能占位成功 */
    private boolean claim(PtSubscriptionEpisodePlus target) {
        PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
        set.setState(STATE_IN_FLIGHT);
        return episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                .eq("id", target.getId())
                .eq("state", STATE_MISSING));
    }

    /** 回滚占位 */
    private void releaseAll(List<PtSubscriptionEpisodePlus> claimed) {
        for (PtSubscriptionEpisodePlus ep : claimed) {
            PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
            set.setState(STATE_MISSING);
            episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                    .eq("id", ep.getId())
                    .eq("state", STATE_IN_FLIGHT));
        }
    }

    private PtDownloadRecordPlus buildRecord(PtSubscriptionPlus sub, MatchResult match, TorrentInfo torrent,
                                             String guidHash, PtDownloaderPlus downloader) {
        PtDownloadRecordPlus record = new PtDownloadRecordPlus();
        record.setSubId(sub.getId());
        record.setEpisode(match.getEpisode());
        record.setEpisodeEnd(match.getEpisodeEnd());
        record.setIndexerId(torrent.getIndexerId());
        record.setGuid(torrent.getGuid());
        record.setGuidHash(guidHash);
        // 插入前生成，不依赖自增 id：否则要「插入→回填 tag→推送」两次写库，
        // 中间崩溃会留下没有 tag、永远回映不到的失联种子
        record.setTrackingTag(TAG_PREFIX + guidHash.substring(0, 16));
        record.setTorrentHash(torrent.getInfoHash());
        record.setTitle(torrent.getTitle());
        record.setSize(torrent.getSize());
        record.setSeeders(torrent.getSeeders());
        record.setDownloaderId(downloader.getId());
        record.setState(RECORD_PUSHED);
        record.setPushedTime(new Date());
        return record;
    }

    /** 查询当前启用的下载器列表，供批内缓存复用 */
    private List<PtDownloaderPlus> loadEnabledDownloaders() {
        return downloaderService.list(new QueryWrapper<PtDownloaderPlus>().eq("enabled", "1"));
    }

    /** 统计每个启用下载器当前 PUSHED/DOWNLOADING 的在途记录数，供负载均衡使用 */
    private Map<Integer, Long> loadDownloaderLoadCounts(List<PtDownloaderPlus> enabledDownloaders) {
        if (enabledDownloaders.isEmpty()) {
            return new HashMap<>();
        }
        List<Integer> ids = enabledDownloaders.stream().map(PtDownloaderPlus::getId).toList();
        List<PtDownloadRecordPlus> active = recordService.list(new QueryWrapper<PtDownloadRecordPlus>()
                .in("downloader_id", ids)
                .in("state", RECORD_PUSHED, RECORD_DOWNLOADING));
        Map<Integer, Long> counts = new HashMap<>();
        for (PtDownloadRecordPlus r : active) {
            counts.merge(r.getDownloaderId(), 1L, Long::sum);
        }
        return counts;
    }

    /**
     * 订阅指定了下载器且该下载器仍启用就用它（不变，用户显式选择优先级最高）；
     * 否则从启用列表里选当前在途记录数最少的一个，并列时选列表里靠前的（顺序即数据库查询顺序，天然稳定）。
     */
    private PtDownloaderPlus resolveDownloader(PtSubscriptionPlus sub,
                                                List<PtDownloaderPlus> enabled,
                                                Map<Integer, Long> loadCache) {
        if (enabled.isEmpty()) {
            return null;
        }
        if (sub.getDownloaderId() != null) {
            for (PtDownloaderPlus downloader : enabled) {
                if (sub.getDownloaderId().equals(downloader.getId())) {
                    return downloader;
                }
            }
            log.warn("订阅[{}] 指定的下载器 {} 不可用，改用负载最低的启用下载器", sub.getId(), sub.getDownloaderId());
        }
        PtDownloaderPlus best = enabled.get(0);
        long bestLoad = loadCache.getOrDefault(best.getId(), 0L);
        for (int i = 1; i < enabled.size(); i++) {
            PtDownloaderPlus candidate = enabled.get(i);
            long load = loadCache.getOrDefault(candidate.getId(), 0L);
            if (load < bestLoad) {
                best = candidate;
                bestLoad = load;
            }
        }
        return best;
    }

    /**
     * 目标下载器是否已达最大并发。{@code maxConcurrent} 为 null 或 &lt;=0 视为不限
     * （与 pt_filter_config 里 min_size/max_size 用 0 表示"不限"的既有约定一致）。
     * <p>
     * 直接复用调用方（{@link #process}/{@link #pushBest}）已经查好的 {@code loadCache}
     * （key=下载器id，value=当前 PUSHED/DOWNLOADING 在途记录数，见 {@link #loadDownloaderLoadCounts}），
     * 不再对 {@code recordService} 发起第二条 COUNT 查询——这条查询已经覆盖了所有启用下载器，
     * 且 {@link #handleGroup} 推送成功后会对该 Map 就地 {@code +1}，同一批次内的后续分组
     * 天然能感知到这次推送占用的名额。
     * </p>
     */
    private boolean isOverCapacity(PtDownloaderPlus downloader, Map<Integer, Long> loadCache) {
        Integer max = downloader.getMaxConcurrent();
        if (max == null || max <= 0) {
            return false;
        }
        long active = loadCache.getOrDefault(downloader.getId(), 0L);
        return active >= max;
    }
}
