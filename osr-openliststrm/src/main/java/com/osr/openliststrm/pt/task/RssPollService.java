package com.osr.openliststrm.pt.task;

import com.osr.common.utils.StringUtils;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.helper.TgHelper;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.pt.indexer.GuidHasher;
import com.osr.openliststrm.pt.indexer.IndexerBackpressureException;
import com.osr.openliststrm.pt.indexer.IndexerHttpException;
import com.osr.openliststrm.pt.indexer.IndexerRateLimiter;
import com.osr.openliststrm.pt.indexer.TorznabClient;
import com.osr.openliststrm.pt.model.TorrentInfo;
import com.osr.openliststrm.pt.subscription.SubscriptionEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * RSS 轮询编排：遍历到期的索引器拉取种子，汇总后交给推送引擎。
 * 多索引器并发拉取，避免单索引器拖慢整轮；并发数可配置（默认 4）。
 *
 * @author Jack
 */
@Slf4j
@Service
public class RssPollService {

    /** 连续失败达到该次数时发告警，只在恰好达到时发一次，避免每轮刷屏 */
    private static final int ALERT_FAIL_THRESHOLD = 3;

    /**
     * 连续失败达到该次数时自动停用该索引器，避免长期失效的索引器每轮空耗轮询周期与告警配额。
     * 停用后 {@link IPtIndexerPlusService#listEnabled()} 不再返回它，fail_count 不再增长，
     * 因此这条分支天然只会触发一次，用户需去索引器管理页手动重新启用（同时应先修好配置）。
     */
    private static final int DISABLE_FAIL_THRESHOLD = 10;

    private static final String ENABLED = "1";
    private static final String DISABLED = "0";

    /** 连续失败时轮询间隔的最大翻倍次数：2^5 = 32 倍，10 分钟的间隔最多退到 5 小时余 */
    private static final int MAX_BACKOFF_SHIFT = 5;

    /** 索引器未给出 Retry-After 时，命中 429/503 的默认冷却秒数 */
    private static final int DEFAULT_THROTTLE_COOLDOWN_SECONDS = 300;

    /**
     * pubDate 允许超前当前时间的幅度，超出即视为站点时钟不同步或填错，剔除不参与游标计算。
     * <p>
     * 放任一条未来时间成为游标，之后每一轮的"窗口下沿 &lt;= 上轮游标"都会恒成立，
     * 覆盖度判定就此永久失效且不发任何告警——静默失效比误报难查得多。
     * </p>
     */
    private static final Duration FUTURE_TOLERANCE = Duration.ofHours(1);

    /**
     * 同时拉取多个到期索引器的最大并发数。
     * <p>
     * 注意这只是本轮轮询内部的一道次级闸门，<b>不是</b>对站点的真正节流——真正的节流在
     * {@link com.osr.openliststrm.pt.indexer.IndexerRateLimiter}（全局单例，按索引器串行化
     * 并强制最小请求间隔，且对搜索补集等其它入口一并生效）。历史上这里的注释曾把它当作
     * "避免打爆站点"的手段，但每轮新建的信号量拦不住跨调用叠加的请求量。
     * </p>
     */
    private final int maxConcurrency;

    /**
     * 自动停用后的冷却期（小时）：冷却期内不重复探测，避免对已知失效的索引器每轮心跳都打一次站点。
     */
    private final int selfHealCooldownHours;

    /**
     * 覆盖度校验的窗口截断线（小时）：只有发布时间在这个范围内的条目才参与"窗口下沿"的计算。
     * <p>
     * 用来剔除置顶种子这类远古条目——它们会把整页的最老时间拉到几年前，让判据恒成立、
     * 真漏拉被静音。默认 24 小时，远大于任何合理的轮询周期，因此这个剔除不损失灵敏度。
     * </p>
     */
    private final int coverageWindowHours;

    private final IPtIndexerPlusService indexerService;
    private final TorznabClient torznabClient;
    private final SubscriptionEngine subscriptionEngine;
    private final IndexerRateLimiter rateLimiter;

    public RssPollService(IPtIndexerPlusService indexerService,
                          TorznabClient torznabClient,
                          SubscriptionEngine subscriptionEngine,
                          IndexerRateLimiter rateLimiter,
                          @Value("${pt.indexer.self-heal-cooldown-hours:2}") int selfHealCooldownHours,
                          @Value("${pt.indexer.max-concurrency:4}") int maxConcurrency,
                          @Value("${pt.indexer.coverage-window-hours:24}") int coverageWindowHours) {
        this.indexerService = indexerService;
        this.torznabClient = torznabClient;
        this.subscriptionEngine = subscriptionEngine;
        this.rateLimiter = rateLimiter;
        this.selfHealCooldownHours = selfHealCooldownHours;
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.coverageWindowHours = Math.max(1, coverageWindowHours);
    }

    /**
     * 轮询一轮：先对冷却期已过的停用索引器做一次自愈探测，再并发拉取所有到期索引器的种子。
     *
     * @return 本轮结果，日志由调用方汇总打印（这样「什么都没发生」那一轮也能被心跳兜住）
     */
    public PollOutcome poll() {
        selfHeal();

        List<PtIndexerPlus> indexers = indexerService.listEnabled();
        long now = System.currentTimeMillis();

        // 只处理到期的索引器
        List<PtIndexerPlus> due = new ArrayList<>();
        for (PtIndexerPlus indexer : indexers) {
            if (isDue(indexer, now)) {
                due.add(indexer);
            }
        }
        if (due.isEmpty()) {
            // 各索引器有自己的轮询间隔，"本轮没有到期的"是常态而非异常
            return PollOutcome.NOTHING_DUE;
        }

        // 并发拉取所有到期索引器，各索引器互不依赖
        List<TorrentInfo> allTorrents = new CopyOnWriteArrayList<>();
        Semaphore limiter = new Semaphore(maxConcurrency);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = due.stream()
                    .map(indexer -> CompletableFuture.runAsync(Threads.wrap(() ->
                            runLimited(limiter, () -> pollOne(indexer, allTorrents))), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        // 这里刻意不发「本轮推送了 N 个种子」的汇总<b>通知</b>。三条理由，每条单独都够：
        // ①它与 SubscriptionEngine 逐条发出的「订阅命中」完全重复——推 3 个种子，
        //   用户会收到 3 条命中详情外加 1 条只有数字的汇总；
        // ②它是广播（拿不到归属人），多用户环境下 B 会收到"推送了 3 个种子"，
        //   而那 3 条命中详情只发给了 A，B 只能看见一个无从追查的数字；
        // ③信息量本就为零，日志里记着就够排查了。
        // 注意这条只针对通知——<b>日志</b>反过来必须记，它是「RSS 还在拉」的唯一证据。
        int pushed = allTorrents.isEmpty() ? 0 : subscriptionEngine.process(allTorrents);
        return new PollOutcome(due.size(), allTorrents.size(), pushed);
    }

    /**
     * 一轮 RSS 轮询的结果。
     *
     * @param dueIndexers 本轮到期、实际发起拉取的索引器数
     * @param torrents    拉回来的种子总数
     * @param pushed      其中推送给下载器的数量
     */
    public record PollOutcome(int dueIndexers, int torrents, int pushed) {

        static final PollOutcome NOTHING_DUE = new PollOutcome(0, 0, 0);

        /** 本轮是否发生了值得记一条 INFO 的事 */
        public boolean changed() {
            return pushed > 0;
        }
    }

    /**
     * 在信号量许可证下执行任务，避免同时向过多索引器发起请求。
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

    /**
     * 拉取单个索引器：成功则清零 fail_count，失败则累加并可能自动停用。
     * 各索引器互不依赖，线程安全地写入 collector。
     * 包可见（而非 private）是为了让测试绕开 poll() 里的虚拟线程池，直接在测试线程调用——
     * 否则 MockedStatic&lt;TgHelper&gt; 只在注册线程生效，跨到虚拟线程会被判定为零交互。
     * <p>
     * <b>无论成功失败都必须写 {@code lastPollTime}</b>。这个字段是 {@link #isDue} 的唯一依据，
     * 早先只在成功分支写，导致拉取失败的索引器立刻又"到期"，被 60 秒的心跳每轮重打——
     * 本该 10 分钟一次的轮询塌缩成 1 分钟一次。而站点返回 429/503 恰恰意味着"你打太快了"，
     * 于是系统对限流信号的反应是把频率再提高 10 倍，正反馈直到被 ban。
     * </p>
     * <p>
     * 三条失败分支按"这次失败该不该记在索引器账上"划分，顺序不能乱：
     * {@link IndexerBackpressureException}（请求没发出去，不计账）→
     * {@link IndexerHttpException} 且 {@code isThrottled()}（对方让你慢点，只冷却不计账）→
     * 其余一切（真失败，计账并退避）。前两者都是 IOException 的子类，
     * 漏掉任一个 catch 都会静默落回最后那条通用分支。
     * </p>
     */
    void pollOne(PtIndexerPlus indexer, List<TorrentInfo> collector) {
        try {
            List<TorrentInfo> fetched = torznabClient.fetch(indexer);
            collector.addAll(fetched);
            checkCoverageGap(indexer, fetched);
            indexer.setLastStatus("OK");
            indexer.setFailCount(0);
            log.info("索引器[{}]拉取到 {} 条种子", indexer.getName(), fetched.size());
        } catch (IndexerBackpressureException e) {
            handleBackpressure(indexer, e);
        } catch (IndexerHttpException e) {
            if (e.isThrottled()) {
                handleThrottled(indexer, e);
            } else {
                handleFailure(indexer, e);
            }
        } catch (Exception e) {
            handleFailure(indexer, e);
        }
        // 成功与失败共用：失败同样要推进轮询时钟，否则退避无从谈起
        indexer.setLastPollTime(new Date());
        indexerService.updateById(indexer);
    }

    /**
     * 本地背压导致请求根本没发出去（限流冷却期内、等许可超时）：<b>既不累加也不清零 fail_count</b>。
     * <p>
     * 这是 {@link #handleThrottled} 的必要补充。命中 429 时我们刻意不计失败，但紧接着的几轮
     * 都会撞上自己设的冷却期而快速失败，若按普通失败处理，那次"不计失败"就被原样绕开了：
     * fail_count 照样涨，退避照样把 5 分钟的周期放大到几十分钟，两次<b>成功</b>拉取之间的窗口
     * 随之拉长，最后由 {@link #checkCoverageGap} 报"拉取窗口覆盖不全"。用户此时的自然反应是
     * 再缩短轮询周期，而那只会让请求更密、更容易撞冷却——正反馈。
     * </p>
     * <p>
     * 不清零也是有意的：本轮没跟索引器说上话，拿不到"它已经好了"的任何证据，
     * 把此前累计的失败次数抹掉等于凭空解除退避。
     * </p>
     */
    private void handleBackpressure(PtIndexerPlus indexer, IndexerBackpressureException e) {
        indexer.setLastStatus(truncate("本轮跳过：" + e.getMessage()));
        log.info("索引器[{}]本轮跳过，请求未发出，不计入失败次数：{}", indexer.getName(), e.getMessage());
    }

    /**
     * 命中 429/503：交给限流器冷却，<b>不计入 fail_count</b>。
     * <p>
     * 这两个状态说的是"慢一点"而不是"我坏了"——索引器配置完好，继续累加失败次数最终把它
     * 自动停用属于误伤。冷却记在 {@link IndexerRateLimiter} 而非本类，因此对用户此刻手动
     * 发起的搜索一并生效，不会有人绕过退避继续捅同一个站点。
     * </p>
     */
    private void handleThrottled(PtIndexerPlus indexer, IndexerHttpException e) {
        int cooldown = e.getRetryAfterSeconds() == null
                ? DEFAULT_THROTTLE_COOLDOWN_SECONDS : e.getRetryAfterSeconds();
        rateLimiter.penalize(indexer.getId(), cooldown);
        indexer.setLastStatus(truncate("被限流(HTTP " + e.getStatusCode() + ")，冷却 " + cooldown + " 秒"));
        log.warn("索引器[{}]被限流（HTTP {}），冷却 {} 秒后再试，本次不计入失败次数",
                indexer.getName(), e.getStatusCode(), cooldown);
    }

    /** 真正的失败：累加 fail_count（进而拉长退避间隔），达阈值告警/自动停用 */
    private void handleFailure(PtIndexerPlus indexer, Exception e) {
        int fails = (indexer.getFailCount() == null ? 0 : indexer.getFailCount()) + 1;
        indexer.setFailCount(fails);
        indexer.setLastStatus(truncate(e.getMessage()));
        log.warn("索引器[{}]拉取失败（第{}次），下次轮询间隔将退避至 {} 倍：{}",
                indexer.getName(), fails, backoffMultiplier(fails), e.getMessage());
        if (fails >= DISABLE_FAIL_THRESHOLD && ENABLED.equals(indexer.getEnabled())) {
            indexer.setEnabled(DISABLED);
            indexer.setDisabledAt(new Date());
            log.warn("索引器[{}]连续失败 {} 次，已自动停用", indexer.getName(), fails);
            notifySafely("🛑 索引器[" + StringUtils.escapeHtml(indexer.getName()) + "]已连续失败 " + fails + " 次，已自动停用，"
                    + "冷却 " + selfHealCooldownHours + " 小时后将自动尝试恢复");
        } else if (fails == ALERT_FAIL_THRESHOLD) {
            notifySafely("⚠️ 索引器[" + StringUtils.escapeHtml(indexer.getName()) + "]已连续失败 " + fails
                    + " 次：" + StringUtils.escapeHtml(e.getMessage()));
        }
    }

    /**
     * 对冷却期已过的停用索引器做一次轻量连通性探测，成功则自动重新启用。
     * 只处理 {@code disabledAt} 非空的索引器——该字段只在"连续失败自动停用"分支被写入，
     * 人工手动停用的索引器 disabledAt 为空，不会被这里误判为可自愈而抢先重新启用。
     * 探测失败只重置冷却计时，不发通知，避免长期失效的索引器每次冷却到期都刷一条告警。
     */
    private void selfHeal() {
        List<PtIndexerPlus> disabled = indexerService.listDisabled();
        long now = System.currentTimeMillis();
        for (PtIndexerPlus indexer : disabled) {
            if (!eligibleForSelfHeal(indexer, now)) {
                continue;
            }
            if (torznabClient.testConnection(indexer)) {
                indexer.setEnabled(ENABLED);
                indexer.setFailCount(0);
                indexer.setDisabledAt(null);
                indexer.setLastStatus("OK");
                indexerService.updateById(indexer);
                log.info("索引器[{}]自愈探测成功，已自动重新启用", indexer.getName());
                notifySafely("✅ 索引器[" + StringUtils.escapeHtml(indexer.getName()) + "]自愈探测成功，已自动重新启用");
            } else {
                indexer.setDisabledAt(new Date());
                indexerService.updateById(indexer);
                log.debug("索引器[{}]自愈探测仍失败，冷却重新计时", indexer.getName());
            }
        }
    }

    private boolean eligibleForSelfHeal(PtIndexerPlus indexer, long now) {
        if (indexer.getDisabledAt() == null) {
            return false;
        }
        return now - indexer.getDisabledAt().getTime() >= selfHealCooldownHours * 3600_000L;
    }

    /**
     * 校验本轮拉取窗口是否完整覆盖了上一轮的位置：Torznab RSS 拉取无游标/时间参数支持，
     * 每轮只能拿到索引器首页种子，若发布速度超过 (首页容量)/(轮询间隔)，两轮之间被挤出
     * 首页的种子会被永久跳过且无法被 {@code excludeAlreadyRecorded} 之外的机制察觉。
     * 假定 Torznab RSS 按发布时间降序返回（标准约定），取本轮首条种子的 guid 作为新游标；
     * 若上一轮游标未出现在本轮结果中，说明存在覆盖不到的漏拉窗口，仅记警告+告警一次，
     * 不尝试补救（Torznab RSS 语义上无法找回已漏掉的种子）。
     * <p>
     * <b>判据只依赖时间戳，不依赖条目身份</b>：记上一轮的 {@code max(pubDate)}，本轮检查
     * </p>
     * <pre>
     *   本轮窗口下沿 &lt;= 上一轮 max(pubDate)
     * </pre>
     * <p>
     * 成立即说明两轮的窗口首尾相接、中间没有断档。历史上这里用的是"上一轮首条种子的 guid
     * 还在不在本轮结果里"，那个判据同时依赖三个前提，任一条不成立就<b>恒定误报</b>——
     * 而误报的表现与真漏拉一模一样，用户唯一能做的反应（缩短轮询周期）还恰好是错的：
     * </p>
     * <ol>
     *   <li><b>首条即最新</b>：把"索引器按 pubDate 降序返回"这个假设当成了事实。置顶种子、
     *       促销置顶都会让游标记在一条非最新的条目上，而这类条目下一轮很可能已经不在了。</li>
     *   <li><b>那条种子下一轮还在</b>：删种、审核下架、管理员挪分类（而我们带着 {@code cat} 过滤）
     *       都会让它凭空消失，跟窗口够不够毫无关系。</li>
     *   <li><b>guid 逐轮稳定</b>：部分索引器的 guid 带一次性 token，
     *       {@link com.osr.openliststrm.pt.indexer.TorznabParser} 在 guid 缺失时
     *       还会把它降级成 downloadUrl。</li>
     * </ol>
     * <p>
     * <b>窗口下沿取"{@code coverageWindowHours} 小时内条目的最早发布时间"，而不是整页最老那条。</b>
     * 一条 2015 年的置顶种子会把整页的最老时间拉到十年前，判据随之恒成立，真漏拉也一起被静音。
     * 之所以能放心剔除，是因为截断线（默认 24 小时）<b>远大于</b>轮询周期（默认 600 秒）：
     * 被剔掉的条目绝无可能影响"上一轮到本轮这几分钟有没有被覆盖"这个判断，
     * 所以这个剔除不损失任何灵敏度，纯粹是去噪。
     * </p>
     */
    private void checkCoverageGap(PtIndexerPlus indexer, List<TorrentInfo> fetched) {
        // 本轮无新种子是正常情况（索引器该轮未发布新内容），不能当作拉取失败处理
        if (fetched.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        Instant futureLimit = now.plus(FUTURE_TOLERANCE);
        Instant cutoff = now.minus(Duration.ofHours(coverageWindowHours));

        // 一次遍历同时挑出：最新条目（按 pubDate，不是按下标）、窗口下沿、各类噪声计数
        TorrentInfo newestItem = null;
        Instant newest = null;
        Instant floor = null;
        int inWindow = 0;
        int tooOld = 0;
        int unparsable = 0;
        int fromFuture = 0;
        Instant oldestOverall = null;
        for (TorrentInfo info : fetched) {
            Instant at = parsePubDate(info.getPubDate());
            if (at == null) {
                unparsable++;
                continue;
            }
            // 站点时钟不同步或干脆填错，会产生一个远在未来的 pubDate。放任它成为游标的话，
            // 之后每一轮的"下沿 <= 上轮游标"都恒成立，判据永久失效且完全静默——比误报难查得多
            if (at.isAfter(futureLimit)) {
                fromFuture++;
                continue;
            }
            if (oldestOverall == null || at.isBefore(oldestOverall)) {
                oldestOverall = at;
            }
            if (newest == null || at.isAfter(newest)) {
                newest = at;
                newestItem = info;
            }
            if (at.isBefore(cutoff)) {
                tooOld++;
                continue;
            }
            inWindow++;
            if (floor == null || at.isBefore(floor)) {
                floor = at;
            }
        }

        if (newest == null) {
            // 一条可用的 pubDate 都没有，只能退回 guid 游标
            checkCoverageGapByGuid(indexer, fetched, unparsable, fromFuture);
            return;
        }

        Instant previousCursor = indexer.getLastSeenPubTime() == null
                ? null : indexer.getLastSeenPubTime().toInstant();
        if (previousCursor == null) {
            log.debug("索引器[{}]首次记录时间游标：{}", indexer.getName(), newest);
        } else if (floor == null) {
            // 整页里连一条 coverageWindowHours 小时内的都没有 → 该站发布极慢，窗口至少覆盖这么久，不可能漏
            log.debug("索引器[{}]拉取窗口远大于轮询周期（{} 小时内无新发布），跳过覆盖度判定",
                    indexer.getName(), coverageWindowHours);
        } else if (floor.isAfter(previousCursor)) {
            int fails = indexer.getFailCount() == null ? 0 : indexer.getFailCount();
            log.warn("索引器[{}]本轮拉取窗口未接上上一轮，{} ~ {} 这段时间发布的种子没有被看到。"
                            + "本页 {} 条（{} 小时内 {} 条{}），窗口下沿 {}，上轮游标 {}，缺口 {}；"
                            + "轮询周期 {} 秒，fail_count={}（退避 {} 倍，实际间隔 {} 秒）",
                    indexer.getName(), previousCursor, floor,
                    fetched.size(), coverageWindowHours, inWindow, describeNoise(tooOld, oldestOverall, unparsable, fromFuture),
                    floor, previousCursor, formatDuration(Duration.between(previousCursor, floor)),
                    indexer.getPollInterval(), fails, backoffMultiplier(fails),
                    (indexer.getPollInterval() == null ? 600 : indexer.getPollInterval()) * backoffMultiplier(fails));
            notifySafely("⚠️ 索引器[" + StringUtils.escapeHtml(indexer.getName()) + "]拉取窗口覆盖不全，"
                    + formatDuration(Duration.between(previousCursor, floor)) + "内发布的种子可能被漏拉。"
                    + "先看该索引器的失败次数（不为 0 时退避已把实际间隔放大数倍），再考虑缩短轮询周期"
                    + "或让索引器提高单页返回数");
        } else {
            Duration margin = Duration.between(floor, previousCursor);
            log.debug("索引器[{}]拉取窗口正常：本页 {} 条（{} 小时内 {} 条{}），窗口下沿 {}，上轮游标 {}，余量 {}",
                    indexer.getName(), fetched.size(), coverageWindowHours, inWindow,
                    describeNoise(tooOld, oldestOverall, unparsable, fromFuture), floor, previousCursor,
                    formatDuration(margin));
        }

        indexer.setLastSeenPubTime(Date.from(newest));
        // 兜底游标同步维护：取 pubDate 最新那条的 guid（而不是首条），
        // 免得将来某天该索引器的 pubDate 忽然不可用时，兜底判据拿着一个陈旧且记错位置的游标
        if (newestItem != null && StringUtils.isNotBlank(newestItem.getGuid())) {
            indexer.setLastSeenGuidHash(GuidHasher.hash(newestItem.getGuid()));
        }
    }

    /**
     * pubDate 完全不可用时的兜底判据：仍按"上一轮首条种子的 guid 还在不在本轮结果里"判断。
     * <p>
     * 这条路径继承了时间游标想要摆脱的全部脆弱性（见 {@link #checkCoverageGap} 的三条前提），
     * 所以日志里必须写明"兜底判据"——否则以后看到告警又要从头推理一遍是哪种成因。
     * 保留它是因为对这类索引器确实没有别的信号可用，有个粗糙的判断好过完全没有。
     * </p>
     */
    private void checkCoverageGapByGuid(PtIndexerPlus indexer, List<TorrentInfo> fetched,
                                        int unparsable, int fromFuture) {
        String newestGuid = fetched.get(0).getGuid();
        if (StringUtils.isBlank(newestGuid)) {
            return;
        }
        String previousCursor = indexer.getLastSeenGuidHash();
        if (previousCursor != null && indexOfCursor(fetched, previousCursor) < 0) {
            log.warn("索引器[{}]本轮拉取窗口未覆盖上次记录点，期间可能有种子被跳过。"
                            + "【该索引器 pubDate 全部不可用（{} 条无法解析、{} 条时间在未来），走的是 guid 兜底判据，"
                            + "种子被删或 guid 每轮变化都会导致误报】本页 {} 条，"
                            + "最新条目「{}」guid#{}，上轮游标#{}",
                    indexer.getName(), unparsable, fromFuture, fetched.size(),
                    fetched.get(0).getTitle(), shortHash(GuidHasher.hash(newestGuid)), shortHash(previousCursor));
            notifySafely("⚠️ 索引器[" + StringUtils.escapeHtml(indexer.getName()) + "]拉取窗口覆盖不全，可能有种子被漏拉。"
                    + "该索引器没有可用的发布时间，走的是精度较低的兜底判据，先看日志确认不是误报");
        }
        indexer.setLastSeenGuidHash(GuidHasher.hash(newestGuid));
    }

    /** 上轮游标在本轮结果中的下标，未命中返回 -1；仅兜底判据使用 */
    private static int indexOfCursor(List<TorrentInfo> fetched, String cursor) {
        for (int i = 0; i < fetched.size(); i++) {
            String guid = fetched.get(i).getGuid();
            if (StringUtils.isNotBlank(guid) && cursor.equals(GuidHasher.hash(guid))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 把被剔除的条目摆到日志里。{@code tooOld} 就是这一页里置顶/远古条目的数量——
     * 有了它不必再去索引器后台翻发布时间分布，一眼就能看出窗口下沿为什么是这个值。
     */
    private static String describeNoise(int tooOld, Instant oldestOverall, int unparsable, int fromFuture) {
        StringBuilder sb = new StringBuilder();
        if (tooOld > 0) {
            sb.append("，剔除 ").append(tooOld).append(" 条更早的（最老 ").append(oldestOverall).append("）");
        }
        if (unparsable > 0) {
            sb.append("，").append(unparsable).append(" 条 pubDate 不可解析");
        }
        if (fromFuture > 0) {
            sb.append("，").append(fromFuture).append(" 条时间在未来已忽略");
        }
        return sb.toString();
    }

    /** 时长的人话形式，日志里比裸秒数好读 */
    private static String formatDuration(Duration duration) {
        long seconds = Math.abs(duration.getSeconds());
        if (seconds < 300) {
            return seconds + " 秒";
        }
        if (seconds < 7200) {
            return (seconds / 60) + " 分钟";
        }
        return String.format("%.1f 小时", seconds / 3600.0);
    }

    /** 解析 Torznab 的 pubDate（RFC 1123 为主，兼容少数发 ISO-8601 的索引器）；解析不出返回 null */
    private static Instant parsePubDate(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(trimmed).toInstant();
            } catch (DateTimeParseException ignored2) {
                return null;
            }
        }
    }

    /**
     * 取哈希前 8 位用于日志比对。<b>绝不能改成打 guid 原文</b>——
     * guid 在缺失时会降级为 downloadUrl，而 PT 站的下载链接里常含 passkey。
     */
    private static String shortHash(String hash) {
        if (hash == null) {
            return "null";
        }
        return hash.length() <= 8 ? hash : hash.substring(0, 8);
    }

    /**
     * 是否到期。基础间隔取索引器自身的 {@code pollInterval}（默认 600 秒），并按连续失败次数
     * 指数退避——失败越多，下次去打扰它的间隔越长。成功一次 {@code failCount} 归零，
     * 退避随之自动解除，不需要额外的恢复逻辑。
     */
    private boolean isDue(PtIndexerPlus indexer, long now) {
        if (indexer.getLastPollTime() == null) {
            return true;
        }
        int interval = indexer.getPollInterval() == null ? 600 : indexer.getPollInterval();
        int fails = indexer.getFailCount() == null ? 0 : indexer.getFailCount();
        long effectiveInterval = interval * 1000L * backoffMultiplier(fails);
        return now - indexer.getLastPollTime().getTime() >= effectiveInterval;
    }

    /** 连续失败次数 → 轮询间隔倍数：1, 2, 4, 8, 16, 32（封顶） */
    private long backoffMultiplier(int failCount) {
        if (failCount <= 0) {
            return 1L;
        }
        return 1L << Math.min(failCount, MAX_BACKOFF_SHIFT);
    }

    private String truncate(String msg) {
        if (msg == null) {
            return "未知错误";
        }
        return msg.length() > 480 ? msg.substring(0, 480) : msg;
    }

    private void notifySafely(String msg) {
        try {
            TgHelper.sendMsg(msg);
        } catch (Exception e) {
            log.debug("发送通知失败（不影响主流程）：{}", e.getMessage());
        }
    }
}
