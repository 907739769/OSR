package com.osr.quartz.util;

import com.osr.common.utils.ThreadTraceIdUtil;
import org.quartz.JobExecutionContext;
import com.osr.quartz.domain.SysJob;
import org.slf4j.MDC;

/**
 * 定时任务处理（允许并发执行）
 * 
 * @author osr
 *
 */
public class QuartzJobExecution extends AbstractQuartzJob
{
    @Override
    protected void doExecute(JobExecutionContext context, SysJob sysJob) throws Exception
    {
        try {
            ThreadTraceIdUtil.initTraceId();
            JobInvokeUtil.invokeMethod(sysJob);
        } finally {
            MDC.clear();
        }
    }
}
