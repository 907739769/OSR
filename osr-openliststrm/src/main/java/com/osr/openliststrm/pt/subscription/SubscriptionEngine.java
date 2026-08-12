package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.enums.PtSmartClassifyLevelEnum;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.notify.NotifyTarget;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.filter.EpisodeCountResolver;
import com.osr.openliststrm.pt.filter.FilterCriteria;
import com.osr.openliststrm.pt.filter.FilterCriteriaFactory;
import com.osr.openliststrm.pt.filter.RejectCode;
import com.osr.openliststrm.pt.filter.TorrentBlacklist;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.indexer.GuidHasher;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.MatchResult;
import com.osr.openliststrm.pt.task.DownloadRecordState;
import com.osr.openliststrm.pt.task.FailReasonCode;
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
import java.util.Locale;
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
    private static final String STATE_IN_LIBRARY = SubscriptionEpisodeState.IN_LIBRARY.value();
    private static final String STATE_UPGRADING = SubscriptionEpisodeState.UPGRADING.value();
    private static final String RECORD_PUSHED = DownloadRecordState.PUSHED.value();
    private static final String RECORD_DOWNLOADING = DownloadRecordState.DOWNLOADING.value();
    private static final String RECORD_FAILED = DownloadRecordState.FAILED.value();

    /** 唯一标签前缀，用于把下载记录回映到下载器里的种子 */
    private static final String TAG_PREFIX = "osr-pt-";

    /** 全淘汰摘要里最多列举几类淘汰原因 */
    private static final int REJECT_SUMMARY_TOP_N = 3;

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
    private final IPtIndexerPlusService indexerService;

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
                              TmdbSearchService tmdbSearchService,
                              IPtIndexerPlusService indexerService) {
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
        this.indexerService = indexerService;
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

        markHitAndRun(torrents);

        // 匹配+分组：纯内存计算，保持串行
        Map<String, List<TorrentInfo>> groups = new LinkedHashMap<>();
        Map<String, MatchResult> groupMatch = new LinkedHashMap<>();
        // 绝对编号映射按订阅一次性建好：RSS 一轮要过几百条种子，不能每条都查一次集表
        Map<Integer, AbsoluteEpisodeMap> absoluteMaps = buildAbsoluteMaps(subscriptions);

        for (TorrentInfo torrent : torrents) {
            fillParsed(torrent);
            MatchResult match = matcher.match(torrent, subscriptions, absoluteMaps);
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
                                SearchLogService.SOURCE_RSS, blacklist, PushMode.FILL_MISSING);
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
        // 搜索补集/手动推送同样要打 H&R 标记，否则 avoidHitAndRun 只在 RSS 路径生效，
        // 用户会看到"自动下载避开了 H&R 站，手动搜索却照样推了一个"这种前后不一致
        markHitAndRun(candidates);
        MatchResult match = new MatchResult(sub, episode);
        Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache = new LinkedHashMap<>();
        List<PtDownloaderPlus> enabledDownloaders = loadEnabledDownloaders();
        Map<Integer, Long> downloaderLoadCache = loadDownloaderLoadCounts(enabledDownloaders);
        return handleGroup(match, candidates, globalConfig, episodeCache,
                enabledDownloaders, downloaderLoadCache, SearchLogService.SOURCE_SUPPLEMENT, blacklist,
                PushMode.FILL_MISSING);
    }

    /**
     * 供洗版复用：已知目标订阅与集号（该集必须处于 IN_LIBRARY），把一个质量更好的候选推给下载器。
     * <p>
     * 与 {@link #pushBest} 走同一条主干，只是模式不同——占位是 IN_LIBRARY → UPGRADING，
     * 失败退回 IN_LIBRARY，且不做区间展开。候选是否<b>确实更优</b>由调用方
     * （{@code UpgradeScanService}）用 {@code UpgradeEvaluator} 判过，本方法不重复判定：
     * 引擎只负责"把选中的这个推下去并落好账"。
     * </p>
     *
     * @return 是否成功推送了一个种子
     */
    public boolean pushUpgrade(PtSubscriptionPlus sub, int episode, List<TorrentInfo> candidates) {
        PtFilterConfigPlus globalConfig = filterConfigService.getConfig();
        TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());
        for (TorrentInfo candidate : candidates) {
            fillParsed(candidate);
        }
        markHitAndRun(candidates);
        MatchResult match = new MatchResult(sub, episode);
        Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache = new LinkedHashMap<>();
        List<PtDownloaderPlus> enabledDownloaders = loadEnabledDownloaders();
        Map<Integer, Long> downloaderLoadCache = loadDownloaderLoadCounts(enabledDownloaders);
        return handleGroup(match, candidates, globalConfig, episodeCache,
                enabledDownloaders, downloaderLoadCache, SearchLogService.SOURCE_SUPPLEMENT, blacklist,
                PushMode.UPGRADE);
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
                                TorrentBlacklist blacklist,
                                PushMode mode) {
        PtSubscriptionPlus sub = match.getSubscription();
        List<PtSubscriptionEpisodePlus> allEpisodes = episodeCache.computeIfAbsent(
                sub.getId(), episodeService::listBySubscription);

        List<PtSubscriptionEpisodePlus> targets = resolveTargets(match, allEpisodes, mode);
        if (targets.isEmpty()) {
            String reason = mode.isUpgrade()
                    ? "该集已不在可洗版状态（可能已被其它轮次占位或退回缺失）"
                    : "无可占位的缺失集（可能已入库或在途）";
            log.debug("订阅[{}] 集{} {}，跳过", sub.getId(), match.getEpisode(), reason);
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, reason);
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

        // 季包目标下，先把「文件数证明覆盖不全」的候选让位给不矛盾的候选，再进过滤择优
        if (!mode.isUpgrade() && match.getEpisode() == SubscriptionMatcher.SEASON_PACK) {
            fresh = preferCompletePacks(fresh, targets.size());
        }

        FilterCriteria criteria = FilterCriteriaFactory.build(globalConfig, sub.getFilterOverride());
        // 体积阈值按每集判定时要知道候选覆盖多少集，必须赶在 evaluate/pickBest 之前算好
        EpisodeCountResolver.apply(fresh, sub.getTotalEpisodes(),
                SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType()));
        String originalLanguage = tmdbSearchService.getOriginalLanguage(sub.getMediaType(), sub.getTmdbId());
        List<TorrentFilterEngine.Verdict> verdicts = filterEngine.evaluate(fresh, criteria, blacklist, originalLanguage);
        searchLogService.recordVerdicts(sub.getId(), match.getEpisode(), source, verdicts);
        List<TorrentInfo> survivors = verdicts.stream()
                .filter(TorrentFilterEngine.Verdict::accepted)
                .map(TorrentFilterEngine.Verdict::torrent)
                .toList();
        TorrentInfo best = filterEngine.pickBest(survivors, criteria);
        if (best == null) {
            // 走到这里说明 fresh 非空（上面已短路）却一个幸存者都没有——候选是被过滤规则全清的。
            // 原先这里直接 return false，什么都不说：用户看到的是"没搜到资源"，实际是自己配的
            // freeOnly / 分辨率白名单把 103 个候选全挡了，而通知还提示他去检查索引器配置。
            // 按 RejectCode 聚合而不是按 reason 文本：文案里嵌着实际值，按文本分组只会得到
            // 一堆计数为 1 的碎片，看不出主要卡在哪条规则上。
            String summary = summarizeRejections(verdicts);
            log.info("订阅[{}] 集{} {}", sub.getId(), match.getEpisode(), summary);
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, summary);
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
        // 洗版刻意跳过这段区间展开：洗版的语义是「把这一集换个更好的版本」，
        // 用一个区间包去覆盖会连带动到区间内那些没打算升级的集——它们的质量基线没被比较过，
        // 很可能被换成更差的版本，而且那些集根本没被 claim，状态与实际下载内容会对不上。
        if (!mode.isUpgrade()
                && match.getEpisode() != SubscriptionMatcher.SEASON_PACK
                && best.getParsedEpisode() != null && best.getParsedEpisodeEnd() != null
                && best.getParsedEpisodeEnd() > best.getParsedEpisode()) {
            match = new MatchResult(sub, best.getParsedEpisode(), best.getParsedEpisodeEnd());
            targets = resolveTargets(match, allEpisodes, mode);
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
            if (claim(target, mode)) {
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
            // excludeAlreadyRecorded 放行了可重试的 FAILED 记录，此时表里已有同 (indexer_id, guid_hash)
            // 的那一行，插新行必撞 uk_indexer_guid。复用原行而不是先删后插：下载记录页保留同一个 id，
            // tracking_tag 本就由 guidHash 派生、复用后仍与下载器里的种子标签对得上。
            PtDownloadRecordPlus reusable = reusableFailedRecord(best.getIndexerId(), guidHash);
            saved = (reusable == null) ? recordService.save(record) : reuse(reusable, record);
        } catch (Exception e) {
            // 并发轮询下同一 guid 可能同时通过 excludeAlreadyRecorded 检查，
            // 落库时撞到 uk_indexer_guid 唯一索引会抛异常而非返回 false，需和 false 分支一样回滚，
            // 否则已占位的集会永久卡在 IN_FLIGHT 且没有对应下载记录可供后续追踪回退。
            log.warn("保存下载记录失败，已回滚：{}", best.getTitle(), e);
            saved = false;
        }
        if (!saved) {
            releaseAll(claimed, mode);
            downloaderLoadCache.merge(downloader.getId(), -1L, Long::sum);
            return false;
        }

        try {
            String tags = downloader.getTag() + "," + record.getTrackingTag();
            downloaderClientFactory.get(downloader)
                    .addTorrent(downloader, best.getDownloadUrl(), resolveSavePath(downloader, sub), tags,
                            shouldPauseOnAdd(match, best));
        } catch (Exception e) {
            log.error("推送种子到下载器失败，已回滚：{}", best.getTitle(), e);
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source,
                    "推送到下载器失败：" + e.getMessage());
            recordService.removeById(record.getId());
            releaseAll(claimed, mode);
            downloaderLoadCache.merge(downloader.getId(), -1L, Long::sum);
            return false;
        }

        for (PtSubscriptionEpisodePlus ep : claimed) {
            ep.setDownloadId(record.getId());
            ep.setState(mode.isUpgrade() ? STATE_UPGRADING : STATE_IN_FLIGHT);
        }
        episodeService.updateBatchById(claimed);

        sub.setLastMatchTime(new Date());
        subscriptionService.updateById(sub);
        PtStatusWebSocket.pushSubscriptionEvent(sub);

        log.info("订阅[{}] {} 已推送{}种子：{}（占位 {} 集）",
                sub.getId(), sub.getTitle(), mode.isUpgrade() ? "洗版" : "", best.getTitle(), claimed.size());
        if (mode.isUpgrade()) {
            // 第一期不碰旧文件：OSR 从不删种，新旧两个版本会同时存在，清理由用户手动完成。
            // 通知里必须把这件事说清楚，否则用户会以为系统已经替换好了。
            notifySafely(NotificationType.SUBSCRIPTION_HIT, "⬆️ 洗版已推送：《" + StringUtils.escapeHtml(sub.getTitle()) + "》"
                    + describeEpisodes(match) + "\n" + StringUtils.escapeHtml(best.getTitle())
                    + "\n已推送至下载器：" + StringUtils.escapeHtml(downloader.getName())
                    + "\n⚠️ 旧版本不会被自动删除，新版本下载完成后请自行清理", sub);
        } else if (match.getEpisode() == SubscriptionMatcher.SEASON_PACK) {
            // 季包的命中通知刻意延后到 DownloadTrackService#trySelectFiles 确认包内确实含目标集之后再发，
            // 补发点见 DownloadTrackService#notifySeasonPackHit。两边的判据都是「episode == SEASON_PACK」，
            // 必须保持一致，否则会漏发或重复发。
            //
            // 「有季无集」的标题（如 HHWEB 日更剧只写 S01 不带集号）一律被判成季包，而它实际可能只有
            // 一集，且未必是本次要补的那一集——推送那一刻没有任何信号能分辨，只有下载器给出文件列表
            // 才知道。原先在这里就发「订阅命中」，于是用户先收到一条命中、紧接着收到一条「种子内不含
            // 任何目标集，已中止」，一次白跑发两条通知，日更剧每天要刷好几轮。
            log.info("订阅[{}] 季包已推送，命中通知延后到文件列表确认后：{}", sub.getId(), best.getTitle());
        } else {
            notifySafely(NotificationType.SUBSCRIPTION_HIT, "📌 订阅命中：《" + StringUtils.escapeHtml(sub.getTitle()) + "》"
                    + describeEpisodes(match) + "\n" + StringUtils.escapeHtml(best.getTitle())
                    + "\n已推送至下载器：" + StringUtils.escapeHtml(downloader.getName()), sub);
        }
        return true;
    }

    /**
     * 季包择优前的收窄：把「文件数已经证明它覆盖不全」的候选剔掉，前提是同组里还有不矛盾的候选。
     * <p>
     * 「按季包命名、实际只含 1 集」的种子与真季包在标题上一模一样，体积也分不开——8GB 可能是
     * 8 集 × 1GB，也可能是 1 集 Remux。唯一的硬判据是 {@code files}（种子内文件总数）：包内集数
     * 不可能超过文件总数，{@code files < 待占位集数} 就<b>一定</b>覆盖不全，这是算术，不是启发式。
     * </p>
     * <p>
     * 不剔除的代价是实打实的：假季包只要在做种数等任一维度上赢下 {@link TorrentFilterEngine#pickBest}，
     * 就会走 {@link #resolveTargets} 的季包分支占位<b>整季</b>缺失集；等元数据解析完，
     * {@code DownloadTrackService#reconcileClaims} 再把其余集退回 MISSING，下一轮才轮到真季包
     * ——而那时它只能占到剩下的集。最终这一季被拆成两个种子下载，多一份 H&R 保种义务，
     * 且先下的那一集与其余集大概率不是同一个版本。
     * </p>
     * <p>
     * 三条保守约束：
     * <ul>
     *   <li>{@code files} 为 null（索引器未提供该属性）的候选一律算"不矛盾"，不做任何推断——
     *       判据缺失时维持既有行为，交给 {@code reconcileClaims} 事后对账兜底。</li>
     *   <li>只占 1 集时直接返回：{@code files >= 1} 恒成立，判据本身不成立。</li>
     *   <li>候选<b>全部</b>覆盖不全时不剔除：宁可下一个只覆盖部分集的包（剩下的集由
     *       {@code reconcileClaims} 退回后继续搜），也不能因为没有完美候选就一集都不补。</li>
     * </ul>
     * </p>
     */
    private List<TorrentInfo> preferCompletePacks(List<TorrentInfo> candidates, int targetCount) {
        if (targetCount < 2) {
            return candidates;
        }
        List<TorrentInfo> complete = new ArrayList<>();
        List<TorrentInfo> incomplete = new ArrayList<>();
        for (TorrentInfo candidate : candidates) {
            Integer files = candidate.getFiles();
            if (files != null && files < targetCount) {
                incomplete.add(candidate);
            } else {
                complete.add(candidate);
            }
        }
        if (incomplete.isEmpty() || complete.isEmpty()) {
            return candidates;
        }
        for (TorrentInfo dropped : incomplete) {
            log.debug("季包候选被文件数判据剔除：{} —— 种子内仅 {} 个文件，覆盖不了本次要占位的 {} 集",
                    dropped.getTitle(), dropped.getFiles(), targetCount);
        }
        return complete;
    }

    /**
     * 把「全部候选被过滤规则淘汰」聚合成一句人话，按 {@link RejectCode} 分类而不是按原因文案。
     * <p>
     * 只列前 {@link #REJECT_SUMMARY_TOP_N} 类——用户需要的是判断方向（"哦是我把 freeOnly 打开了"），
     * 完整明细本来就由 {@code recordVerdicts} 逐条落在 {@code pt_search_log} 里。
     * </p>
     */
    private String summarizeRejections(List<TorrentFilterEngine.Verdict> verdicts) {
        Map<RejectCode, Long> byCode = verdicts.stream()
                .filter(v -> !v.accepted())
                .collect(java.util.stream.Collectors.groupingBy(
                        TorrentFilterEngine.Verdict::rejectCode,
                        LinkedHashMap::new,
                        java.util.stream.Collectors.counting()));
        String detail = byCode.entrySet().stream()
                .sorted(Map.Entry.<RejectCode, Long>comparingByValue().reversed())
                .limit(REJECT_SUMMARY_TOP_N)
                .map(e -> e.getValue() + " 个「" + e.getKey().label() + "」")
                .collect(java.util.stream.Collectors.joining("、"));
        return verdicts.size() + " 个候选全部被过滤规则淘汰：" + detail;
    }

    /**
     * 这个种子要不要以<b>暂停态</b>加入下载器，等 {@code DownloadTrackService#trySelectFiles}
     * 按目标集选完文件再启动？
     * <p>
     * 只有<b>多集包</b>需要。推送那一刻谁也不知道包里究竟有哪几集——判据（下载器给出的
     * 真实文件列表）要等元数据解析完才拿得到。不暂停的话，这段窗口期里非目标集的文件
     * 已经在下了，几十 GB 的季包可能白下掉大半；更糟的是包内一集目标都没有时，
     * 那些流量完全是白烧的。
     * </p>
     * <p>
     * <b>单集种子一律不暂停</b>：它没有"选错文件"的可能，{@code trySelectFiles} 对它几乎是
     * 空操作，暂停只会白白多等一轮 30 秒轮询。电影同理（集号是哨兵 0，本就不做文件过滤）。
     * </p>
     * <p>
     * <b>磁力链一律不暂停</b>：下载器在暂停态下不会去下载磁力的元数据，{@code listFiles}
     * 会永远返回空列表，种子就永远停在暂停、永远等不到启动——比不暂停糟得多。
     * .torrent 链接的元数据在种子文件里，加进来就能读，不受影响。
     * </p>
     */
    private boolean shouldPauseOnAdd(MatchResult match, TorrentInfo best) {
        if (isMagnet(best.getDownloadUrl())
                || SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(match.getSubscription().getMediaType())) {
            return false;
        }
        if (match.getEpisode() == SubscriptionMatcher.SEASON_PACK) {
            return true;
        }
        // 区间包（E01-E04 这类）与季包同理：覆盖多集，同样要等文件列表才能定夺
        return best.getParsedEpisode() != null && best.getParsedEpisodeEnd() != null
                && best.getParsedEpisodeEnd() > best.getParsedEpisode();
    }

    private boolean isMagnet(String downloadUrl) {
        return downloadUrl != null && downloadUrl.trim().toLowerCase(Locale.ROOT).startsWith("magnet:");
    }

    /** 电影不带季集号；季包整季提示；单集/区间正常拼 SxxEyy，episode/episodeEnd 已在上面按 best 重算过 */
    private String describeEpisodes(MatchResult match) {
        PtSubscriptionPlus sub = match.getSubscription();
        if (SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())) {
            return "";
        }
        if (match.getEpisode() == SubscriptionMatcher.SEASON_PACK) {
            return " S" + sub.getSeason() + " 全季";
        }
        Integer end = match.getEpisodeEnd();
        return (end != null && end > match.getEpisode())
                ? " S" + sub.getSeason() + "E" + match.getEpisode() + "-E" + end
                : " S" + sub.getSeason() + "E" + match.getEpisode();
    }

    /** 发通知但绝不让通知失败影响主流程（单测环境下 SpringUtils.getBean 会抛异常，这里兜住） */
    private void notifySafely(NotificationType type, String msg, PtSubscriptionPlus sub) {
        try {
            TgHelper.sendMsg(type, msg, NotifyTarget.owner(sub == null ? null : sub.getOwnerUserId()));
        } catch (Exception e) {
            log.debug("发送通知失败（不影响主流程）：{}", e.getMessage());
        }
    }

    private static final String CLASSIFY_CATEGORY_MOVIE = "电影";
    private static final String CLASSIFY_CATEGORY_TV = "剧集";
    private static final String CLASSIFY_YEAR_UNKNOWN = "未分类";

    /**
     * 按下载器的智能分类级别，在 save_path 后拼接分类子目录。
     * 年份取 {@code sub.getYear()}——订阅创建时写入的首播年份，后续不再变化，
     * 同一订阅的所有季会落在同一个年份目录下，不会因为当前抓取到哪一季而漂移。
     */
    private String resolveSavePath(PtDownloaderPlus downloader, PtSubscriptionPlus sub) {
        String base = downloader.getSavePath();
        PtSmartClassifyLevelEnum level = PtSmartClassifyLevelEnum.getByCode(downloader.getSmartClassifyLevel());
        if (level == PtSmartClassifyLevelEnum.NONE) {
            return base;
        }
        String category = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(sub.getMediaType())
                ? CLASSIFY_CATEGORY_MOVIE : CLASSIFY_CATEGORY_TV;
        String path = stripTrailingSlash(base) + "/" + category;
        if (level == PtSmartClassifyLevelEnum.CATEGORY_YEAR) {
            String year = com.osr.common.utils.StringUtils.isNotBlank(sub.getYear()) ? sub.getYear() : CLASSIFY_YEAR_UNKNOWN;
            path = path + "/" + year;
        }
        return path;
    }

    private static String stripTrailingSlash(String path) {
        if (path == null) {
            return "";
        }
        int end = path.length();
        while (end > 0 && (path.charAt(end - 1) == '/' || path.charAt(end - 1) == '\\')) {
            end--;
        }
        return path.substring(0, end);
    }

    /**
     * 按来源索引器给候选打上 H&R 站点标记，供过滤引擎规避/降权。
     * <p>
     * 一次查全部索引器建成 Map 再逐条打标，而不是逐个候选查库——一轮 RSS 有几十上百条候选，
     * 逐条查等于把 30 秒的轮询拖成分钟级。索引器查不到（已删除）时保持 false：
     * 判不出来就按"不考核"处理，宁可少规避一次，也不能凭空把一批正常候选当成 H&R 淘汰掉。
     * </p>
     */
    private void markHitAndRun(List<TorrentInfo> torrents) {
        Set<Integer> hrIndexerIds = indexerService.list().stream()
                .filter(PtIndexerPlus::hitAndRunEnabled)
                .map(PtIndexerPlus::getId)
                .collect(java.util.stream.Collectors.toSet());
        if (hrIndexerIds.isEmpty()) {
            return;
        }
        for (TorrentInfo torrent : torrents) {
            torrent.setHitAndRun(torrent.getIndexerId() != null && hrIndexerIds.contains(torrent.getIndexerId()));
        }
    }

    /**
     * 用本地解析结果填充种子的 parsedXxx 字段，不发任何网络请求。
     * <p>
     * 公开而非包内可见：洗版扫描（{@code pt.upgrade} 包）要先拿到解析结果才能判断
     * "这个候选对不对应目标集"与"它是不是更好"，而那些判断必须发生在推送之前。
     * </p>
     */
    /**
     * 为这批订阅建「绝对集号 → 本地集号」映射，只有真用绝对编号的订阅会进结果。
     * <p>
     * 一次查出全部相关集行再按订阅分组，避免 N 个订阅打 N 次库。
     * </p>
     */
    private Map<Integer, AbsoluteEpisodeMap> buildAbsoluteMaps(List<PtSubscriptionPlus> subscriptions) {
        List<Integer> subIds = subscriptions.stream()
                .map(PtSubscriptionPlus::getId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (subIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, List<PtSubscriptionEpisodePlus>> bySub = episodeService.list(
                        new LambdaQueryWrapper<PtSubscriptionEpisodePlus>()
                                .in(PtSubscriptionEpisodePlus::getSubId, subIds)
                                .isNotNull(PtSubscriptionEpisodePlus::getTmdbEpisodeNumber))
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(PtSubscriptionEpisodePlus::getSubId));

        Map<Integer, AbsoluteEpisodeMap> result = new HashMap<>();
        bySub.forEach((subId, episodes) -> {
            AbsoluteEpisodeMap map = AbsoluteEpisodeMap.from(episodes);
            if (!map.isEmpty()) {
                result.put(subId, map);
            }
        });
        return result;
    }

    public void fillParsed(TorrentInfo torrent) {
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
        applySeasonPackRange(torrent);
        applyDescriptionEpisode(torrent);
        torrent.setParsedResolution(info.getResolution());
        torrent.setParsedSource(info.getSource());
        torrent.setParsedReleaseGroup(info.getReleaseGroup());
        torrent.setParsedTags(collectTags(info));
    }

    /**
     * 「有季无集」的种子若在标题里写明了集数区间（{@code [01-26]}、{@code 第01-26话}），
     * 把它补进 parsedEpisode/parsedEpisodeEnd，让下游按<b>区间</b>而不是<b>整季包</b>处理。
     * <p>
     * 不补的话 {@link SubscriptionMatcher} 会判成季包，而季包会占位该订阅的全部缺失集：
     * 一部 50 集的番分成上下两部分发布时，先来的那半个包会把 50 集全标成在途，
     * 后 24 集既下不到也不会退回缺失（补搜与 RSS 只认 MISSING），永久卡死。
     * </p>
     * <p>
     * 只对判不出集号的种子生效，绝不覆盖已解析出的集号——{@code S01E05} 这类标题里
     * 若碰巧还有个 {@code [01-26]} 的合集标注，集号必须以 E05 为准。
     * </p>
     */
    private void applySeasonPackRange(TorrentInfo torrent) {
        if (torrent.getParsedSeason() == null || torrent.getParsedEpisode() != null) {
            return;
        }
        SeasonPackRange.Range range = SeasonPackRange.parse(torrent.getTitle());
        if (range == null) {
            return;
        }
        torrent.setParsedEpisode(range.start());
        torrent.setParsedEpisodeEnd(range.end());
        log.debug("季包标题带集数区间，按 E{}-E{} 处理而非整季：{}", range.start(), range.end(), torrent.getTitle());
    }

    /**
     * 标题里既没有集号也没有集数区间时，退而从 {@code description} 里找，见 {@link DescriptionEpisode}。
     * <p>
     * 针对的是「同一季每集都发成同一个标题」的发布组（HHWEB 一类日更剧）：集号只存在于
     * description。不补的话这些种子全部被判成整季包，占位订阅的全部缺失集，推送后才由
     * {@code DownloadTrackService#trySelectFiles} 从文件列表发现「包里那一集不是要补的集」
     * 而中止——每天每集白跑一轮，还要按转发站点数乘一遍。
     * </p>
     * <p>
     * 三条约束与 {@link #applySeasonPackRange} 同源：
     * </p>
     * <ul>
     *   <li><b>只在判不出集号时生效</b>，绝不覆盖标题解析出的集号。description 是站点自填的
     *       自由文本，可靠性低于遵循命名规范的标题；{@code S01E05} 的种子哪怕 description 里
     *       写着别的数字，也必须以 E05 为准。</li>
     *   <li><b>要求已解析出季号</b>：{@code SubscriptionMatcher} 对没有季号的剧集种子直接不匹配，
     *       补出集号也无处可用，平白多一次正则开销和一条误判的可能。</li>
     *   <li><b>判不出来就维持现状</b>（当整季包处理），由下载器的真实文件列表事后对账兜底。
     *       本方法只是把「不知道包里是第几集」的窗口期从「推送到元数据解析完成」提前到推送之前。</li>
     * </ul>
     */
    private void applyDescriptionEpisode(TorrentInfo torrent) {
        if (torrent.getParsedSeason() == null || torrent.getParsedEpisode() != null) {
            return;
        }
        DescriptionEpisode.Episodes episodes = DescriptionEpisode.parse(torrent.getDescription());
        if (episodes == null) {
            return;
        }
        torrent.setParsedEpisode(episodes.start());
        // 单集时 episodeEnd 必须留 null：下游多处按「end != null && end > start」判区间，
        // 填一个等于 start 的值只是把同一件事换个说法，却会让区间展开逻辑多一种要考虑的形态
        torrent.setParsedEpisodeEnd(episodes.isRange() ? episodes.end() : null);
        log.debug("标题无集号，已从 description 补出 E{}{}：{}", episodes.start(),
                episodes.isRange() ? "-E" + episodes.end() : "", torrent.getTitle());
    }

    /**
     * 汇总一条种子的质量标签，供过滤引擎的 requiredTags/excludeTags 使用。
     * <p>
     * 取 {@code MediaInfo.tags}（REMUX/HDR10/10BIT/Dolby Vision…）<b>加上</b>视频编码与音频编码。
     * 后两者必须并进来：extractor 是按 Resolution → Codec → SourceAndGroup 顺序跑的，
     * {@code CodecExtractor} 的 AUDIO 正则先一步匹掉了 "Atmos" 并把它记进 {@code audioCodec}、
     * 同时从标题里抹掉，等 {@code SourceAndGroupExtractor} 再扫 TAGS 时已经找不到它了。
     * 只读 tags 的话，「必须带 Atmos」这种最常见的配置会一条候选都匹配不上，
     * 而用户完全看不出是解析顺序把标签吞了。H265/DTS-HD 同理。
     * </p>
     * <p>
     * 按大写去重：tags 里多为大写、编码归一化后是 "H265"/"Atmos"/"DTS-HD" 这类混合大小写，
     * 同一个标签可能两边都出现。保留首次出现的原始写法，淘汰原因里展示时更贴近种子标题。
     * </p>
     */
    private List<String> collectTags(MediaInfo info) {
        Map<String, String> byUpper = new LinkedHashMap<>();
        List<String> parsed = info.getTags();
        if (parsed != null) {
            for (String tag : parsed) {
                if (StringUtils.isNotBlank(tag)) {
                    byUpper.putIfAbsent(tag.toUpperCase(Locale.ROOT), tag);
                }
            }
        }
        for (String codec : new String[]{info.getVideoCodec(), info.getAudioCodec()}) {
            if (StringUtils.isNotBlank(codec)) {
                byUpper.putIfAbsent(codec.toUpperCase(Locale.ROOT), codec);
            }
        }
        return new ArrayList<>(byUpper.values());
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
                                                           List<PtSubscriptionEpisodePlus> allEpisodes,
                                                           PushMode mode) {
        List<PtSubscriptionEpisodePlus> targets = new ArrayList<>();
        if (mode.isUpgrade()) {
            // 洗版只动调用方指定的那一集，且它必须仍处于 IN_LIBRARY：
            // 已经在洗（UPGRADING）或已退回缺失的集都不该被再占一次
            for (PtSubscriptionEpisodePlus ep : allEpisodes) {
                if (ep.getEpisode() == match.getEpisode() && STATE_IN_LIBRARY.equals(ep.getState())) {
                    targets.add(ep);
                }
            }
            return targets;
        }
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
     * <p>
     * <b>可重试的 FAILED 记录不算"已记录"</b>（判定见 {@link FailReasonCode#isRetryable}）。
     * 本方法原先不看 state，于是一条 FAILED 记录会把对应种子对该索引器永久封死：
     * 失败原因哪怕只是下载器重启把任务弄丢了（{@code TORRENT_NOT_FOUND}），该集当前最优的
     * 那个种子也再无机会被选中，若该集只有这一个资源，{@code DownloadRecordAdminService#retry}
     * 重搜多少次都补不回来——集会一直卡在缺失，且没有任何地方说得出为什么。
     * 放行后由 {@link #reusableFailedRecord} 复用原行落库，不会撞唯一索引；
     * 同一集的无限重试则由 {@code pt_subscription_episode.fail_count} 的熔断阈值兜住。
     * </p>
     * <p>
     * <b>不要再加一层"按标题跨索引器剔除"</b>：日更剧的发布组（HHWEB 一类）会把同一季所有集
     * 发成标题<b>逐字相同</b>的种子，集号只出现在 {@code description} 里。按标题连坐会让一条
     * {@code NO_TARGET_EPISODE} 把该发布组这一版本的<b>全部后续集</b>永久烧掉，包括还没发布的。
     * 同一个种子在多站转发造成的重复推送，靠 {@code SubscriptionEngine#fillParsed} 从
     * description 补出集号来根治——那才是能把它们区分开的判据。
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
                if (isRetryableFailure(record)) {
                    continue;
                }
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

    /** 这条记录是不是"失败了但还允许该种子被重新选中"的那种 */
    private boolean isRetryableFailure(PtDownloadRecordPlus record) {
        return RECORD_FAILED.equals(record.getState())
                && FailReasonCode.isRetryable(record.getFailReasonCode());
    }

    /**
     * 查该 {@code (indexer_id, guid_hash)} 上是否躺着一条可重试的 FAILED 记录。
     * 只对择优选中的那一个种子查一次，不是对全部候选逐条查。
     */
    private PtDownloadRecordPlus reusableFailedRecord(Integer indexerId, String guidHash) {
        PtDownloadRecordPlus existing = recordService.getOne(new QueryWrapper<PtDownloadRecordPlus>()
                .eq("indexer_id", indexerId)
                .eq("guid_hash", guidHash)
                .eq("state", RECORD_FAILED), false);
        return (existing != null && isRetryableFailure(existing)) ? existing : null;
    }

    /**
     * 把可重试的失败行改写成一条新的推送记录。
     * <p>
     * 用 {@code UpdateWrapper} 显式把上一次的失败痕迹置空——MyBatis-Plus 默认跳过实体里的 null 字段，
     * 只靠实体更新的话 {@code fail_reason}/{@code fail_reason_code}/{@code completed_time} 会原样留着，
     * 前端下载记录页会显示成"正在下载，但带着上次的失败原因"。{@code files_selected} 必须回到 false，
     * 否则 {@link com.osr.openliststrm.pt.task.DownloadTrackService} 会以为文件已经筛过，跳过按集过滤。
     * </p>
     * <p>
     * {@code state = FAILED} 是更新条件而非仅仅是查询条件：并发轮询下两个分组可能同时查到同一行，
     * 影响行数为 0 说明已被别人抢先复用，本次按落库失败处理并回滚，与 save 返回 false 的路径一致。
     * </p>
     */
    private boolean reuse(PtDownloadRecordPlus reusable, PtDownloadRecordPlus record) {
        boolean updated = recordService.update(record, new UpdateWrapper<PtDownloadRecordPlus>()
                .eq("id", reusable.getId())
                .eq("state", RECORD_FAILED)
                .set("fail_reason", null)
                .set("fail_reason_code", null)
                .set("completed_time", null)
                .set("progress", null)
                .set("files_selected", false));
        if (updated) {
            // 下游要靠它写 pt_subscription_episode.download_id、以及推送失败时删记录回滚
            record.setId(reusable.getId());
            log.info("复用可重试的失败下载记录[{}] 重新推送：{}", reusable.getId(), record.getTitle());
        }
        return updated;
    }

    /**
     * 条件更新占位，防止并发轮询给同一集推两个种子。
     * 补缺集是 MISSING → IN_FLIGHT，洗版是 IN_LIBRARY → UPGRADING。
     */
    private boolean claim(PtSubscriptionEpisodePlus target, PushMode mode) {
        String from = mode.isUpgrade() ? STATE_IN_LIBRARY : STATE_MISSING;
        String to = mode.isUpgrade() ? STATE_UPGRADING : STATE_IN_FLIGHT;
        PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
        set.setState(to);
        return episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                .eq("id", target.getId())
                .eq("state", from));
    }

    /**
     * 回滚占位，与 {@link #claim} 严格对称。
     * <p>
     * 洗版必须退回 IN_LIBRARY 而不是 MISSING：旧版本的文件一直好端端在库里，
     * 退成 MISSING 会让这一集显示成缺失并被 RSS 从头重下一遍——比不洗版还糟。
     * </p>
     */
    private void releaseAll(List<PtSubscriptionEpisodePlus> claimed, PushMode mode) {
        String back = mode.isUpgrade() ? STATE_IN_LIBRARY : STATE_MISSING;
        String claimedState = mode.isUpgrade() ? STATE_UPGRADING : STATE_IN_FLIGHT;
        for (PtSubscriptionEpisodePlus ep : claimed) {
            PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
            set.setState(back);
            episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                    .eq("id", ep.getId())
                    .eq("state", claimedState));
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

    /**
     * 查询当前<b>参与订阅下载</b>的启用下载器列表，供批内缓存复用。
     * <p>
     * {@code role=SEED_ONLY} 的下载器必须在这一步就被排除干净。它的用途是接收 IYUU
     * 转移/辅种过来的种子，往它上面推订阅意味着：种子下到了一台配了自动删种的机器上，
     * 而那台机器的清理规则是按"保种"设计的（做满 N 小时就删），和"正在补的剧集要留着上传网盘"
     * 完全不是一回事。
     * </p>
     * <p>
     * 过滤刻意放在 Java 侧而不是写进 SQL 的 {@code eq("role", "DOWNLOAD")}：role 是后加的列，
     * 存量行可能为 NULL，SQL 等值比较对 NULL 恒为假，会把用户原本唯一的那台下载器整个滤掉，
     * 升级后订阅直接全部停摆。{@link PtDownloaderPlus#participatesInDownload()} 对 NULL
     * 退化成 DOWNLOAD，升级前后行为一致。
     * </p>
     */
    private List<PtDownloaderPlus> loadEnabledDownloaders() {
        return downloaderService.list(new QueryWrapper<PtDownloaderPlus>().eq("enabled", "1"))
                .stream()
                .filter(PtDownloaderPlus::participatesInDownload)
                .toList();
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
            // 走到这里说明订阅指定的下载器已停用、已删除、或被改成了 SEED_ONLY（见
            // loadEnabledDownloaders）。后一种不是配置事故而是用户的分工意图，同样只能改派——
            // 把订阅推到只做种的机器上，比换一台下载器糟得多
            log.warn("订阅[{}] 指定的下载器 {} 不在订阅下载池中（已停用/已删除/已改为仅做种），"
                    + "改用负载最低的下载器", sub.getId(), sub.getDownloaderId());
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
