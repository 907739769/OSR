package com.osr.openliststrm.pt.task;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.notify.NotificationType;
import com.osr.openliststrm.notify.NotifyTarget;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SearchSupplementService;
import com.osr.openliststrm.pt.subscription.dto.SearchAndPushSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 自动补搜业务逻辑：对开启了 auto_search 且到期的订阅，发起一次搜索，
 * 季包优先、未命中则从同一候选池本地逐集匹配补散集（见 {@link SearchSupplementService#searchAndPushMissing}），
 * 每个到期订阅每轮仍只发一次搜索请求，不会因为支持散集而增加对索引器的请求量。
 *
 * @author Jack
 */
@Slf4j
@Service
public class AutoSearchService {

    private static final int DEFAULT_INTERVAL_HOURS = 24;

    /**
     * 落空退避最多把周期翻几倍（{@code 1 << 3} = 8 倍）。
     * 再往上由 {@link #MAX_INTERVAL_MILLIS} 封顶。
     */
    private static final int MAX_BACKOFF_SHIFT = 3;

    /** 退避上限：连续落空再多，也至少一周试一次——片源可能很久以后才出现，不能彻底放弃 */
    private static final long MAX_INTERVAL_MILLIS = 7L * 24 * 3600_000L;

    /** 到期时刻的向后抖动幅度上限（百分比），按订阅 id 派生，见 {@link #isDue} */
    private static final int JITTER_PERCENT_RANGE = 20;

    /**
     * 「压根没搜到候选」的指纹取值。
     * <p>
     * 给它一个显式取值而不是留 null：它与「候选全被过滤规则淘汰」是<b>处置方向相反</b>的两种落空
     * （一个去看关键词与索引器，一个去调过滤规则），两者之间的切换必须能被
     * {@link #trySearch} 的指纹比较识别出来，从而再通知一次。
     * </p>
     */
    private static final String NO_CANDIDATE_SIGN = "NO_CANDIDATE";

    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtFilterConfigPlusService filterConfigService;
    private final SearchSupplementService searchSupplementService;
    private final IPtIndexerPlusService indexerService;

    /**
     * 单轮扫描的总耗时预算（毫秒），{@code <= 0} 表示不限制。默认 20 分钟，
     * 给 30 分钟的心跳间隔（{@link AutoSearchTask}）留出余量。
     * <p>
     * 订阅之间是串行的，而一条剧集订阅的墙钟由最慢的索引器决定
     * （{@code pt.search.indexer-budget-ms} 默认 30 秒，且那是软上限），最坏能到 40~50 秒。
     * 首次启动时所有订阅的 {@code last_search_time} 都是 null、全体同时到期，几十条订阅串行
     * 就能跑过一个心跳周期，于是下一次心跳被 {@code AutoSearchTask} 的重叠保护整个吞掉。
     * </p>
     * <p>
     * 超预算时剩下的订阅<b>原样留到下一轮</b>：它们的 {@code last_search_time} 没被改动，
     * 下轮心跳照样判定到期，而本轮搜过的那些已经不到期了，因此每轮天然从上次的断点接着往下走，
     * 不会有订阅被反复插队或长期饿死。
     * </p>
     * <p>
     * 刻意不用并发来解决这件事：{@code IndexerRateLimiter} 是全局的，多条订阅同时搜只会在
     * 限流器上排队，并把等待推到 {@code pt.indexer.max-wait-ms} 之外触发静默跳过——
     * 那正是 {@code SearchSupplementService#executePlan} 注释里记下的坑。
     * </p>
     */
    private final long roundBudgetMillis;

    public AutoSearchService(IPtSubscriptionPlusService subscriptionService,
                             IPtFilterConfigPlusService filterConfigService,
                             SearchSupplementService searchSupplementService,
                             IPtIndexerPlusService indexerService,
                             @Value("${pt.search.auto-search-round-budget-ms:1200000}") long roundBudgetMillis) {
        this.subscriptionService = subscriptionService;
        this.filterConfigService = filterConfigService;
        this.searchSupplementService = searchSupplementService;
        this.indexerService = indexerService;
        this.roundBudgetMillis = roundBudgetMillis;
    }

    /**
     * 扫一轮：对每个开启自动补搜、到期、且仍有缺集的订阅发起一次搜索。单个订阅异常不影响其他订阅。
     */
    public void run() {
        // 「ACTIVE + 开着开关 + 有 MISSING 集」三个条件由 SQL 完成，不再拉全部 ACTIVE 再内存过滤
        List<PtSubscriptionPlus> candidates = subscriptionService.listAutoSearchCandidates();
        if (candidates.isEmpty()) {
            return;
        }
        // 一个启用中的索引器都没有时整轮直接跳过并说明原因。否则每个订阅都会走完一遍
        // 搜索流程、各自得到「0 个候选」，日志里看起来像是「站上都没资源」，
        // 而实际上一个请求都没发出去过——用户会照着去改过滤规则和关键词
        if (indexerService.listEnabled().isEmpty()) {
            log.warn("没有启用中的索引器，本轮自动补搜跳过（共 {} 个待搜订阅）", candidates.size());
            return;
        }
        int intervalHours = resolveIntervalHours();
        long now = System.currentTimeMillis();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, roundBudgetMillis));
        int searched = 0;

        for (int i = 0; i < candidates.size(); i++) {
            PtSubscriptionPlus sub = candidates.get(i);
            if (!isDue(sub, intervalHours, now)) {
                continue;
            }
            if (budgetExhausted(deadline)) {
                // 放弃必须说出口，理由同 SearchSupplementService#runPlanOn：只写 debug 的话，
                // 用户看到的是「补搜好像不按周期跑」，而日志里一切正常
                log.warn("自动补搜已用满 {}ms 单轮预算，本轮搜了 {} 个订阅，剩余 {} 个候选留到下一轮"
                                + "（它们的 last_search_time 未改动，下轮心跳会接着搜）",
                        roundBudgetMillis, searched, candidates.size() - i);
                return;
            }
            try {
                trySearch(sub, intervalHours);
                searched++;
            } catch (Exception e) {
                log.warn("订阅[{}]自动补搜失败：{}", sub.getId(), e.getMessage());
            }
        }
    }

    /**
     * 是否落空由 {@link SearchAndPushSummary#isSkipped()}（无缺集/全是未播集/订阅不可搜）以及
     * {@link SearchAndPushSummary#anyPushed()} 共同决定；跳过的情况不触碰任何标记，
     * 因为跳过既不是"落空"也不是"命中"，不该覆盖上一次真正搜索留下的状态。
     * <p>
     * 通知只在两种边界发出：<b>首次落空</b>，以及<b>落空原因的种类变了</b>。后者不能省——
     * 第一次落空可能是「压根没搜到候选」，用户照着改了关键词，下一轮变成「候选全被 freeOnly
     * 淘汰」，处置方向完全反过来了；只按"上次是否已落空"去重会把这次翻转吃掉，而它恰恰是
     * 用户最需要知道的一次变化。至于长期缺集的老剧，仍然不会每轮都被打扰。
     * </p>
     * <p>
     * 与旧实现的两处差别：<b>每次落空都会写库</b>（累加连续落空次数，退避靠这个计数）；
     * 写库走 {@code updateAutoSearchMissState} 而不是 {@code updateById(sub)}——手里这份订阅是
     * 本轮开始时查的，{@code last_search_time} 已被本次搜索更新过，整实体写回会把它覆盖成旧值，
     * 让订阅永远"到期"、每次心跳都重搜（原因见该方法的 javadoc）。
     * </p>
     */
    private void trySearch(PtSubscriptionPlus sub, int intervalHours) {
        SearchAndPushSummary summary = searchSupplementService.searchAndPushMissing(sub.getId());
        if (summary.isSkipped()) {
            return;
        }
        int streak = missStreakOf(sub);
        if (summary.anyPushed()) {
            if (streak > 0 || sub.getLastAutoSearchRejectSign() != null) {
                subscriptionService.updateAutoSearchMissState(sub.getId(), 0, null);
            }
            return;
        }

        String sign = StringUtils.isBlank(summary.getRejectSignature())
                ? NO_CANDIDATE_SIGN : summary.getRejectSignature();
        int nextStreak = streak + 1;
        if (streak == 0 || !sign.equals(sub.getLastAutoSearchRejectSign())) {
            notifySafely(describeNoResult(sub, summary.getRejectSummary(), nextStreak, intervalHours), sub);
        }
        subscriptionService.updateAutoSearchMissState(sub.getId(), nextStreak, sign);
    }

    /**
     * 落空通知的文案：能说出「被自己的过滤规则淘汰了多少」就直说，别再一律提示检查索引器。
     * <p>
     * 这两种落空是完全不同的处置方向：候选被规则淘汰要去调过滤配置，压根没搜到候选才该看
     * 关键词与索引器。旧文案不加区分地写"检查索引器配置"，在前一种情况下把用户引向了
     * 一个根本没问题的地方——索引器好好地返回了上百个候选，是 freeOnly 或分辨率白名单全清了。
     * </p>
     * <p>
     * 末尾必须报出下次重试的间隔：退避之后实际周期不再等于用户配的那个值，不说明的话
     * 用户看到的是「我配了 24 小时，怎么两天没动静」，而这是有意的。
     * </p>
     */
    private String describeNoResult(PtSubscriptionPlus sub, String rejectSummary, int nextStreak, int intervalHours) {
        String title = StringUtils.escapeHtml(sub.getTitle());
        long nextHours = effectiveIntervalMillis(sub, nextStreak, intervalHours) / 3600_000L;
        String tail = "（已连续 " + nextStreak + " 轮落空，下次约 " + nextHours
                + " 小时后再试；本提醒在落空原因变化或再次搜到资源前只发一次）";
        if (StringUtils.isNotBlank(rejectSummary)) {
            return "🔍 订阅[" + title + "] 自动补搜未推送任何资源——" + StringUtils.escapeHtml(rejectSummary)
                    + "。请检查过滤规则是否过严" + tail;
        }
        return "🔍 订阅[" + title + "] 自动补搜未找到可用资源，"
                + "可等待 RSS 命中，或检查关键词与索引器配置" + tail;
    }

    /**
     * 是否到期。基准是全局周期，再叠两个修正：
     * <ol>
     *   <li><b>连续落空退避</b>（{@link #effectiveIntervalMillis}）：片源确实不存在的老剧不该
     *       永远每 24 小时打满一整轮索引器请求，而这件事用户从现象上根本看不出来。</li>
     *   <li><b>按 id 派生的确定性抖动</b>：首次启动时所有订阅的 {@code last_search_time} 都是
     *       null、全体同时到期，串行跑完后它们的 {@code last_search_time} 又几乎相同，于是一个
     *       周期后再次聚在一起——这个抱团是自我维持的，不会自己散开。抖动只<b>向后</b>
     *       （0 ~ +{@value #JITTER_PERCENT_RANGE}%），保证实际周期不会短于用户配的值；
     *       用 id 而不是随机数，同一订阅每轮算出同一个偏移，行为可复现、也写得出测试。</li>
     * </ol>
     * 从未搜索过的订阅恒到期、不抖动：建订阅补搜（{@code SubscriptionSearchOnCreateTrigger}）
     * 跑过之后 {@code last_search_time} 就有值了，这里的 null 主要是升级上来的存量库，
     * 让它们先搜一次，抱团只会发生这一次。
     */
    private boolean isDue(PtSubscriptionPlus sub, int intervalHours, long now) {
        if (sub.getLastSearchTime() == null) {
            return true;
        }
        return now - sub.getLastSearchTime().getTime()
                >= effectiveIntervalMillis(sub, missStreakOf(sub), intervalHours);
    }

    /**
     * 该订阅本次实际使用的周期：基准 × 落空退避倍数（封顶 {@link #MAX_INTERVAL_MILLIS}）
     * + 按 id 派生的向后抖动。
     *
     * @param streak 连续落空次数；0 表示上轮有命中或还没跑过，用基准周期
     */
    private long effectiveIntervalMillis(PtSubscriptionPlus sub, int streak, int intervalHours) {
        long base = intervalHours * 3600_000L;
        long backed = streak <= 0 ? base
                : Math.min(base * (1L << Math.min(streak, MAX_BACKOFF_SHIFT)), MAX_INTERVAL_MILLIS);
        int id = sub.getId() == null ? 0 : Math.abs(sub.getId());
        return backed + backed / 100 * (id % (JITTER_PERCENT_RANGE + 1));
    }

    /** 连续落空次数，null 视为 0（存量行、或刚从 char 列迁移过来的值） */
    private int missStreakOf(PtSubscriptionPlus sub) {
        Integer streak = sub.getLastAutoSearchNoResult();
        return streak == null || streak < 0 ? 0 : streak;
    }

    /**
     * 单轮预算是否已耗尽。{@code roundBudgetMillis <= 0} 表示不限制。
     * 用差值与 0 比较而不是直接比大小，是 {@code nanoTime} 的惯用写法（它的绝对值无意义）。
     */
    private boolean budgetExhausted(long deadline) {
        return roundBudgetMillis > 0 && deadline - System.nanoTime() < 0;
    }

    private int resolveIntervalHours() {
        PtFilterConfigPlus config = filterConfigService.getConfig();
        Integer hours = config.getAutoSearchIntervalHours();
        return hours == null ? DEFAULT_INTERVAL_HOURS : hours;
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
}
