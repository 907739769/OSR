package com.osr.framework.manager;

import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import org.springframework.scheduling.TaskScheduler;

import java.time.Instant;

/**
 * 异步任务管理器
 * 
 * @author liuhulu
 */
public class AsyncManager
{
    private final long OPERATE_DELAY_TIME = 10;

    private final TaskScheduler executor = SpringUtils.getBean("virtualScheduledExecutor");

    private AsyncManager(){}

    private static final AsyncManager me = new AsyncManager();

    public static AsyncManager me()
    {
        return me;
    }

    /**
     * 提交异步任务。在此统一做 {@link Threads#wrap} 包装，调用方直接传裸 Runnable 即可 ——
     * 包装发生在提交侧，捕获的是调用线程（通常是 HTTP 请求线程）的 MDC，
     * traceId 因此能从请求一路带进异步任务里。
     */
    public void execute(Runnable task)
    {
        executor.schedule(Threads.wrap(task), Instant.now().plusMillis(OPERATE_DELAY_TIME));
    }

    public void shutdown()
    {
    }
}
