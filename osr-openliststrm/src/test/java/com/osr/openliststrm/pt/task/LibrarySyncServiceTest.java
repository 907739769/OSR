package com.osr.openliststrm.pt.task;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 媒体库对账的并发编排。
 * <p>
 * 原实现是逐条串行 {@code refresh}，每条夹着一次 Emby 往返；订阅一多，单轮墙钟会逼近
 * 10 分钟的心跳间隔，而 {@code LibrarySyncTask} 的重叠保护会把下一次心跳整个吞掉——
 * 现象是「对账好像越来越不及时」，日志里却每轮都正常。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LibrarySyncServiceTest {

    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private SubscriptionService subscriptionBiz;

    private LibrarySyncService service(int concurrency) {
        return new LibrarySyncService(subscriptionService, subscriptionBiz, concurrency);
    }

    private static PtSubscriptionPlus sub(int id) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(id);
        return s;
    }

    private void candidates(int count) {
        List<PtSubscriptionPlus> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(sub(i));
        }
        when(subscriptionService.listActiveWithMissing()).thenReturn(list);
    }

    @Test
    void 没有待对账订阅_不发起任何对账() {
        when(subscriptionService.listActiveWithMissing()).thenReturn(List.of());

        assertEquals(0, service(4).refreshAll().scanned());
        verify(subscriptionBiz, never()).refresh(anyInt());
    }

    @Test
    void 每条有缺集的订阅都被对账一次() {
        candidates(10);

        assertEquals(10, service(4).refreshAll().scanned());

        for (int i = 1; i <= 10; i++) {
            verify(subscriptionBiz).refresh(i);
        }
    }

    /**
     * 单条失败不能影响其余——一台 Emby 抽风或某条订阅的 TMDb 记录有问题，
     * 不该让剩下几十条这一轮全部不对账。
     */
    @Test
    void 单条对账抛异常_其余照常完成() {
        candidates(5);
        doThrow(new RuntimeException("emby down")).when(subscriptionBiz).refresh(3);

        assertEquals(5, service(4).refreshAll().scanned());

        verify(subscriptionBiz).refresh(1);
        verify(subscriptionBiz).refresh(2);
        verify(subscriptionBiz).refresh(4);
        verify(subscriptionBiz).refresh(5);
    }

    /**
     * <b>并发度必须真的被限住。</b>
     * <p>
     * 卡点不在 Emby 而在数据库连接：{@code refresh} 带 {@code @Transactional}，事务里夹着
     * Emby 的网络往返，所以每条并发对账都会在整个 HTTP 往返期间占住一个连接。信号量漏掉的话
     * 20 条订阅就是 20 个连接，而且这件事在功能上完全看不出来——直到某次对账撞上一波在线请求，
     * 整站一起等连接。
     * </p>
     */
    @Test
    void 同时在跑的对账数不超过配置的并发度() {
        candidates(20);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        doAnswer(invocation -> {
            int now = inFlight.incrementAndGet();
            peak.updateAndGet(prev -> Math.max(prev, now));
            Thread.sleep(20);
            inFlight.decrementAndGet();
            return 0;
        }).when(subscriptionBiz).refresh(anyInt());

        service(4).refreshAll();

        assertTrue(peak.get() <= 4, "并发度超出上限，实测峰值 " + peak.get());
    }

    /**
     * 订阅之间确实是并行的，不是被信号量退化成了串行。
     * <p>
     * 这条与上一条一起把并发度夹在 (1, 4] 区间里：只钉上限的话，把整段改回串行照样通过。
     * </p>
     */
    @Test
    void 订阅之间确实并行_不是串行() {
        candidates(8);
        // 用 threadId 而不是 getName()：newVirtualThreadPerTaskExecutor 建出来的虚拟线程
        // 默认<无名>，getName() 一律返回空串，按名字去重会把 8 个不同线程数成 1 个
        Set<Long> threads = ConcurrentHashMap.newKeySet();
        doAnswer(invocation -> {
            threads.add(Thread.currentThread().threadId());
            Thread.sleep(30);
            return 0;
        }).when(subscriptionBiz).refresh(anyInt());

        service(4).refreshAll();

        assertTrue(threads.size() > 1, "8 条订阅跑在同一个线程上，说明并发没生效");
    }

    /**
     * refreshAll 必须等所有对账落库<b>才返回</b>。
     * <p>
     * 调用方紧接着要跑 {@code StuckEpisodeSweepService}，而那一步依赖「本轮刚被推进
     * IN_LIBRARY 的集」已经写完；提前返回的话，这些集会在同一轮里被当成卡死的在途集，
     * 发出一批本不该发的 LIBRARY_STUCK 通知。
     * </p>
     */
    @Test
    void 返回时全部对账已完成() {
        candidates(12);
        AtomicInteger done = new AtomicInteger();
        doAnswer(invocation -> {
            Thread.sleep(15);
            done.incrementAndGet();
            return 0;
        }).when(subscriptionBiz).refresh(anyInt());

        service(4).refreshAll();

        assertEquals(12, done.get());
    }

    /** 并发度配成 0 或负数时退化为 1，而不是让 Semaphore 构造出一个永远拿不到许可的死锁 */
    @Test
    void 并发度配置非法_退化为单条串行而不是卡死() {
        candidates(3);

        assertEquals(3, service(0).refreshAll().scanned());

        verify(subscriptionBiz).refresh(1);
        verify(subscriptionBiz).refresh(2);
        verify(subscriptionBiz).refresh(3);
    }
}
