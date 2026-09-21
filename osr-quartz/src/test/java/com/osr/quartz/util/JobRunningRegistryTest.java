package com.osr.quartz.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 执行中登记簿。
 *
 * 守的是「禁止并发的任务不会被重复触发」：手动执行绕开了 Quartz，实体上的 concurrent
 * 字段对那条路径不起作用，这个登记簿是唯一的闸门。它一旦退化成「先查再加」两步，
 * 并发点击就会同时通过——那种回归不会报错，只会让同一个复制任务跑两遍。
 */
class JobRunningRegistryTest
{
    @Test
    @DisplayName("同一个任务只有第一次登记成功，结束后才能再次登记")
    void tryMarkRunningIsExclusive()
    {
        JobRunningRegistry registry = new JobRunningRegistry();

        assertTrue(registry.tryMarkRunning(100L));
        assertFalse(registry.tryMarkRunning(100L), "上一轮未结束时不应再次受理");
        assertTrue(registry.isRunning(100L));

        registry.markFinished(100L);
        assertFalse(registry.isRunning(100L));
        assertTrue(registry.tryMarkRunning(100L), "结束之后应能再次执行");
    }

    @Test
    @DisplayName("不同任务互不影响")
    void differentJobsAreIndependent()
    {
        JobRunningRegistry registry = new JobRunningRegistry();

        assertTrue(registry.tryMarkRunning(100L));
        assertTrue(registry.tryMarkRunning(101L), "另一个任务不该被别人的执行挡住");
        assertTrue(registry.isRunning(100L));
        assertTrue(registry.isRunning(101L));
    }

    @Test
    @DisplayName("并发触发同一个任务时，只有一个线程拿得到执行权")
    void onlyOneThreadWinsUnderConcurrency() throws Exception
    {
        JobRunningRegistry registry = new JobRunningRegistry();
        int threads = 32;
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger winners = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(threads))
        {
            for (int i = 0; i < threads; i++)
            {
                pool.submit(() -> {
                    try
                    {
                        startGate.await();
                        if (registry.tryMarkRunning(100L))
                        {
                            winners.incrementAndGet();
                        }
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                    }
                    finally
                    {
                        done.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS), "并发登记未在预期时间内完成");
        }

        assertTrue(winners.get() == 1, "期望只有 1 个线程拿到执行权，实际 " + winners.get());
    }

    @Test
    @DisplayName("jobId 为 null 时不登记，也不抛异常")
    void nullJobIdIsIgnored()
    {
        JobRunningRegistry registry = new JobRunningRegistry();

        assertFalse(registry.tryMarkRunning(null));
        assertFalse(registry.isRunning(null));
        registry.markRunning(null);
        registry.markFinished(null);
    }
}
