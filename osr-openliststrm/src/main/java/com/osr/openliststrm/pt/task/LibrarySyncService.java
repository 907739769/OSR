package com.osr.openliststrm.pt.task;

import com.osr.common.utils.Threads;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.PtLogText;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 媒体库对账的编排逻辑：把「有缺集的订阅」逐条交给
 * {@link SubscriptionService#refresh(Integer)}，与 Emby 核对已入库集数。
 * <p>
 * 抽成独立 Service 是为了脱离定时器单测，与 {@code DownloadTrackService} 同一分工。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class LibrarySyncService {

    private final IPtSubscriptionPlusService subscriptionService;
    private final SubscriptionService subscriptionBiz;

    /**
     * 同时对账几条订阅。
     * <p>
     * <b>这里能用并发，而 {@code AutoSearchService} 刻意不用，两者不矛盾</b>——差别在下游是谁：
     * 补搜打的是索引器，受全局的 {@code IndexerRateLimiter} 约束，多条订阅同时搜只会在限流器上
     * 排队、把等待推过 {@code pt.indexer.max-wait-ms} 从而触发静默跳过；而对账打的是
     * <b>Emby 与 TMDb</b>，前者是局域网内的自建服务，后者已经有 {@code TMDbApiService} 里那个
     * 全局 {@code Semaphore(4)} 兜着，且订阅详情有两级缓存，本来就很少真的发出请求。
     * </p>
     * <p>
     * 默认取 4 而不是更大，卡点不在对端而在<b>数据库连接</b>：{@code refresh} 带
     * {@code @Transactional}，事务里夹着 Emby 的网络往返，所以每条并发对账都会在整个 HTTP
     * 往返期间占住一个连接。Druid 的 {@code maxActive} 是 50，4 条无关痛痒；调到几十就要先想清楚
     * 这件事，那时抢的是所有在线请求的连接。
     * </p>
     */
    private final int concurrency;

    public LibrarySyncService(IPtSubscriptionPlusService subscriptionService,
                              SubscriptionService subscriptionBiz,
                              @Value("${pt.library.sync-concurrency:4}") int concurrency) {
        this.subscriptionService = subscriptionService;
        this.subscriptionBiz = subscriptionBiz;
        this.concurrency = Math.max(1, concurrency);
    }

    /**
     * 对账一轮：只处理仍有缺集的 ACTIVE 订阅，全部已入库的直接跳过（由 SQL 完成）。
     * <p>
     * 单条订阅失败不影响其余订阅——一台 Emby 抽风或某条订阅的 TMDb 记录有问题，不该让
     * 剩下几十条这一轮全部不对账。
     * </p>
     *
     * @return 本轮的对账结果，供调用方记日志
     */
    public SyncOutcome refreshAll() {
        List<PtSubscriptionPlus> active = subscriptionService.listActiveWithMissing();
        if (active.isEmpty()) {
            return SyncOutcome.EMPTY;
        }
        AtomicInteger episodesIn = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        Semaphore permits = new Semaphore(concurrency);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = active.stream()
                    .map(sub -> CompletableFuture.runAsync(
                            Threads.wrap(() -> refreshOne(sub, permits, episodesIn, failed)), executor))
                    .toList();
            // 必须等齐：调用方紧接着要跑 StuckEpisodeSweepService，而那一步依赖
            // 「本轮刚被推进 IN_LIBRARY 的集」已经落库，否则它们会被当成卡死的在途集
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        return new SyncOutcome(active.size(), episodesIn.get(), failed.get());
    }

    private void refreshOne(PtSubscriptionPlus sub, Semaphore permits,
                            AtomicInteger episodesIn, AtomicInteger failed) {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            episodesIn.addAndGet(subscriptionBiz.refresh(sub.getId()));
        } catch (Exception e) {
            failed.incrementAndGet();
            log.warn("对账 {} 失败：{}", PtLogText.subject(sub), e.getMessage());
        } finally {
            permits.release();
        }
    }

    /**
     * 一轮对账的结果。
     *
     * <p><b>扫描数和变化数必须分开报</b>：改造前这里只返回 {@code active.size()}，日志写的是
     * 「本轮对账 103 条订阅」——那是<b>输入</b>而不是<b>结果</b>，103 这个数每轮都一样、
     * 与这一轮有没有干成任何事无关。实测一整天里有 32 集完成了入库，而日志对此只字未提。
     *
     * @param scanned    本轮发起对账的订阅数
     * @param episodesIn 本轮新推进到 IN_LIBRARY 的集数——这才是「这一轮干了什么」
     * @param failed     对账失败的订阅数（各自已经 warn 过，这里只用于汇总时提一句）
     */
    public record SyncOutcome(int scanned, int episodesIn, int failed) {

        static final SyncOutcome EMPTY = new SyncOutcome(0, 0, 0);

        /** 本轮是否发生了值得记一条 INFO 的事 */
        public boolean changed() {
            return episodesIn > 0 || failed > 0;
        }
    }
}
