package com.osr.openliststrm.pt.autoadd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.FaultThrottle;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddLogPlus;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.PtLogText;
import com.osr.openliststrm.pt.autoadd.dto.AutoAddRunResult;
import com.osr.openliststrm.pt.autoadd.source.PopularItem;
import com.osr.openliststrm.pt.autoadd.source.PopularSource;
import com.osr.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.pt.subscription.TmdbSearchService;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 热门自动订阅：拉取榜单 → 补全 → 过滤 → 去重 → 建订阅，是各 {@link PopularSource} 与既有
 * {@link SubscriptionService#subscribe} 之间的粘合层，本身不关心数据来自 TMDb 还是别的源。
 * <p>
 * 「补全」那一步只对没有 tmdbId 的候选（豆瓣源）生效，见 {@link PopularItemResolver}。
 * 它<b>必须排在过滤之前</b>：过滤依据的三个字段全部来自 TMDb。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class AutoAddPopularService {

    private static final int DEFAULT_MAX_ADD_PER_RUN = 5;

    private static final String TYPE_TV = "TV";

    static final String RESULT_ADDED = "ADDED";
    static final String RESULT_FAILED = "FAILED";
    static final String RESULT_FETCH_FAILED = "FETCH_FAILED";
    static final String RESULT_SKIPPED_EXISTS = "SKIPPED_EXISTS";
    static final String RESULT_SKIPPED_REMOVED = "SKIPPED_REMOVED";
    static final String RESULT_SKIPPED_FILTER = "SKIPPED_FILTER";
    static final String RESULT_SKIPPED_NO_MATCH = "SKIPPED_NO_MATCH";

    /**
     * 只记第一次的结果。SKIPPED_NO_MATCH 不在其中：它靠「最近一条日志的时间」决定何时重试
     * （见 settledBefore），去重的话那条时间永远停在第一次，过了重试期就变成每轮都重搜
     */
    private static final Set<String> DEDUP_RESULTS =
            Set.of(RESULT_SKIPPED_EXISTS, RESULT_SKIPPED_REMOVED, RESULT_SKIPPED_FILTER);

    /** 没匹配上 TMDb 的豆瓣条目，隔多少天再按标题重搜一次 */
    static final int NO_MATCH_RETRY_DAYS = 7;

    /** 跳过/失败日志保留天数 */
    static final int LOG_RETENTION_DAYS = 30;

    /** 与 create_time 的写入格式一致（MyMetaObjectHandler） */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 正在执行的规则 id。属于组件级状态（跨调用存活），与「单例不许存单次请求状态」那条不冲突 */
    private final Set<Integer> runningRules = ConcurrentHashMap.newKeySet();

    /** 拉榜失败按规则节流：心跳 30 分钟一轮，RSSHub 挂一天就是 48 条同样的错误 */
    private final FaultThrottle fetchFaults = new FaultThrottle();

    /** 与 pt_auto_add_log.message 的列宽一致 */
    private static final int MAX_LOG_MESSAGE_LENGTH = 500;

    @Autowired
    private List<PopularSource> sources;

    @Autowired
    private IPtAutoAddRulePlusService ruleService;

    @Autowired
    private IPtAutoAddLogPlusService logService;

    @Autowired
    private IPtSubscriptionPlusService subscriptionPlusService;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private TmdbSearchService tmdbSearchService;

    @Autowired
    private PopularItemResolver resolver;

    @Autowired
    private SubscriptionSearchOnCreateTrigger searchOnCreateTrigger;

    /**
     * 执行单条规则。规则里 source 找不到对应数据源实现时直接跳过。
     * <p>
     * 同一条规则同一时刻只允许跑一份：「立即执行」与定时任务撞上时，两边会对同一批条目
     * 各建一次订阅，后到的那份撞唯一约束被记成 FAILED——明明订上了，日志却说失败。
     * </p>
     *
     * @throws IllegalStateException 该规则正在执行中
     */
    public AutoAddRunResult runRule(PtAutoAddRulePlus rule) {
        if (!runningRules.add(rule.getId())) {
            throw new IllegalStateException("规则「" + rule.getName() + "」正在执行中，请稍后查看执行日志");
        }
        try {
            return doRunRule(rule);
        } finally {
            runningRules.remove(rule.getId());
        }
    }

    private AutoAddRunResult doRunRule(PtAutoAddRulePlus rule) {
        PopularSource source = sources.stream().filter(s -> s.supports(rule.getSource())).findFirst().orElse(null);
        if (source == null) {
            log.warn("热门自动订阅规则[{}] source={} 无对应数据源实现，跳过", rule.getId(), rule.getSource());
            return new AutoAddRunResult(0, 0, 0);
        }

        List<PopularItem> candidates;
        String faultKey = String.valueOf(rule.getId());
        try {
            candidates = source.fetch(rule);
        } catch (Exception e) {
            // 不推进 last_run_time：拉榜失败多是 RSSHub/TMDb 临时不可用，下一个 30 分钟心跳就重试，
            // 推进的话要白等一整个 interval_hours。重试期间用 FaultThrottle 压住重复日志
            FaultThrottle.Decision decision = fetchFaults.onFailure(faultKey);
            if (decision.shouldReport()) {
                log.warn("热门自动订阅规则[{}]{} 拉取榜单失败（连续 {} 次），下个心跳重试：{}",
                        rule.getId(), rule.getName(), decision.consecutiveFailures(), e.getMessage(), e);
                // 也写进执行日志：页面上只看得到这张表，只写应用日志的话用户看到的是「一直没加」
                writeRuleLog(rule, RESULT_FETCH_FAILED, e.getMessage());
            }
            return AutoAddRunResult.fetchFailed(e.getMessage());
        }
        if (fetchFaults.onSuccess(faultKey)) {
            log.info("热门自动订阅规则[{}]{} 拉取榜单已恢复", rule.getId(), rule.getName());
        }

        Set<Integer> genreExclude = parseGenreExclude(rule.getGenreExclude());
        int maxAdd = (rule.getMaxAddPerRun() == null || rule.getMaxAddPerRun() <= 0)
                ? DEFAULT_MAX_ADD_PER_RUN : rule.getMaxAddPerRun();
        boolean movie = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(rule.getMediaType());
        String mediaType = movie ? SubscriptionService.TYPE_MOVIE : TYPE_TV;

        int added = 0, skipped = 0, failed = 0;
        for (PopularItem item : candidates) {
            if (added >= maxAdd) {
                break;
            }
            // 豆瓣源拉回来的条目只有标题，先按标题搜 TMDb 补全 tmdbId 与过滤所需字段。
            // 补全必须在过滤之前：genreIds/voteAverage/voteCount 都来自 TMDb，不补的话
            // 规则上那三个过滤器对豆瓣源全部落空（恒为 null，按"不达标"处理会一条都放不过）。
            String sourceTitle = item.getTitle();
            if (StringUtils.isBlank(item.getTmdbId())) {
                if (settledBefore(item, movie, mediaType)) {
                    skipped++;
                    continue;
                }
                String failReason = resolver.resolve(item, rule.getMediaType());
                if (failReason != null) {
                    writeLog(rule, item, null, RESULT_SKIPPED_NO_MATCH, failReason);
                    skipped++;
                    continue;
                }
            }
            // 标题被换成了 TMDb 侧的名字，把这次映射记进日志——用户核对"订的到底是不是那部"
            // 只能靠这一行，而误匹配是这条链路上唯一会造成实际损失的失败方式
            String matchNote = StringUtils.equals(sourceTitle, item.getTitle())
                    ? null : "来源标题《" + sourceTitle + "》匹配到 TMDb《" + item.getTitle() + "》";

            String skipReason = filterReason(item, genreExclude, rule);
            if (skipReason != null) {
                writeLog(rule, item, null, RESULT_SKIPPED_FILTER, join(skipReason, matchNote));
                skipped++;
                continue;
            }

            Integer season = movie ? null : resolveSeason(item);
            if (alreadySubscribed(item.getTmdbId(), movie, season)) {
                writeLog(rule, item, season, RESULT_SKIPPED_EXISTS, join("同作品同季已存在订阅", matchNote));
                skipped++;
                continue;
            }
            if (logService.everAdded(item.getTmdbId(), mediaType, season)) {
                writeLog(rule, item, season, RESULT_SKIPPED_REMOVED,
                        join("此前自动订过、订阅已被删除，不再自动加回；需要的话请手动订阅", matchNote));
                skipped++;
                continue;
            }

            SubscribeRequest request = new SubscribeRequest();
            request.setTmdbId(item.getTmdbId());
            request.setMediaType(mediaType);
            request.setSeason(season);
            request.setDownloaderId(rule.getDownloaderId());
            request.setFilterOverride(rule.getFilterOverride());
            try {
                PtSubscriptionPlus sub = subscriptionService.subscribe(request);
                writeLog(rule, item, season, RESULT_ADDED, matchNote);
                added++;
                triggerSearchOnCreate(sub);
            } catch (Exception e) {
                log.warn("热门自动订阅规则[{}]建订阅失败 tmdbId={} title={}：{}",
                        rule.getId(), item.getTmdbId(), item.getTitle(), e.getMessage());
                writeLog(rule, item, season, RESULT_FAILED, join(e.getMessage(), matchNote));
                failed++;
            }
        }

        // 只写 last_run_time 这一列：整条 updateById 会把执行开始时读到的旧规则写回去，
        // 执行期间用户在页面上改的规则（豆瓣源一轮可能跑上几十秒）就被静默覆盖了
        Date now = new Date();
        rule.setLastRunTime(now);
        ruleService.update(new UpdateWrapper<PtAutoAddRulePlus>()
                .set("last_run_time", now)
                .eq("id", rule.getId()));
        log.info("热门自动订阅规则[{}]{} 执行完成：新增{} 跳过{} 失败{}",
                rule.getId(), rule.getName(), added, skipped, failed);
        return new AutoAddRunResult(added, skipped, failed);
    }

    /**
     * 豆瓣条目是否能凭上一轮的结果直接判定为「不用再处理」，从而省掉按标题搜 TMDb。
     * <p>
     * 榜单一天到晚是同一批条目，每轮都重搜一遍 TMDb（最坏还要多查 3 次别名）纯属浪费配额。
     * 两种情况直接跳过、且不再写日志（第一次已经写过）：
     * <ol>
     *   <li>上一轮已匹配到 tmdbId，而那部作品的那一季现在有订阅，或曾被自动订过又被删了</li>
     *   <li>上一轮没匹配上且不到 {@value #NO_MATCH_RETRY_DAYS} 天——TMDb 补中文译名不是几小时的事，
     *       但也不能永不重试，否则那一条就永远订不上</li>
     * </ol>
     * 上一轮被过滤掉（评分不够等）的<b>不走捷径</b>：过滤字段来自 TMDb、会变，必须重新补全再判。
     * </p>
     */
    private boolean settledBefore(PopularItem item, boolean movie, String mediaType) {
        PtAutoAddLogPlus last = logService.latestBySourceItem(item.getDoubanId(), mediaType);
        if (last == null) {
            return false;
        }
        if (StringUtils.isNotBlank(last.getTmdbId())) {
            // 来源标题自带的季号（「第九季」）比上次记下的更可信；都没有时判不了，走完整流程
            Integer season = movie ? null : (item.getSeasonNumber() != null ? item.getSeasonNumber() : last.getSeason());
            if (!movie && season == null) {
                return false;
            }
            return alreadySubscribed(last.getTmdbId(), movie, season)
                    || logService.everAdded(last.getTmdbId(), mediaType, season);
        }
        return RESULT_SKIPPED_NO_MATCH.equals(last.getResult())
                && withinDays(last.getCreateTime(), NO_MATCH_RETRY_DAYS);
    }

    private static boolean withinDays(String time, int days) {
        if (StringUtils.isBlank(time)) {
            return false;
        }
        try {
            return LocalDateTime.parse(time, TIME_FORMAT).isAfter(LocalDateTime.now().minusDays(days));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 建订阅后发起一次性补搜历史资源，与 Web / 企微 / MCP 三个入口保持一致。
     * <p>
     * <b>缺了这一步，自动订进来的剧不会被任何主动搜索路径碰到</b>：{@code auto_search}
     * 的库默认是 {@code '0'} 而 {@link SubscriptionService#subscribe} 不改它，
     * 定期补搜（{@code AutoSearchService}）的候选 SQL 又要求那个开关开着，于是只剩 RSS 轮询
     * 碰运气——而榜单里的热门剧往往已经播了几集，那批历史集的种子早滑出 24 小时的拉取窗口了。
     * 现象是订阅列表里躺着一排进度恒为 0 的剧，而执行日志里每条都是 ADDED、一切正常。
     * </p>
     * <p>
     * 这里<b>刻意不顺手打开 {@code auto_search}</b>：那个默认关是有意的（追完的老剧长期空转，
     * 每轮都要向每个索引器打满一整份检索计划），而自动订阅只会让开着开关的订阅更多。
     * 「历史集补不上」这个真问题由这一次补搜解决，不需要改默认值。
     * </p>
     * <p>
     * 异常一律吞掉：订阅此时已经建成功并记了 ADDED，触发失败不该把它翻成 FAILED——
     * 那条日志是用来解释「这轮为什么没加」的，而它确实加上了。
     * </p>
     */
    private void triggerSearchOnCreate(PtSubscriptionPlus sub) {
        // 建订阅时已经对过一次账，全部集都在库的订阅直接是 COMPLETED，没有可补的东西
        if (sub == null || !SubscriptionService.STATUS_ACTIVE.equals(sub.getStatus())) {
            return;
        }
        try {
            searchOnCreateTrigger.triggerAsync(sub.getId());
        } catch (Exception e) {
            log.warn("{} 热门自动订阅建订阅后补搜触发失败：{}", PtLogText.subject(sub), e.getMessage());
        }
    }

    /**
     * 到期的启用规则依次执行一轮。由 {@link AutoAddPopularTask} 定时调用。
     */
    public int runDueRules() {
        int ran = 0;
        for (PtAutoAddRulePlus rule : ruleService.listEnabled()) {
            if (!due(rule)) {
                continue;
            }
            try {
                runRule(rule);
                ran++;
            } catch (IllegalStateException e) {
                // 正在被「立即执行」跑着，这一轮让给它
                log.info("热门自动订阅规则[{}]正在执行中，本轮跳过", rule.getId());
            } catch (Exception e) {
                log.error("热门自动订阅规则[{}]执行异常：{}", rule.getId(), e.getMessage(), e);
            }
        }
        if (ran > 0) {
            purgeOldLogs();
        }
        return ran;
    }

    /**
     * 清掉 {@value #LOG_RETENTION_DAYS} 天前的跳过/失败日志，ADDED 永久保留（见
     * {@link IPtAutoAddLogPlusService#purgeSkippedBefore}）。只在确有规则跑过的那一轮做，
     * 不必每 30 分钟心跳都删一次。
     */
    private void purgeOldLogs() {
        try {
            int purged = logService.purgeSkippedBefore(
                    LocalDateTime.now().minusDays(LOG_RETENTION_DAYS).format(TIME_FORMAT));
            if (purged > 0) {
                log.info("热门自动订阅：清理 {} 天前的执行日志 {} 条", LOG_RETENTION_DAYS, purged);
            }
        } catch (Exception e) {
            log.warn("热门自动订阅清理旧执行日志失败：{}", e.getMessage());
        }
    }

    private boolean due(PtAutoAddRulePlus rule) {
        if (rule.getLastRunTime() == null) {
            return true;
        }
        int intervalHours = (rule.getIntervalHours() == null || rule.getIntervalHours() <= 0) ? 24 : rule.getIntervalHours();
        long elapsedMs = System.currentTimeMillis() - rule.getLastRunTime().getTime();
        return elapsedMs >= intervalHours * 3600_000L;
    }

    /**
     * 决定订哪一季；电影不涉及季，不调用本方法。
     * <p>
     * <b>来源侧给出的季号优先</b>：豆瓣榜单里的「瑞克和莫蒂 第九季」说的就是第 9 季，
     * 而 TMDb 那时可能已经有第 10 季了——按「最新季」兜底会订到一个用户没在榜单上看到的季。
     * 榜单条目本身是最直接的意图表达，只有它给不出时才退回查最新季。
     * </p>
     */
    private Integer resolveSeason(PopularItem item) {
        if (item.getSeasonNumber() != null) {
            return item.getSeasonNumber();
        }
        try {
            return tmdbSearchService.getLatestSeasonNumber(item.getTmdbId());
        } catch (Exception e) {
            log.warn("查询 tmdbId={} 最新季号失败，兜底订第1季：{}", item.getTmdbId(), e.getMessage());
            return 1;
        }
    }

    private boolean alreadySubscribed(String tmdbId, boolean movie, Integer season) {
        LambdaQueryWrapper<PtSubscriptionPlus> wrapper = new LambdaQueryWrapper<PtSubscriptionPlus>()
                .eq(PtSubscriptionPlus::getTmdbId, tmdbId)
                .eq(PtSubscriptionPlus::getMediaType, movie ? SubscriptionService.TYPE_MOVIE : TYPE_TV)
                .eq(PtSubscriptionPlus::getSeason, movie ? 0 : season);
        return subscriptionPlusService.count(wrapper) > 0;
    }

    /**
     * 返回跳过原因；不为空即命中过滤，null 表示通过全部过滤条件。
     */
    private String filterReason(PopularItem item, Set<Integer> genreExclude, PtAutoAddRulePlus rule) {
        if (!genreExclude.isEmpty() && item.getGenreIds() != null
                && item.getGenreIds().stream().anyMatch(genreExclude::contains)) {
            return "命中类型排除";
        }
        if (rule.getMinVoteAverage() != null
                && (item.getVoteAverage() == null || item.getVoteAverage() < rule.getMinVoteAverage())) {
            return "评分不达标";
        }
        if (rule.getMinVoteCount() != null
                && (item.getVoteCount() == null || item.getVoteCount() < rule.getMinVoteCount())) {
            return "评分人数不达标";
        }
        return null;
    }

    /**
     * 解析排除类型。非数字的片段跳过并告警，而不是让 parseInt 抛出去：
     * 页面是多选框填不出坏值，但规则也能经接口/MCP 写入，一个手误的「动画」会让整条规则每轮都执行失败
     */
    Set<Integer> parseGenreExclude(String csv) {
        if (StringUtils.isBlank(csv)) {
            return new HashSet<>();
        }
        Set<Integer> ids = new HashSet<>();
        for (String part : csv.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                ids.add(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                log.warn("热门自动订阅规则的排除类型含非法值「{}」，已忽略（应为 TMDb 类型 ID）", token);
            }
        }
        return ids;
    }

    /** 拼两段说明，任一为空时不留下多余的分隔符 */
    private String join(String first, String second) {
        if (StringUtils.isBlank(first)) {
            return second;
        }
        return StringUtils.isBlank(second) ? first : first + "；" + second;
    }

    private void writeLog(PtAutoAddRulePlus rule, PopularItem item, Integer season, String result, String message) {
        PtAutoAddLogPlus entry = new PtAutoAddLogPlus();
        entry.setRuleId(rule.getId());
        entry.setTmdbId(item.getTmdbId());
        entry.setSourceItemId(item.getDoubanId());
        entry.setSourceItemUrl(item.getSourceUrl());
        entry.setMediaType(StringUtils.isNotBlank(item.getMediaType()) ? item.getMediaType() : rule.getMediaType());
        entry.setTitle(item.getTitle());
        entry.setSeason(season);
        entry.setResult(result);
        // message 列是 varchar(500)，异常消息可以任意长（下载器/索引器的错误常带一整个响应体），
        // 不截断的话整条日志写不进去，而这条日志正是用来解释"这轮为什么没加"的
        entry.setMessage(StringUtils.substring(message, 0, MAX_LOG_MESSAGE_LENGTH));
        saveLog(entry);
    }

    /** 规则级的日志（不对应具体条目），目前只有拉榜失败 */
    private void writeRuleLog(PtAutoAddRulePlus rule, String result, String message) {
        PtAutoAddLogPlus entry = new PtAutoAddLogPlus();
        entry.setRuleId(rule.getId());
        entry.setMediaType(rule.getMediaType());
        entry.setTitle("（拉取榜单）");
        entry.setResult(result);
        entry.setMessage(StringUtils.substring(message, 0, MAX_LOG_MESSAGE_LENGTH));
        saveLog(entry);
    }

    private void saveLog(PtAutoAddLogPlus entry) {
        try {
            // 跳过类结果每轮都会原样再成立一次，只记第一次。不去重的话榜单 20 条、一天一轮，
            // 几天后执行日志（最多取 100 条）里全是逐字相同的「已存在」，真正要看的 ADDED 被挤出去
            if (DEDUP_RESULTS.contains(entry.getResult()) && logService.alreadyLogged(entry)) {
                return;
            }
            logService.save(entry);
        } catch (Exception e) {
            log.warn("写入热门自动订阅日志失败：{}", e.getMessage());
        }
    }
}
