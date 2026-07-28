package com.ruoyi.common.utils;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 线程相关工具类.
 * 
 * @author ruoyi
 */
public class Threads
{
    private static final Logger logger = LoggerFactory.getLogger(Threads.class);

    /**
     * sleep等待,单位为毫秒
     */
    public static void sleep(long milliseconds)
    {
        try
        {
            Thread.sleep(milliseconds);
        }
        catch (InterruptedException e)
        {
            return;
        }
    }

    /**
     * 停止线程池
     * 先使用 shutdown, 停止接收新任务并尝试完成所有已存在任务.
     * 如果超时，则调用 shutdownNow, 取消在 workQueue 中 Pending 的任务，并中断所有阻塞函数.
     * 如果仍人超時，則強制退出.
     * 另对在 shutdown 时线程本身被调用中断做了处理.
     */
    public static void shutdownAndAwaitTermination(ExecutorService pool)
    {
        if (pool != null && !pool.isShutdown())
        {
            pool.shutdown();
            try
            {
                if (!pool.awaitTermination(120, TimeUnit.SECONDS))
                {
                    try {
                        pool.shutdownNow();
                        if (!pool.awaitTermination(120, TimeUnit.SECONDS))
                        {
                            logger.info("Pool did not terminate");
                        }
                    } catch (UnsupportedOperationException e) {
                        // Virtual thread executor doesn't support shutdownNow, just shutdown is enough
                        logger.debug("shutdownNow not supported (virtual thread executor), shutdown sufficient");
                    }
                }
            }
            catch (InterruptedException ie)
            {
                try {
                    pool.shutdownNow();
                } catch (UnsupportedOperationException e) {
                    logger.debug("shutdownNow not supported (virtual thread executor)");
                }
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 打印线程异常信息
     */
    public static void printException(Runnable r, Throwable t)
    {
        if (t == null && r instanceof Future<?>)
        {
            try
            {
                Future<?> future = (Future<?>) r;
                if (future.isDone())
                {
                    future.get();
                }
            }
            catch (CancellationException ce)
            {
                t = ce;
            }
            catch (ExecutionException ee)
            {
                t = ee.getCause();
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
            }
        }
        if (t != null)
        {
            logger.error(t.getMessage(), t);
        }
    }

    /**
     * 使用结构化并发执行多个子任务（Java 25+）
     * 示例：
     * <pre>
     * try (var scope = StructuredTaskScope.open()) {
     *     scope.fork(() -> fetchUserData());
     *     scope.fork(() -> fetchOrderData());
     *     scope.join();
     *     scope.throwIfFailed();
     * } catch (Exception e) {
     *     // 处理异常
     * }
     * </pre>
     *
     * @return StructuredTaskScope 实例（AutoCloseable）
     */
    public static StructuredTaskScope<Object, Void> openStructuredScope()
    {
        return StructuredTaskScope.open();
    }

    /**
     * 使用结构化并发执行可返回结果的子任务（Java 25+）
     *
     * @param <T> 子任务返回类型
     * @param task 子任务
     * @return 子任务执行结果
     */
    public static <T> T executeWithStructuredConcurrency(Callable<T> task) throws Exception
    {
        try (var scope = StructuredTaskScope.open()) {
            StructuredTaskScope.Subtask<T> subtask = scope.fork(task);
            scope.join();
            return subtask.get();
        }
    }

    // 统一包装 Runnable 任务
    public static Runnable wrap(Runnable task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                String childTraceId = ThreadTraceIdUtil.createChildTraceId();
                MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, childTraceId);
                task.run();
            } finally {
                MDC.clear();
            }
        };
    }

    /**
     * 包装 Supplier 任务，传递 traceId 到目标线程（用于 CompletableFuture.supplyAsync）。
     */
    public static <T> Supplier<T> wrapSupplier(Supplier<T> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                String childTraceId = ThreadTraceIdUtil.createChildTraceId();
                MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, childTraceId);
                return task.get();
            } finally {
                MDC.clear();
            }
        };
    }

    /**
     * 包装 Callable 任务，传递 traceId 到目标线程。
     */
    public static <T> Callable<T> wrapCallable(Callable<T> task) {
        Map<String, String> context = MDC.getCopyOfContextMap();
        return () -> {
            try {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                String childTraceId = ThreadTraceIdUtil.createChildTraceId();
                MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, childTraceId);
                return task.call();
            } finally {
                MDC.clear();
            }
        };
    }

}
