package com.osr.quartz.util;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 「哪些定时任务此刻正在执行」的内存登记簿。
 *
 * 两条执行路径都往这里登记：Quartz 定时触发（AbstractQuartzJob）与页面上的手动执行
 * （SysJobServiceImpl#run）。分开记是不够的——Quartz 的 @DisallowConcurrentExecution
 * 只约束它自己触发的那些，手动执行完全绕开 Quartz，两边各记各的就会出现
 * 「凌晨三点的复制任务正跑着，用户点一下执行，同一个任务并发跑两遍」。
 *
 * 只活在单个进程的内存里：本项目是单实例部署，重启后自然清空（重启时任务本来也断了）。
 *
 * @author osr
 */
@Component
public class JobRunningRegistry
{
    private final Set<Long> runningJobIds = ConcurrentHashMap.newKeySet();

    /**
     * 登记开始执行。
     *
     * @return true 表示登记成功；false 表示该任务已在执行中，调用方应放弃本次执行
     */
    public boolean tryMarkRunning(Long jobId)
    {
        return jobId != null && runningJobIds.add(jobId);
    }

    /** 无条件登记（用于 Quartz 触发的路径：并发与否由 Quartz 自己把关，这里只记录状态） */
    public void markRunning(Long jobId)
    {
        if (jobId != null)
        {
            runningJobIds.add(jobId);
        }
    }

    public void markFinished(Long jobId)
    {
        if (jobId != null)
        {
            runningJobIds.remove(jobId);
        }
    }

    public boolean isRunning(Long jobId)
    {
        return jobId != null && runningJobIds.contains(jobId);
    }
}
