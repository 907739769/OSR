package com.osr.openliststrm.pt.search;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import com.osr.openliststrm.pt.filter.EpisodeCountResolver;
import com.osr.openliststrm.pt.filter.FilterCriteria;
import com.osr.openliststrm.pt.filter.FilterCriteriaFactory;
import com.osr.openliststrm.pt.filter.TorrentBlacklist;
import com.osr.openliststrm.pt.filter.TorrentFilterEngine;
import com.osr.openliststrm.pt.model.ExternalIds;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.DescriptionAliases;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.SearchCandidateDTO;
import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import com.osr.openliststrm.rename.RenameClientProvider;
import com.osr.openliststrm.rename.SeasonSuffix;
import com.osr.openliststrm.rename.TitleNormalizer;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.openliststrm.tmdb.TMDbClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 资源搜索页：不建订阅，直接拿关键词搜全部站点，看站上有什么，再决定直接下载还是转为订阅。
 * <p>
 * 与订阅内手动搜索的区别有两条，都是刻意的：
 * </p>
 * <ul>
 *   <li><b>不做「是不是这部作品」的过滤</b>——没有订阅可比。检索、去重、标题解析、H&R 标记与订阅内
 *       手动搜索共用同一份实现（{@link SearchSupplementService#searchKeyword}），两边看到的种子画像一致。</li>
 *   <li><b>全局过滤规则只标注、不淘汰</b>（{@link SearchCandidateDTO#getRuleRejection}）。用户来这里正是要看
 *       「站上到底有什么」，按规则滤掉一批的话他会以为站上没有；而标注出「这条会因为分辨率不在白名单被挡」，
 *       恰好回答了「为什么我订了却没自动下」。用的是全局规则、不带任何订阅级覆盖。</li>
 * </ul>
 * <p>
 * <b>直接下载不建下载记录</b>：{@code pt_download_record.sub_id} 是 NOT NULL，下载追踪、记录页的归属隔离、
 * 统计都以「记录属于某个订阅」为前提，为这一个入口放开要动三十来处。因此直接下载的种子在 OSR 看来
 * 与用户在下载器里手动添加的种子是一回事：不追踪进度、不下发 H&R 分享限制、不出现在下载记录页；
 * 下载完成后的同步/STRM 链路是按目录触发的，照常生效。想要被追踪就走「转为订阅」。
 * </p>
 */
@Slf4j
@Service
public class ResourceSearchService {

    /** 直接下载的种子打这个标签：与订阅推送的跟踪标签分开，不会被下载追踪认领 */
    public static final String MANUAL_TAG = "osr-manual";

    /** 关键词至少这么长：一个字的关键词在多数站点上要么报错、要么返回整站最新，都不是用户要的 */
    static final int MIN_KEYWORD_LENGTH = 2;

    /**
     * 一次搜索最多识别多少组标题；超过时只识别<b>种子最多</b>的这么多组，其余不问。
     * <p>
     * 一次搜索几百条结果里通常只有十几组（同一部剧的 12 集是 12 条种子、一组），多数搜索碰不到它。
     * 碰到的是长寿动画这一类：实测「Shingeki no Kyojin」100 条结果有 33 组标题——各季、剧场版、OAD、
     * 各字幕组各写各的。早先的做法是超限就整页不识别，于是恰恰是最需要识别的那类搜索一条都认不出。
     * 按组内种子数从多到少取，是因为大组覆盖的行最多；剩下的多是只有一两条的零散写法。
     * 没问的那些组必须显式说出来，不能显示成「识别不出」——真相是没识别。
     * </p>
     */
    static final int MAX_TMDB_LOOKUPS = 20;

    /**
     * 标题识别落空后，一组标题最多拿几个 description 别名再试。每个别名是一次 TMDb 搜索，
     * 而别名列表里靠后的多半是繁体、日文原名这些同一个答案的不同写法，试多了只是多花配额。
     */
    static final int MAX_ALIAS_ATTEMPTS = 3;

    /**
     * 一组标题最多拆出几部同名剧（见 {@link #identifyGroup}）。同名剧三部以上极少见，
     * 而每拆一轮是一次完整的识别，不封顶的话一组脏数据能把这一组的请求量翻好几倍。
     */
    static final int MAX_SAME_NAME_WORKS = 3;

    /** 集号超过全剧总集数这么多倍才算「装不下」，与 {@code TMDbClient} 的集数反证同一个余量、同一个理由 */
    static final int EPISODE_OVERFLOW_FACTOR = 2;

    /**
     * 一条种子对应的作品身份。识别不出时整条为 null——前端据此显示「识别不出」，
     * 而不是留一个空白格（空白会被读成「站上没有这部剧」）。
     */
    public record WorkIdentity(String tmdbId, String mediaType, String title, String year) {
    }

    /**
     * 搜索结果。
     *
     * @param candidateCount      去重后的候选数
     * @param rejectedCount       其中会被全局过滤规则淘汰的条数
     * @param distinctWorks       按「标题 + 类型」归并出的标题组数（同一部剧的 12 集算 1 组）
     * @param identifiedWorks     其中识别出作品身份的组数，与 distinctWorks 同一口径、恒不大于它
     * @param tmdbLookupSkipped   标题组数超过上限：只识别了种子最多的那些组，其余没问
     * @param tmdbLookupTruncated 预算内没跑完，已识别的只是其中一部分
     * @param tmdbLookupUnavailable TMDb key 未配置：整页一个都没识别，不是「这些种子不属于任何作品」
     */
    public record Result(int candidateCount, int rejectedCount, boolean tmdbLookupEnabled,
                         int distinctWorks, int identifiedWorks, boolean tmdbLookupSkipped,
                         boolean tmdbLookupTruncated, boolean tmdbLookupUnavailable,
                         List<SearchCandidateDTO> items) {
    }

    private final SearchSupplementService searchSupplementService;
    private final SubscriptionEngine subscriptionEngine;
    private final TorrentFilterEngine filterEngine;
    private final IPtFilterConfigPlusService filterConfigService;
    private final IPtTorrentBlacklistPlusService blacklistService;
    private final IPtDownloaderPlusService downloaderService;
    private final DownloaderClientFactory downloaderClientFactory;
    private final IPtSubscriptionPlusService subscriptionService;
    private final TmdbSearchService tmdbSearchService;
    private final RenameClientProvider clientProvider;

    private final boolean tmdbLookupEnabled;
    private final int tmdbLookupMax;
    /**
     * 识别的墙钟预算。到点后不再等没跑完的组（它们各自跑完、结果进 TMDb 缓存，下次搜索直接命中），
     * 与索引器预算同一取向：软上限，不打断已发出的请求。
     * 前端超时按「索引器预算 + 识别预算」配，见 {@code api/openlist/ptSearch.ts}。
     * <p>
     * <b>这个预算盖不住最坏情况，是有意的。</b>最坏请求量 = 组数上限（{@link #MAX_TMDB_LOOKUPS}）×
     * （标题 1 次 + 别名 {@link #MAX_ALIAS_ATTEMPTS} 次）× 每次识别的请求数（一次搜索起步，
     * 电影带年份两次，候选全是中日韩名时再加最多 5 次英文规范名探测），全部要过
     * {@code TMDbApiService} 并发 4 的信号量——20 组全是冷缓存的罗马音标题时跑不完。
     * 实测远到不了那里（13 组冷缓存约 20 秒、5 组含别名兜底约 6 秒，均含索引器检索），
     * 而跑不完的代价只是这一次标「没跑完」：任务不取消，结果进缓存，再搜一次就全了。
     * 为了盖住一个罕见的最坏情况把每次搜索的等待上限翻倍，不划算。
     * </p>
     */
    private final long tmdbLookupBudgetMillis;

    public ResourceSearchService(SearchSupplementService searchSupplementService,
                                 SubscriptionEngine subscriptionEngine,
                                 TorrentFilterEngine filterEngine,
                                 IPtFilterConfigPlusService filterConfigService,
                                 IPtTorrentBlacklistPlusService blacklistService,
                                 IPtDownloaderPlusService downloaderService,
                                 DownloaderClientFactory downloaderClientFactory,
                                 IPtSubscriptionPlusService subscriptionService,
                                 TmdbSearchService tmdbSearchService,
                                 RenameClientProvider clientProvider,
                                 @Value("${pt.search.tmdb-lookup:true}") boolean tmdbLookupEnabled,
                                 @Value("${pt.search.tmdb-lookup-max:" + MAX_TMDB_LOOKUPS + "}") int tmdbLookupMax,
                                 @Value("${pt.search.tmdb-lookup-budget-ms:30000}") long tmdbLookupBudgetMillis) {
        this.searchSupplementService = searchSupplementService;
        this.subscriptionEngine = subscriptionEngine;
        this.filterEngine = filterEngine;
        this.filterConfigService = filterConfigService;
        this.blacklistService = blacklistService;
        this.downloaderService = downloaderService;
        this.downloaderClientFactory = downloaderClientFactory;
        this.subscriptionService = subscriptionService;
        this.tmdbSearchService = tmdbSearchService;
        this.clientProvider = clientProvider;
        this.tmdbLookupEnabled = tmdbLookupEnabled;
        this.tmdbLookupMax = tmdbLookupMax;
        this.tmdbLookupBudgetMillis = tmdbLookupBudgetMillis;
    }

    /**
     * @throws IllegalArgumentException 关键词为空或太短、没有启用中的索引器、所选站点全部不可用
     */
    public Result search(String keyword, Collection<Integer> indexerIds, Long me, boolean admin) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.length() < MIN_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("关键词至少 " + MIN_KEYWORD_LENGTH + " 个字");
        }
        // 没有索引器时直说，别让用户拿着「0 个结果」去换关键词（同订阅内手动搜索）
        if (searchSupplementService.hasNoEnabledIndexer()) {
            throw new IllegalArgumentException("没有启用中的索引器，无法搜索。请到「PT索引器」页面添加或启用至少一个索引器");
        }
        List<TorrentInfo> torrents = new ArrayList<>(searchSupplementService.searchKeyword(kw, indexerIds));
        // 与订阅内手动搜索同样先打 H&R 标记、折算集数：前者让「规避 H&R」的标注生效，后者让按每集判定的
        // 体积规则对季包给出与订阅链路一致的结论。没有订阅总集数可参考，按种子自身的区间/文件数估
        subscriptionEngine.markHitAndRun(torrents);
        for (TorrentInfo t : torrents) {
            t.setEpisodeCount(EpisodeCountResolver.resolve(t, null, looksLikeMovie(t)));
        }
        Map<TorrentInfo, TorrentFilterEngine.Verdict> verdicts = evaluate(torrents);

        // 默认按做种数降序：页面上能再按列排序，这里只决定第一眼看到什么
        torrents.sort(Comparator.comparingInt(TorrentInfo::getSeeders).reversed());
        List<SearchCandidateDTO> items = searchSupplementService.toCandidateDtos(torrents);
        // 下面的规则标注与作品身份都按下标把 torrents 的结论回填到 items 上，靠的是 toCandidateDtos
        // 一对一、不过滤、不重排。它哪天变了，结论会静默套到别的行上——带着 TMDb 链接和「已订阅」标记，
        // 看着比真的还可信。宁可这一次搜索直接失败，也不能把串了行的结果交出去
        if (items.size() != torrents.size()) {
            throw new IllegalStateException("候选 DTO 与种子不是一一对应（" + items.size() + " / " + torrents.size()
                    + "），规则标注与作品识别按下标回填的前提不成立");
        }
        int rejected = 0;
        for (int i = 0; i < torrents.size(); i++) {
            TorrentFilterEngine.Verdict verdict = verdicts.get(torrents.get(i));
            if (verdict != null && !verdict.accepted()) {
                items.get(i).setRuleRejection(verdict.rejectCode() == null ? "被过滤规则淘汰" : verdict.rejectCode().label());
                items.get(i).setRuleRejectionDetail(verdict.rejectReason());
                rejected++;
            }
        }

        LookupOutcome lookup = identifyWorks(torrents);
        fillWorkIdentity(items, torrents, lookup.works());
        markSubscribed(items, visibleSubscribedIds(lookup.ids(), me, admin));

        log.info("资源搜索 关键词[{}]：{} 个结果，其中 {} 个会被全局过滤规则淘汰，{} 组标题{}",
                kw, items.size(), rejected, lookup.distinctWorks(), describeLookup(lookup));
        return new Result(items.size(), rejected, tmdbLookupEnabled, lookup.distinctWorks(), lookup.identified(),
                lookup.skipped(), lookup.truncated(), lookup.unavailable(), items);
    }

    /** 按全局规则逐条判定；判定本身出错不该让搜索失败，退回成「不标注」 */
    private Map<TorrentInfo, TorrentFilterEngine.Verdict> evaluate(List<TorrentInfo> torrents) {
        Map<TorrentInfo, TorrentFilterEngine.Verdict> byTorrent = new IdentityHashMap<>();
        try {
            FilterCriteria criteria = FilterCriteriaFactory.build(filterConfigService.getConfig(), null);
            TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());
            // 原始语言未知（没有订阅就没有 TMDb 条目），传 null 跳过「中字」检查——那一项要按影片原语言判
            for (TorrentFilterEngine.Verdict v : filterEngine.evaluate(torrents, criteria, blacklist, null)) {
                byTorrent.put(v.torrent(), v);
            }
        } catch (Exception e) {
            log.warn("资源搜索按全局过滤规则标注失败，本次结果不带标注：{}", e.getMessage(), e);
        }
        return byTorrent;
    }

    /**
     * @param works      每条种子的作品身份（按引用存取：同一组里的种子可能分属两部同名剧）
     * @param identified 至少认出一条种子的标题组数
     */
    private record LookupOutcome(Map<TorrentInfo, WorkIdentity> works, Set<String> ids, int distinctWorks,
                                 int identified, boolean skipped, boolean truncated, boolean unavailable) {
        static LookupOutcome none(int distinctWorks, boolean unavailable) {
            return new LookupOutcome(Map.of(), Set.of(), distinctWorks, 0, false, false, unavailable);
        }
    }

    /**
     * 按「归一化标题 + 类型」归并后逐组识别。同一部剧的 12 集是 12 条种子、一个作品，
     * 逐条查 TMDb 会把一次搜索打成几百次请求。
     * <p>
     * 优先用索引器已经给出的 tmdbid（站点自填的、比标题强得多的信号），但<b>只在分类能判出大类时</b>才采纳：
     * {@code ExternalIds.kindOf} 分不清电影还是剧集时，那个 id 没法用。没有才走
     * {@link TMDbClient#matchTmdbId} 的打分与两道门槛；标题也落空时拿 description 里的别名
     * 再试（{@link #identifyByAlias}）。识别不出留 null，绝不猜。
     * </p>
     * <p>
     * 各组并发识别，这一层不自己压并发：{@code TMDbApiService} 有全局信号量（同时最多 4 个请求）、
     * 429 退避与两层缓存，开多少线程都被它封顶。串行等于把并发度从 4 压到 1，20 组标题很容易
     * 撞预算——撞了用户看到的是一部分作品没有身份。
     * </p>
     */
    private LookupOutcome identifyWorks(List<TorrentInfo> torrents) {
        Map<String, List<TorrentInfo>> groups = new LinkedHashMap<>();
        for (TorrentInfo t : torrents) {
            String key = workKey(t);
            if (key != null) {
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            }
        }
        int distinct = groups.size();
        if (!tmdbLookupEnabled || distinct == 0) {
            return LookupOutcome.none(distinct, false);
        }
        TMDbClient client = clientProvider.tmdb();
        if (client == null) {
            log.warn("资源搜索未识别作品：TMDb key 未配置，本次结果不带作品身份");
            return LookupOutcome.none(distinct, true);
        }
        boolean overLimit = distinct > tmdbLookupMax;
        if (overLimit) {
            // sorted 是稳定排序：种子数相同的保持原顺序，也就是含做种最多那条种子的组在前
            Map<String, List<TorrentInfo>> largest = new LinkedHashMap<>();
            groups.entrySet().stream()
                    .sorted((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()))
                    .limit(Math.max(0, tmdbLookupMax))
                    .forEach(e -> largest.put(e.getKey(), e.getValue()));
            log.warn("资源搜索只识别了一部分：归并后 {} 组标题，超过上限 {}，只识别种子最多的 {} 组，其余本次不带作品身份",
                    distinct, tmdbLookupMax, largest.size());
            groups = largest;
        }

        long deadline = System.currentTimeMillis() + tmdbLookupBudgetMillis;
        Map<String, CompletableFuture<Map<TorrentInfo, WorkIdentity>>> pending = new LinkedHashMap<>();
        // 不用 try-with-resources：close() 会等全部任务跑完，预算就成了摆设
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            for (Map.Entry<String, List<TorrentInfo>> entry : groups.entrySet()) {
                List<TorrentInfo> group = entry.getValue();
                pending.put(entry.getKey(), CompletableFuture.supplyAsync(
                        Threads.wrapSupplier(() -> identifyGroup(client, group)), executor));
            }
        } finally {
            executor.shutdown();
        }

        Map<TorrentInfo, WorkIdentity> works = new IdentityHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        int identified = 0;
        boolean truncated = false;
        for (Map.Entry<String, CompletableFuture<Map<TorrentInfo, WorkIdentity>>> entry : pending.entrySet()) {
            try {
                long remaining = Math.max(0L, deadline - System.currentTimeMillis());
                Map<TorrentInfo, WorkIdentity> found = entry.getValue().get(remaining, TimeUnit.MILLISECONDS);
                if (!found.isEmpty()) {
                    works.putAll(found);
                    found.values().forEach(work -> ids.add(work.tmdbId()));
                    identified++;
                }
            } catch (TimeoutException e) {
                truncated = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                truncated = true;
                break;
            } catch (ExecutionException e) {
                // identify 自己兜住了全部异常，走到这里只可能是 Error 一类
                log.warn("资源搜索识别作品的任务异常结束，该组结果不带作品身份：{}", e.getMessage(), e);
            }
        }
        if (truncated) {
            log.warn("资源搜索识别作品未跑完：预算 {} 秒内只识别出 {} / {} 组标题，其余本次不带作品身份",
                    tmdbLookupBudgetMillis / 1000, identified, groups.size());
        }
        return new LookupOutcome(works, ids, distinct, identified, overLimit, truncated, false);
    }

    /**
     * 识别一组标题里的每条种子是哪部作品。绝大多数组里只有一部作品，一次识别就完；
     * 这个方法多做的一件事是<b>把同名剧拆开</b>。
     * <p>
     * 归并键里没有年份（剧集种子上的年份是本季播出年，进键会把同一部剧按季劈开，见 {@link #workKey}），
     * 代价是同名的两部剧落进同一组：搜「One Piece」，1999 年的动画《航海王》与 2023 年的真人版《海贼王》
     * 标题、类型都相同。只问代表种子的话，代表是真人版，整组 —— 包括
     * {@code One Piece S23E1171 1999 …} —— 都被标成真人版。重命名那边没有这个问题，因为它逐个文件识别，
     * 集号 1171 在只有十几集的真人版上过不了集数反证。
     * </p>
     * <p>
     * 做法是<b>先认代表，再拿认出的作品核对组里其余每一条</b>（{@link #contradicts}）：年份或集号在这部剧上
     * 说不通的挑出来，当成新的一组再认一轮。三条边界：
     * </p>
     * <ul>
     *   <li><b>代表自己不核对</b>：它的答案就是 TMDb 针对它给的，再核对只会原地打转。</li>
     *   <li><b>再认一轮得到的还是同一部作品，就全部收下</b>。核对用的年份上界是个弱信号
     *       （{@code The.Office.S03E05.2019} 标的是压制年，不是播出年），它只负责挑出「值得再问一次」的，
     *       真正的裁决是 TMDb 的回答——回答没变，说明没有第二部同名剧。</li>
     *   <li>最多拆 {@link #MAX_SAME_NAME_WORKS} 部，再多的留作识别不出。</li>
     * </ul>
     * <p>
     * 没写年份、集号又不出格的种子（{@code One Piece S01E05 1080p}）没有任何可核对的东西，跟着代表走。
     * 这种认错是可能的，页面上的 TMDb 链接与站点详情页是留给用户自己核对的出口。
     * </p>
     */
    private Map<TorrentInfo, WorkIdentity> identifyGroup(TMDbClient client, List<TorrentInfo> group) {
        Map<TorrentInfo, WorkIdentity> result = new IdentityHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        List<TorrentInfo> pending = group;
        for (int round = 0; round < MAX_SAME_NAME_WORKS && !pending.isEmpty(); round++) {
            WorkIdentity work = identify(client, pending);
            if (work == null) {
                break;
            }
            TmdbSearchService.SeriesShape shape = seen.add(work.tmdbId()) ? shapeOf(work) : null;
            List<TorrentInfo> rest = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                TorrentInfo t = pending.get(i);
                // shape 为 null：不是剧集、取不到详情，或这部作品前面已经出现过（TMDb 没有别的答案了）
                if (i > 0 && shape != null && contradicts(t, shape)) {
                    rest.add(t);
                } else {
                    result.put(t, work);
                }
            }
            if (!rest.isEmpty()) {
                log.debug("资源搜索拆同名剧：「{}」认成《{}》[tmdb {}]，但组内 {} 条的年份或集号在它上面说不通，另认一轮",
                        pending.get(0).getParsedTitle(), work.title(), work.tmdbId(), rest.size());
            }
            pending = rest;
        }
        return result;
    }

    private TmdbSearchService.SeriesShape shapeOf(WorkIdentity work) {
        if (!TmdbSearchService.TYPE_TV.equals(work.mediaType())) {
            return null;
        }
        try {
            return tmdbSearchService.seriesShape(work.tmdbId());
        } catch (Exception e) {
            log.debug("资源搜索取剧集季信息失败，这一组不拆同名剧：tmdb {}，{}", work.tmdbId(), e.getMessage());
            return null;
        }
    }

    /**
     * 这条种子的集号或年份，放在这部剧上说不说得通。只在<b>有依据</b>时判矛盾，缺什么就不判什么。
     * <ul>
     *   <li><b>集号</b>：超过全剧总集数的 {@link #EPISODE_OVERFLOW_FACTOR} 倍，这部剧装不下它。
     *       留一倍余量是因为集号有三套（发布组的绝对集号可能略超 TMDb 的记录）。</li>
     *   <li><b>年份</b>：发布组在剧集种子上标的要么是首播年，要么是本季播出年。离首播年 1 年以内，说得通；
     *       否则看它的季：早于本季开播年 1 年以上，或晚于<b>下一季</b>开播年 1 年以上，说不通
     *       （一季可以跨好几年播，所以上界取下一季而不是本季）。这部剧没有这一季、或种子没写季号时，
     *       只剩一条确定的：比首播年早 1 年以上——续季只可能更晚，不可能更早。</li>
     * </ul>
     */
    static boolean contradicts(TorrentInfo t, TmdbSearchService.SeriesShape shape) {
        Integer episode = t.getParsedEpisode();
        if (episode != null && shape.totalEpisodes() > 0
                && episode > (long) shape.totalEpisodes() * EPISODE_OVERFLOW_FACTOR) {
            return true;
        }
        Integer year = yearOf(t.getParsedYear());
        if (year == null) {
            return false;
        }
        Integer first = shape.firstAirYear();
        if (first != null && Math.abs(year - first) <= 1) {
            return false;
        }
        Integer season = t.getParsedSeason();
        Integer seasonYear = season == null ? null : shape.seasonYears().get(season);
        if (seasonYear != null) {
            Map.Entry<Integer, Integer> next = shape.seasonYears().higherEntry(season);
            return year < seasonYear - 1 || (next != null && year > next.getValue() + 1);
        }
        return first != null && year < first - 1;
    }

    private static Integer yearOf(String year) {
        if (StringUtils.isBlank(year)) {
            return null;
        }
        try {
            return Integer.valueOf(year.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 识别一组标题对应的作品；落空、出错都返回 null，不让一组的失败波及其余。
     * 组内第一条（做种最多的那条）当代表：同组的标题、类型都相同，换一条去问得到的是同一个答案。
     */
    private WorkIdentity identify(TMDbClient client, List<TorrentInfo> group) {
        TorrentInfo t = group.get(0);
        String mediaType = workType(t);
        try {
            String tmdbId = StringUtils.isNotBlank(t.getTmdbId())
                    && ExternalIds.kindOf(t.getCategories()) != ExternalIds.Kind.UNKNOWN ? t.getTmdbId() : null;
            if (tmdbId == null) {
                tmdbId = client.matchTmdbId(mediaType, toMediaInfo(t, t.getParsedTitle(), t.getParsedTitleEn()));
            }
            if (StringUtils.isBlank(tmdbId)) {
                return identifyByAlias(client, group, mediaType);
            }
            TmdbSearchItem work = tmdbSearchService.describeWork(mediaType, tmdbId);
            return work == null ? null : new WorkIdentity(tmdbId, mediaType, work.getTitle(), work.getYear());
        } catch (Exception e) {
            log.warn("资源搜索识别作品「{}」失败，该组结果不带作品身份：{}", t.getParsedTitle(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * 标题识别落空后的兜底：拿 description 第一段里的作品别名再问一次 TMDb。
     * <p>
     * 国内站发的日本动画、部分国产片用<b>罗马音 / 拼音</b>命名（{@code Sousou no Frieren}），
     * 而 TMDb 给的中文名、原语言名、英文名里恰恰没有这一种，标题这条路在采纳门槛上必然落空；
     * 能对上的名字一直摆在 description 的别名列表里（{@code 葬送的芙莉莲 / 葬送のフリーレン}）。
     * 订阅匹配那边早就拿它兜底（{@code SubscriptionMatcher#descriptionAliases}），取别名用的是同一份
     * {@link DescriptionAliases#parse}。
     * </p>
     * <p>
     * <b>采纳比标题那条路更严：要求别名与作品的中文名或原名归一化后全等。</b>description 是站点自填的
     * 自由文本，别名里混着「【原盘首发】怪奇物语 第四季」「2026年1月新番 …」这类带修饰的写法；
     * 而 {@code matchTmdbId} 的门槛是「标题命中<b>或</b>年份接近」，一个带修饰的别名搜回一批不相干的结果、
     * 其中一个年份碰巧接近就会被采纳。假身份比没有身份糟得多——它带着 TMDb 链接和「已订阅」标记，
     * 看着比真的还可信。全等之后，带修饰的别名自然对不上，等于自动跳过。
     * </p>
     * <p>
     * 别名按在组内出现的次数排序：干净的作品名每条种子都会写，修饰语各写各的，前者自然排到前面。
     * </p>
     */
    private WorkIdentity identifyByAlias(TMDbClient client, List<TorrentInfo> group, String mediaType) {
        TorrentInfo t = group.get(0);
        for (String alias : rankedAliases(group)) {
            String tmdbId = client.matchTmdbId(mediaType, toMediaInfo(t, alias, null));
            if (StringUtils.isBlank(tmdbId)) {
                continue;
            }
            TmdbSearchItem work = tmdbSearchService.describeWork(mediaType, tmdbId);
            if (work == null) {
                continue;
            }
            String wanted = TitleNormalizer.normalizeForCompare(alias);
            if (wanted.equals(TitleNormalizer.normalizeForCompare(work.getTitle()))
                    || wanted.equals(TitleNormalizer.normalizeForCompare(work.getOriginalTitle()))) {
                log.debug("资源搜索按别名识别出作品：「{}」经别名「{}」对上《{}》[tmdb {}]",
                        t.getParsedTitle(), alias, work.getTitle(), tmdbId);
                return new WorkIdentity(tmdbId, mediaType, work.getTitle(), work.getYear());
            }
            log.debug("资源搜索按别名搜到的作品与别名不全等，不采纳：别名「{}」→《{}》/「{}」",
                    alias, work.getTitle(), work.getOriginalTitle());
        }
        return null;
    }

    /** 组内全部种子的 description 别名，剥掉尾部季号后按出现次数降序，取前 {@link #MAX_ALIAS_ATTEMPTS} 个 */
    private List<String> rankedAliases(List<TorrentInfo> group) {
        String parsed = TitleNormalizer.normalizeForCompare(group.get(0).getParsedTitle());
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> display = new LinkedHashMap<>();
        for (TorrentInfo t : group) {
            // 同一条种子里重复写的别名只算一次，否则一条写了三遍的种子能把它顶到最前
            Set<String> seen = new LinkedHashSet<>();
            for (String raw : DescriptionAliases.parse(t.getDescription())) {
                String alias = SeasonSuffix.strip(raw);
                String key = TitleNormalizer.normalizeForCompare(alias);
                // 与解析出的片名相同的别名不用再试：标题那条路刚拿它问过
                if (key == null || key.equals(parsed) || !seen.add(key)) {
                    continue;
                }
                counts.merge(key, 1, Integer::sum);
                display.putIfAbsent(key, alias);
            }
        }
        // sorted 是稳定排序：次数相同的保持首次出现的先后，也就是做种多的那条种子里写在前面的
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(MAX_ALIAS_ATTEMPTS)
                .map(e -> display.get(e.getKey()))
                .toList();
    }

    /**
     * 归并键：归一化标题 + 类型，<b>电影再加年份</b>。
     * <p>
     * 类型必须进键：同名的电影与剧集在 TMDb 上是两套编号，只按标题归并会把第一条的身份套给全部。
     * 年份只对电影进键：电影的年份是上映年，同名不同年就是两部作品（翻拍）；剧集种子上的年份是
     * <b>本季播出年</b>，进键会把同一部剧按季劈开——一部八季的剧占掉八组，既白发请求，
     * 又更容易撞上限让整页都不识别。同名不同年的剧集因此会落进同一组，由 {@link #identifyGroup}
     * 在组内按年份与集号再拆开。
     * </p>
     */
    private String workKey(TorrentInfo t) {
        String title = TitleNormalizer.normalizeForCompare(t.getParsedTitle());
        if (StringUtils.isBlank(title)) {
            return null;
        }
        String type = workType(t);
        String year = TmdbSearchService.TYPE_MOVIE.equals(type) ? StringUtils.defaultString(t.getParsedYear()) : "";
        return title + "|" + type + "|" + year;
    }

    /** 类型以 Torznab 分类为准；分类判不出大类时才退回「有没有季号或集号」 */
    private String workType(TorrentInfo t) {
        ExternalIds.Kind kind = ExternalIds.kindOf(t.getCategories());
        if (kind == ExternalIds.Kind.MOVIE) {
            return TmdbSearchService.TYPE_MOVIE;
        }
        if (kind == ExternalIds.Kind.TV) {
            return TmdbSearchService.TYPE_TV;
        }
        return looksLikeMovie(t) ? TmdbSearchService.TYPE_MOVIE : TmdbSearchService.TYPE_TV;
    }

    /**
     * 把种子自身的解析结果摆成 TMDb 匹配要的输入形状，不重新跑一遍正则。
     * 片名单独传：标题那条路用解析出的片名，别名兜底用别名，年份与季集号两条路共用。
     */
    private MediaInfo toMediaInfo(TorrentInfo t, String title, String englishTitle) {
        MediaInfo info = new MediaInfo(t.getTitle());
        info.setTitle(title);
        info.setOriginalTitle(title);
        info.setEnglishTitle(englishTitle);
        info.setYear(t.getParsedYear());
        if (t.getParsedSeason() != null) {
            info.setSeason(String.valueOf(t.getParsedSeason()));
        }
        if (t.getParsedEpisode() != null) {
            info.setEpisode(String.valueOf(t.getParsedEpisode()));
        }
        return info;
    }

    private void fillWorkIdentity(List<SearchCandidateDTO> items, List<TorrentInfo> torrents,
                                  Map<TorrentInfo, WorkIdentity> works) {
        if (works.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i++) {
            WorkIdentity work = works.get(torrents.get(i));
            if (work == null) {
                continue;
            }
            items.get(i).setMatchedTmdbId(work.tmdbId());
            items.get(i).setMediaType(work.mediaType());
            items.get(i).setMatchedTitle(work.title());
            items.get(i).setMatchedYear(work.year());
        }
    }

    /**
     * 已订阅标记按可见范围判：管理员看全部，其余人看自己的与无归属的公共订阅。
     * 口径与订阅页、统计面板同一份（见 pt/subscription/AGENTS.md 的归属那条）。
     * 用列名字符串而不是 lambda 投影，理由与 {@code SearchLogService#prune} 相同。
     * <p>
     * 键必须带媒体类型：TMDb 的 tv/1399 与 movie/1399 是两部不相干的作品，只按 tmdb_id 比会把
     * 「订了电影」标到一条剧集种子上。
     * </p>
     */
    private Set<String> visibleSubscribedIds(Collection<String> tmdbIds, Long me, boolean admin) {
        if (tmdbIds.isEmpty()) {
            return Set.of();
        }
        QueryWrapper<PtSubscriptionPlus> wrapper = new QueryWrapper<>();
        wrapper.select("tmdb_id", "media_type").in("tmdb_id", tmdbIds);
        if (!admin) {
            wrapper.and(w -> w.eq("owner_user_id", me).or().isNull("owner_user_id"));
        }
        Set<String> subscribed = new LinkedHashSet<>();
        for (PtSubscriptionPlus sub : subscriptionService.list(wrapper)) {
            subscribed.add(subscriptionKey(sub.getTmdbId(), sub.getMediaType()));
        }
        return subscribed;
    }

    private static String subscriptionKey(String tmdbId, String mediaType) {
        return tmdbId + "|" + StringUtils.lowerCase(StringUtils.defaultString(mediaType));
    }

    private void markSubscribed(List<SearchCandidateDTO> items, Set<String> subscribedIds) {
        if (subscribedIds.isEmpty()) {
            return;
        }
        for (SearchCandidateDTO item : items) {
            if (StringUtils.isNotBlank(item.getMatchedTmdbId())) {
                item.setSubscribed(subscribedIds.contains(subscriptionKey(item.getMatchedTmdbId(), item.getMediaType())));
            }
        }
    }

    private String describeLookup(LookupOutcome outcome) {
        if (!tmdbLookupEnabled) {
            return "（识别已关闭）";
        }
        if (outcome.unavailable()) {
            return "（TMDb key 未配置，本次未识别）";
        }
        if (outcome.truncated()) {
            return "（预算内未跑完，已识别 " + outcome.identified() + " 组）";
        }
        if (outcome.skipped()) {
            return "（超过上限 " + tmdbLookupMax + "，只识别种子最多的那些组，识别出 " + outcome.identified() + " 组）";
        }
        return "，识别出 " + outcome.identified() + " 组";
    }

    /**
     * 直接推给指定下载器，不建下载记录（理由见类注释）。
     *
     * @return 实际落盘目录，回给用户看
     * @throws IllegalArgumentException 下载器不存在/停用/只做种、缺下载链接、下载器拒绝
     */
    public String push(ResourcePushRequest request) {
        if (request.getDownloaderId() == null) {
            throw new IllegalArgumentException("请选择下载器");
        }
        if (StringUtils.isBlank(request.getDownloadUrl())) {
            throw new IllegalArgumentException("该候选没有下载链接，无法推送");
        }
        PtDownloaderPlus downloader = downloaderService.getById(request.getDownloaderId());
        if (downloader == null || !"1".equals(downloader.getEnabled())) {
            throw new IllegalArgumentException("所选下载器不存在或已停用");
        }
        // 与订阅推送同一条分工：只做种的机器开着按「保种」设计的清理规则，新下载推上去会被当保种种子清掉
        if (!downloader.participatesInDownload()) {
            throw new IllegalArgumentException("下载器「" + downloader.getName() + "」只用于做种，不接受新下载，请换一台");
        }
        TorrentInfo torrent = SearchSupplementService.torrentOf(request);
        subscriptionEngine.fillParsed(torrent);
        String savePath = SubscriptionEngine.savePathFor(downloader, looksLikeMovie(torrent), torrent.getParsedYear());
        try {
            downloaderClientFactory.get(downloader).addTorrent(downloader, torrent.getDownloadUrl(), savePath, MANUAL_TAG, false);
        } catch (Exception e) {
            log.warn("资源搜索直接下载失败：{} → 下载器「{}」：{}", torrent.getTitle(), downloader.getName(), e.getMessage(), e);
            throw new IllegalArgumentException("推送到下载器「" + downloader.getName() + "」失败：" + e.getMessage());
        }
        log.info("资源搜索直接下载：{} → 下载器「{}」，目录 {}（不建下载记录）", torrent.getTitle(), downloader.getName(), savePath);
        return savePath;
    }

    /** 解析不出季号也解析不出集号的，按电影处理（决定智能分类落进「电影」还是「剧集」目录） */
    static boolean looksLikeMovie(TorrentInfo torrent) {
        return torrent.getParsedSeason() == null && torrent.getParsedEpisode() == null;
    }
}
