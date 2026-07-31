package com.osr.quartz.util;

import java.util.Date;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.osr.common.constant.Constants;
import com.osr.common.constant.ScheduleConstants;
import com.osr.common.utils.ExceptionUtil;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.bean.BeanUtils;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.quartz.domain.SysJob;
import com.osr.quartz.domain.SysJobLog;
import com.osr.quartz.service.ISysJobLogService;

/**
 * 抽象quartz调用
 *
 * @author osr
 */
public abstract class AbstractQuartzJob implements Job
{
    private static final Logger log = LoggerFactory.getLogger(AbstractQuartzJob.class);

    /**
     * 线程本地变量
     */
    private static ThreadLocal<Date> threadLocal = new ThreadLocal<>();

    @Override
    public void execute(JobExecutionContext context)
    {
        Object jobInstance = context.getMergedJobDataMap().get(ScheduleConstants.TASK_PROPERTIES);
        SysJob sysJob = new SysJob();
        if (jobInstance != null)
        {
            BeanUtils.copyBeanProp(sysJob, jobInstance);
        }
        try
        {
            before(context, sysJob);
            if (isValidSysJob(sysJob))
            {
                doExecute(context, sysJob);
            }
            else
            {
                log.warn("定时任务执行失败：任务配置无效，jobId={}", sysJob.getJobId());
            }
            after(context, sysJob, null);
        }
        catch (Exception e)
        {
            log.error("任务执行异常  - ：", e);
            after(context, sysJob, e);
        }
    }

    /**
     * 校验sysJob是否有效（invokeTarget不为空）
     */
    private boolean isValidSysJob(SysJob sysJob)
    {
        return sysJob != null && StringUtils.isNotEmpty(sysJob.getInvokeTarget());
    }

    /**
     * 执行前
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     */
    protected void before(JobExecutionContext context, SysJob sysJob)
    {
        threadLocal.set(new Date());
    }

    /**
     * 执行后
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     */
    protected void after(JobExecutionContext context, SysJob sysJob, Exception e)
    {
        Date startTime = threadLocal.get();
        threadLocal.remove();

        final SysJobLog sysJobLog = new SysJobLog();
        sysJobLog.setJobName(sysJob.getJobName());
        sysJobLog.setJobGroup(sysJob.getJobGroup());
        sysJobLog.setInvokeTarget(sysJob.getInvokeTarget());
        sysJobLog.setStartTime(startTime);
        sysJobLog.setEndTime(new Date());
        long runMs = sysJobLog.getEndTime().getTime() - sysJobLog.getStartTime().getTime();
        sysJobLog.setJobMessage(sysJobLog.getJobName() + " 总共耗时：" + runMs + "毫秒");
        if (e != null)
        {
            sysJobLog.setStatus(Constants.FAIL);
            String errorMsg = StringUtils.substring(ExceptionUtil.getExceptionMessage(e), 0, 2000);
            sysJobLog.setExceptionInfo(errorMsg);
        }
        else
        {
            sysJobLog.setStatus(Constants.SUCCESS);
        }

        // 写入数据库当中
        SpringUtils.getBean(ISysJobLogService.class).addJobLog(sysJobLog);
    }

    /**
     * 执行方法，由子类重载
     *
     * @param context 工作执行上下文对象
     * @param sysJob 系统计划任务
     * @throws Exception 执行过程中的异常
     */
    protected abstract void doExecute(JobExecutionContext context, SysJob sysJob) throws Exception;
}
