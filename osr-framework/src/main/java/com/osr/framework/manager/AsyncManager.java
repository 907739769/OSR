package com.osr.framework.manager;

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

    public void execute(Runnable task)
    {
        executor.schedule(task, Instant.now().plusMillis(OPERATE_DELAY_TIME));
    }

    public void shutdown()
    {
    }
}
