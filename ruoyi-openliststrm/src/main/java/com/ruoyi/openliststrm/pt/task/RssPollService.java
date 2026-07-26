package com.ruoyi.openliststrm.pt.task;

import com.ruoyi.common.utils.Threads;
import com.ruoyi.openliststrm.helper.TgHelper;
import com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.pt.indexer.TorznabClient;
import com.ruoyi.openliststrm.pt.model.TorrentInfo;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    /** 同时拉取多个到期索引器的最大并发数 */
    private final int maxConcurrency;

    /**
     * 自动停用后的冷却期（小时）：冷却期内不重复探测，避免对已知失效的索引器每轮心跳都打一次站点。
     */
    private final int selfHealCooldownHours;

    private final IPtIndexerPlusService indexerService;
    private final TorznabClient torznabClient;
    private final SubscriptionEngine subscriptionEngine;

    public RssPollService(IPtIndexerPlusService indexerService,
                          TorznabClient torznabClient,
                          SubscriptionEngine subscriptionEngine,
                          @Value("${pt.indexer.self-heal-cooldown-hours:2}") int selfHealCooldownHours,
                          @Value("${pt.indexer.max-concurrency:4}") int maxConcurrency) {
        this.indexerService = indexerService;
        this.torznabClient = torznabClient;
        this.subscriptionEngine = subscriptionEngine;
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
     */
    private void pollOne(PtIndexerPlus indexer, List<TorrentInfo> collector) {
        try {
            List<TorrentInfo> fetched = torznabClient.fetch(indexer);
            collector.addAll(fetched);
            indexer.setLastPollTime(new Date());
            indexer.setLastStatus("OK");
            indexer.setFailCount(0);
            log.info("索引器[{}]拉取到 {} 条种子", indexer.getName(), fetched.size());
        } catch (Exception e) {
            int fails = (indexer.getFailCount() == null ? 0 : indexer.getFailCount()) + 1;
            indexer.setFailCount(fails);
            indexer.setLastStatus(truncate(e.getMessage()));
            log.warn("索引器[{}]拉取失败（第{}次）：{}", indexer.getName(), fails, e.getMessage());
            if (fails >= DISABLE_FAIL_THRESHOLD && ENABLED.equals(indexer.getEnabled())) {
                indexer.setEnabled(DISABLED);
                indexer.setDisabledAt(new Date());
                log.warn("索引器[{}]连续失败 {} 次，已自动停用", indexer.getName(), fails);
                notifySafely("🛑 索引器[" + indexer.getName() + "]已连续失败 " + fails + " 次，已自动停用，"
                        + "冷却 " + selfHealCooldownHours + " 小时后将自动尝试恢复");
            } else if (fails == ALERT_FAIL_THRESHOLD) {
                notifySafely("⚠️ 索引器[" + indexer.getName() + "]已连续失败 " + fails + " 次：" + e.getMessage());
            }
        }
        indexerService.updateById(indexer);
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
                notifySafely("✅ 索引器[" + indexer.getName() + "]自愈探测成功，已自动重新启用");
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

    private boolean isDue(PtIndexerPlus indexer, long now) {
        if (indexer.getLastPollTime() == null) {
            return true;
        }
        int interval = indexer.getPollInterval() == null ? 600 : indexer.getPollInterval();
        return now - indexer.getLastPollTime().getTime() >= interval * 1000L;
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
