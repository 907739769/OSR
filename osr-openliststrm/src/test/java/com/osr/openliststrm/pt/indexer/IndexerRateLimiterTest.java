package com.osr.openliststrm.pt.indexer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流器是整个 PT 模块唯一的防封收口，行为必须可验证。
 */
class IndexerRateLimiterTest {

    /**
     * 在 {@link IndexerRateLimiter.IndexerCall} 里等闩：该函数式接口只声明 throws IOException，
     * lambda 内不能直接抛 InterruptedException，占位线程也不关心中断的后续处理。
     */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void 同一索引器连续两次请求_至少间隔minInterval() throws Exception {
        IndexerRateLimiter limiter = new IndexerRateLimiter(200L, 30_000L, 4);

        long start = System.nanoTime();
        limiter.execute(1, () -> "a");
        limiter.execute(1, () -> "b");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // 留 10% 容差：Thread.sleep 在 Windows 上可能提前 1~2ms 唤醒，断言严格等于 200ms 会偶发失败。
        // 本用例要证明的是"确实等待了一个间隔"，不是计时器精度。
        assertTrue(elapsedMillis >= 180, "两次请求应至少间隔约 200ms，实际 " + elapsedMillis + "ms");
    }

    @Test
    void 不同索引器互不影响_不会因为别的站点在冷却而被拖慢() throws Exception {
        IndexerRateLimiter limiter = new IndexerRateLimiter(500L, 30_000L, 4);

        limiter.execute(1, () -> "a");
        long start = System.nanoTime();
        limiter.execute(2, () -> "b");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 200, "不同索引器不该共享间隔，实际等了 " + elapsedMillis + "ms");
    }

    @Test
    void 同一索引器的并发请求被串行化() throws Exception {
        IndexerRateLimiter limiter = new IndexerRateLimiter(0L, 30_000L, 8);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        try {
                            limiter.execute(1, () -> {
                                int now = concurrent.incrementAndGet();
                                peak.accumulateAndGet(now, Math::max);
                                try {
                                    Thread.sleep(20);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                concurrent.decrementAndGet();
                                return null;
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        assertEquals(1, peak.get(), "同一索引器同时只该有一个在途请求");
    }

    @Test
    void 全局并发上限对所有索引器一起生效() throws Exception {
        IndexerRateLimiter limiter = new IndexerRateLimiter(0L, 30_000L, 2);
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = java.util.stream.IntStream.range(0, 10)
                    .mapToObj(i -> CompletableFuture.runAsync(() -> {
                        try {
                            // 每个请求打不同的索引器，绕开 per-indexer 串行化，只剩全局闸门约束
                            limiter.execute(i, () -> {
                                int now = concurrent.incrementAndGet();
                                peak.accumulateAndGet(now, Math::max);
                                try {
                                    Thread.sleep(30);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                concurrent.decrementAndGet();
                                return null;
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }, executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        assertTrue(peak.get() <= 2, "全局并发不该超过 2，实际峰值 " + peak.get());
    }

    @Test
    void penalize后超过maxWait的请求快速失败而不是挂起() {
        IndexerRateLimiter limiter = new IndexerRateLimiter(0L, 1000L, 4);
        limiter.penalize(1, 300);

        long start = System.nanoTime();
        assertThrows(IOException.class, () -> limiter.execute(1, () -> "never"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 500, "冷却期内应立即失败而非挂起，实际耗时 " + elapsedMillis + "ms");
    }

    @Test
    void penalize取较大值_短冷却不会覆盖已生效的长冷却() {
        IndexerRateLimiter limiter = new IndexerRateLimiter(0L, 30_000L, 4);

        limiter.penalize(1, 300);
        limiter.penalize(1, 5);

        assertTrue(limiter.remainingCooldownMillis(1) > 250_000,
                "后到的短冷却不该缩短已生效的长冷却");
    }

    @Test
    void 请求结束后的最小间隔不会覆盖冷却惩罚() throws Exception {
        IndexerRateLimiter limiter = new IndexerRateLimiter(100L, 30_000L, 4);

        // 在动作内部施加长冷却，模拟"这次请求收到了 429"
        limiter.execute(1, () -> {
            limiter.penalize(1, 300);
            return "429";
        });

        assertTrue(limiter.remainingCooldownMillis(1) > 250_000,
                "execute 收尾写入的最小间隔不该把冷却缩回 100ms");
    }

    @Test
    void 动作抛出的异常原样传播_且照常推进间隔() {
        IndexerRateLimiter limiter = new IndexerRateLimiter(200L, 30_000L, 4);

        assertThrows(IndexerHttpException.class,
                () -> limiter.execute(1, () -> {
                    throw new IndexerHttpException(500, null);
                }));

        // 失败请求同样消耗了对方配额，间隔照常生效
        assertTrue(limiter.remainingCooldownMillis(1) > 0);
    }

    // ---------- 所有等待都必须有上限，否则慢请求会把后来者拖死 ----------

    @Test
    void 前一个请求占住站点串行许可_后来者等满maxWait即放弃_不无限等() throws Exception {
        // 回归用例：串行许可原本是无限等待的 acquire()，一次 60 秒读超时的轮询会让
        // 用户此刻手动搜同一索引器死等 60 秒，正好撞上前端超时
        IndexerRateLimiter limiter = new IndexerRateLimiter(0L, 300L, 8);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try {
                limiter.execute(1, () -> {
                    occupied.countDown();
                    awaitQuietly(release);
                    return "slow";
                });
            } catch (Exception ignored) {
                // 占位线程的结果与本用例无关
            }
        });
        holder.start();
        assertTrue(occupied.await(2, TimeUnit.SECONDS), "占位线程未能进入临界区");

        try {
            long start = System.nanoTime();
            IOException e = assertThrows(IOException.class, () -> limiter.execute(1, () -> "blocked"));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMillis < 1500, "应在 maxWait(300ms) 后放弃，实际等了 " + elapsedMillis + "ms");
            assertTrue(e.getMessage().contains("串行许可"), "超时消息应指明卡在哪一段：" + e.getMessage());
        } finally {
            release.countDown();
            holder.join(2000);
        }
    }

    @Test
    void 全局名额被别的索引器占满_后来者同样等满maxWait即放弃() throws Exception {
        IndexerRateLimiter limiter = new IndexerRateLimiter(0L, 300L, 1);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try {
                limiter.execute(1, () -> {
                    occupied.countDown();
                    awaitQuietly(release);
                    return "slow";
                });
            } catch (Exception ignored) {
                // 同上
            }
        });
        holder.start();
        assertTrue(occupied.await(2, TimeUnit.SECONDS), "占位线程未能进入临界区");

        try {
            // 打的是另一个索引器：站点串行许可是空的，卡住的只可能是全局名额
            long start = System.nanoTime();
            IOException e = assertThrows(IOException.class, () -> limiter.execute(2, () -> "blocked"));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMillis < 1500, "应在 maxWait(300ms) 后放弃，实际等了 " + elapsedMillis + "ms");
            assertTrue(e.getMessage().contains("全局并发许可"), "超时消息应指明卡在哪一段：" + e.getMessage());
        } finally {
            release.countDown();
            holder.join(2000);
        }
    }

    @Test
    void 抢许可超时后_不会误放行也不会泄漏许可() throws Exception {
        IndexerRateLimiter limiter = new IndexerRateLimiter(0L, 100L, 1);
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try {
                limiter.execute(1, () -> {
                    occupied.countDown();
                    awaitQuietly(release);
                    return "slow";
                });
            } catch (Exception ignored) {
                // 同上
            }
        });
        holder.start();
        assertTrue(occupied.await(2, TimeUnit.SECONDS));

        AtomicInteger executed = new AtomicInteger();
        assertThrows(IOException.class, () -> limiter.execute(1, executed::incrementAndGet));
        assertEquals(0, executed.get(), "抢不到许可时绝不能执行动作");

        release.countDown();
        holder.join(2000);

        // 占位线程退出后许可应已归还，后续请求能正常通过
        assertEquals(1, (int) limiter.execute(1, () -> 1));
    }

    @Test
    void 索引器id为null_不抛异常_退化为共用一个槽位() throws Exception {
        IndexerRateLimiter limiter = new IndexerRateLimiter(0L, 30_000L, 4);
        AtomicLong calls = new AtomicLong();

        limiter.execute(null, calls::incrementAndGet);

        assertEquals(1L, calls.get());
    }
}
