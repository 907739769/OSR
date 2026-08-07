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

    private final IPtIndexerPlusService indexerService;
    private final TorznabClient torznabClient;
    private final SubscriptionEngine subscriptionEngine;
    private final IndexerRateLimiter rateLimiter;

    public RssPollService(IPtIndexerPlusService indexerService,
                          TorznabClient torznabClient,
                          SubscriptionEngine subscriptionEngine,
                          IndexerRateLimiter rateLimiter,
                          @Value("${pt.indexer.self-heal-cooldown-hours:2}") int selfHealCooldownHours,
                          @Value("${pt.indexer.max-concurrency:4}") int maxConcurrency) {
        this.indexerService = indexerService;
        this.torznabClient = torznabClient;
        this.subscriptionEngine = subscriptionEngine;
        this.rateLimiter = rateLimiter;
        this.selfHealCooldownHours = selfHealCooldownHours;
        this.maxConcurrency = Math.max(1, maxConcurrency);
    }

    /**
     * 轮询一轮：先对冷却期已过的停用索引器做一次自愈探测，再并发拉取所有到期索引器的种子。
     */
    public void poll() {
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
            return;
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

        if (!allTorrents.isEmpty()) {
            int pushed = subscriptionEngine.process(allTorrents);
            if (pushed > 0) {
                notifySafely("📥 本轮为订阅推送了 " + pushed + " 个种子");
            }
            log.info("本轮共拉取 {} 条种子，推送 {} 个", allTorrents.size(), pushed);
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
     * <b>这条告警有三种成因，只看告警本身分辨不出来，所以日志里必须带够判据</b>：
     * </p>
     * <ol>
     *   <li><b>窗口真的不够</b>：{@code pubDate 跨度} 明显小于本索引器的轮询周期，说明单页容量
     *       跟不上发布速度。这是告警字面意思成立的唯一情形。</li>
     *   <li><b>退避把间隔放大了</b>：{@code fail_count} 不为 0 时实际间隔是配置值的 2~32 倍
     *       （见 {@link #isDue}），游标比的是两次<b>成功</b>拉取之间的窗口，改配置里的周期无济于事。</li>
     *   <li><b>guid 不稳定</b>：部分索引器的 guid 带一次性 token 或时间戳（{@code TorznabParser}
     *       在 guid 缺失时还会降级用 downloadUrl），同一条目每轮的 guid 都不同，游标永远匹配不上，
     *       于是<b>每一轮</b>都告警，与间隔无关。判据是相邻两轮日志里同一标题的 {@code guid#} 是否变化——
     *       所以日志打的是标题 + 哈希前缀，<b>不能打 guid 原文</b>（PT 站的链接里常含 passkey）。</li>
     * </ol>
     */
    private void checkCoverageGap(PtIndexerPlus indexer, List<TorrentInfo> fetched) {
        // 本轮无新种子是正常情况（索引器该轮未发布新内容），不能当作拉取失败处理
        if (fetched.isEmpty()) {
            return;
        }
        // guid 缺失的条目无法参与游标计算（正常 Torznab 响应不会出现，防御性跳过而非抛异常中断整轮拉取）
        String newestGuid = fetched.get(0).getGuid();
        if (StringUtils.isBlank(newestGuid)) {
            return;
        }
        String previousCursor = indexer.getLastSeenGuidHash();
        if (previousCursor != null) {
            int hit = indexOfCursor(fetched, previousCursor);
            if (hit < 0) {
                int fails = indexer.getFailCount() == null ? 0 : indexer.getFailCount();
                log.warn("索引器[{}]本轮拉取窗口未覆盖上次记录点，期间可能有种子被跳过。"
                                + "本轮 {} 条，{}；最新条目「{}」guid#{}，上轮游标#{}；"
                                + "轮询周期 {} 秒，fail_count={}（退避 {} 倍，实际间隔 {} 秒）",
                        indexer.getName(), fetched.size(), describeWindow(fetched),
                        fetched.get(0).getTitle(), shortHash(GuidHasher.hash(newestGuid)), shortHash(previousCursor),
                        indexer.getPollInterval(), fails, backoffMultiplier(fails),
                        (indexer.getPollInterval() == null ? 600 : indexer.getPollInterval()) * backoffMultiplier(fails));
                notifySafely("⚠️ 索引器[" + StringUtils.escapeHtml(indexer.getName()) + "]拉取窗口覆盖不全，可能有种子被漏拉。"
                        + "先看该索引器的失败次数（不为 0 时退避已把实际间隔放大数倍），再看日志里同一时刻的诊断行"
                        + "判断是单页容量不足还是索引器 guid 不稳定");
            } else if (hit * 5 >= fetched.size() * 4) {
                // 命中位置就是上轮以来的新增条数：逼近单页容量说明再快一点就要漏了，提前示警但不打扰用户
                log.warn("索引器[{}]拉取窗口余量不足：上轮以来新增 {} 条，单页共 {} 条，{}",
                        indexer.getName(), hit, fetched.size(), describeWindow(fetched));
            } else {
                log.debug("索引器[{}]拉取窗口正常：上轮以来新增 {} 条 / 单页 {} 条",
                        indexer.getName(), hit, fetched.size());
            }
        }
        indexer.setLastSeenGuidHash(GuidHasher.hash(newestGuid));
    }

    /** 上轮游标在本轮结果中的下标（即上轮以来的新增条数），未命中返回 -1 */
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
     * 概括本轮返回列表的时间窗口：pubDate 跨度 + 是否降序。
     * <p>
     * 跨度是判断漏拉告警是否属实的核心判据——它直接说明"这一页覆盖了多长时间"，
     * 跨度远小于轮询周期就是单页容量跟不上发布速度，与游标逻辑无关。
     * 降序检查则守着 {@code fetched.get(0)} 是最新条目这个前提：聚合型索引器合并多站点结果时
     * 未必严格降序，那样游标会记在一条偏老的种子上，下一轮它早被挤出首页，恒定误报。
     * </p>
     */
    private static String describeWindow(List<TorrentInfo> fetched) {
        Instant newest = null;
        Instant oldest = null;
        Instant previous = null;
        boolean descending = true;
        int unparsable = 0;
        for (TorrentInfo info : fetched) {
            Instant at = parsePubDate(info.getPubDate());
            if (at == null) {
                unparsable++;
                continue;
            }
            if (newest == null || at.isAfter(newest)) {
                newest = at;
            }
            if (oldest == null || at.isBefore(oldest)) {
                oldest = at;
            }
            if (previous != null && at.isAfter(previous)) {
                descending = false;
            }
            previous = at;
        }
        if (newest == null) {
            return "pubDate 全部缺失或无法解析（" + unparsable + " 条），无法判断窗口跨度";
        }
        return "pubDate 跨度 " + Duration.between(oldest, newest).toSeconds() + " 秒（" + oldest + " ~ " + newest + "）"
                + (descending ? "，降序正常" : "，⚠️非降序，首条并非最新，游标可能记错位置")
                + (unparsable > 0 ? "，另有 " + unparsable + " 条 pubDate 不可解析" : "");
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
