package com.osr.quartz.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.osr.common.constant.Constants;
import com.osr.common.constant.ScheduleConstants;
import com.osr.common.core.text.Convert;
import com.osr.common.exception.job.TaskException;
import com.osr.common.utils.ExceptionUtil;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.quartz.domain.JobRunResult;
import com.osr.quartz.domain.SysJob;
import com.osr.quartz.domain.SysJobLog;
import com.osr.quartz.mapper.SysJobMapper;
import com.osr.quartz.service.ISysJobLogService;
import com.osr.quartz.service.ISysJobService;
import com.osr.quartz.util.CronUtils;
import com.osr.quartz.util.JobInvokeUtil;
import com.osr.quartz.util.JobRunningRegistry;
import com.osr.quartz.util.ScheduleUtils;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * 定时任务调度信息 服务层
 * 
 * @author osr
 */
@Service
public class SysJobServiceImpl implements ISysJobService
{
    private static final Logger log = LoggerFactory.getLogger(SysJobServiceImpl.class);

    /** sys_job.concurrent 的「允许并发」取值（0允许 1禁止） */
    private static final String CONCURRENT_ALLOW = "0";

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private SysJobMapper jobMapper;

    /** 虚拟线程调度器，手动执行的任务体跑在它上面而不是 HTTP 请求线程上 */
    @Autowired
    @Qualifier("virtualScheduledExecutor")
    private TaskScheduler taskScheduler;

    /**
     * 执行中登记簿，与 Quartz 定时触发那条路径共用。
     *
     * 手动执行走的是 executeJobDirectly()，它绕开了 Quartz，因此实体上的 concurrent
     * （0允许 1禁止）对这条路径完全不起作用——在这里自己把它实现出来，否则用户连点两次
     * 「执行」就会真的并发跑两遍复制/STRM 生成。列表接口也读它来置灰按钮。
     */
    @Autowired
    private JobRunningRegistry runningRegistry;

    /**
     * 项目启动时，初始化定时器 
     * 主要是防止手动修改数据库导致未同步到定时任务处理（注：不能手动修改数据库ID和任务组名，否则会导致脏数据）
     */
    @EventListener(ApplicationReadyEvent.class)
    public void load() throws SchedulerException, TaskException
    {
        scheduler.clear();
        List<SysJob> jobList = jobMapper.selectJobAll();
        for (SysJob job : jobList)
        {
            ScheduleUtils.createScheduleJob(scheduler, job);
        }
        if (scheduler.isInStandbyMode())
        {
            scheduler.start();
        }
    }

    /**
     * 获取quartz调度器的计划任务列表（分页）
     * 
     * @param page 分页对象
     * @param job 调度信息
     * @return 调度任务集合
     */
    @Override
    public List<SysJob> selectJobListPage(Page<SysJob> page, SysJob job)
    {
        return jobMapper.selectJobListPage(page, job);
    }

    /**
     * 获取quartz调度器的计划任务列表
     * 
     * @param job 调度信息
     * @return 调度任务集合
     */
    @Override
    public List<SysJob> selectJobList(SysJob job)
    {
        return jobMapper.selectJobList(job);
    }

    /**
     * 通过调度任务ID查询调度信息
     * 
     * @param jobId 调度任务ID
     * @return 调度任务对象信息
     */
    @Override
    public SysJob selectJobById(Long jobId)
    {
        return jobMapper.selectJobById(jobId);
    }

    /**
     * 暂停任务
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int pauseJob(SysJob job) throws SchedulerException
    {
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        job.setStatus(ScheduleConstants.Status.PAUSE.getValue());
        int rows = jobMapper.updateJob(job);
        if (rows > 0)
        {
            scheduler.pauseJob(ScheduleUtils.getJobKey(jobId, jobGroup));
        }
        return rows;
    }

    /**
     * 恢复任务
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int resumeJob(SysJob job) throws SchedulerException
    {
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        job.setStatus(ScheduleConstants.Status.NORMAL.getValue());
        int rows = jobMapper.updateJob(job);
        if (rows > 0)
        {
            scheduler.resumeJob(ScheduleUtils.getJobKey(jobId, jobGroup));
        }
        return rows;
    }

    /**
     * 删除任务后，所对应的trigger也将被删除
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteJob(SysJob job) throws SchedulerException
    {
        Long jobId = job.getJobId();
        String jobGroup = job.getJobGroup();
        int rows = jobMapper.deleteJobById(jobId);
        if (rows > 0)
        {
            scheduler.deleteJob(ScheduleUtils.getJobKey(jobId, jobGroup));
        }
        return rows;
    }

    /**
     * 批量删除调度信息
     * 
     * @param ids 需要删除的数据ID
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteJobByIds(String ids) throws SchedulerException
    {
        Long[] jobIds = Convert.toLongArray(ids);
        for (Long jobId : jobIds)
        {
            SysJob job = jobMapper.selectJobById(jobId);
            deleteJob(job);
        }
    }

    /**
     * 任务调度状态修改
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int changeStatus(SysJob job) throws SchedulerException
    {
        int rows = 0;
        String status = job.getStatus();
        if (ScheduleConstants.Status.NORMAL.getValue().equals(status))
        {
            rows = resumeJob(job);
        }
        else if (ScheduleConstants.Status.PAUSE.getValue().equals(status))
        {
            rows = pauseJob(job);
        }
        return rows;
    }

    /**
     * 立即运行任务
     * 
     * @param job 调度信息
     */
    @Override
    public JobRunResult run(SysJob job) throws SchedulerException
    {
        Long jobId = job.getJobId();
        SysJob tmpObj = selectJobById(jobId);
        if (tmpObj == null)
        {
            log.warn("手动执行定时任务失败：任务不存在，jobId={}", jobId);
            return JobRunResult.NOT_FOUND;
        }
        JobKey jobKey = ScheduleUtils.getJobKey(jobId, tmpObj.getJobGroup());

        // 如果任务不在 Scheduler 中，先重新注册
        if (!scheduler.checkExists(jobKey))
        {
            log.warn("任务不在调度器中，尝试重新注册：jobId={}, jobName={}, jobGroup={}",
                    jobId, tmpObj.getJobName(), tmpObj.getJobGroup());
            try
            {
                ScheduleUtils.createScheduleJob(scheduler, tmpObj);
            }
            catch (TaskException e)
            {
                log.error("重新注册定时任务失败，jobId={}, jobName={}, 原因={}",
                        jobId, tmpObj.getJobName(), e.getMessage(), e);
                return JobRunResult.REGISTER_FAILED;
            }
        }

        // 禁止并发的任务（concurrent=1，四个内置任务都是）上一轮没跑完就不再受理
        boolean forbidConcurrent = !CONCURRENT_ALLOW.equals(tmpObj.getConcurrent());
        if (forbidConcurrent && !runningRegistry.tryMarkRunning(jobId))
        {
            log.warn("任务正在执行中，忽略本次手动触发：jobId={}, jobName={}", jobId, tmpObj.getJobName());
            return JobRunResult.ALREADY_RUNNING;
        }

        // 丢到虚拟线程上执行。任务体（如 openListStrmTask.copy()）是整盘遍历，分钟级起步，
        // 留在请求线程上跑必然撞上前端 15s 超时：用户看到红字报错，任务其实还在后台跑，
        // 于是再点一次——这正是上面那把并发锁要挡的场景。Threads.wrap 是为了 traceId 不断链。
        try
        {
            taskScheduler.schedule(Threads.wrap(() -> {
                try
                {
                    executeJobDirectly(tmpObj);
                }
                finally
                {
                    if (forbidConcurrent)
                    {
                        runningRegistry.markFinished(jobId);
                    }
                }
            }), Instant.now());
        }
        catch (RuntimeException e)
        {
            // 提交失败时必须把闸门放开：漏掉的话这个任务会永远停在「执行中」，
            // 按钮一直是灰的，重启之前再也手动执行不了
            if (forbidConcurrent)
            {
                runningRegistry.markFinished(jobId);
            }
            log.error("提交定时任务到后台执行失败：jobId={}, jobName={}, 原因={}",
                    jobId, tmpObj.getJobName(), e.getMessage(), e);
            return JobRunResult.REGISTER_FAILED;
        }

        log.info("定时任务已提交后台执行：jobId={}, jobName={}", jobId, tmpObj.getJobName());
        return JobRunResult.TRIGGERED;
    }

    @Override
    public boolean isRunning(Long jobId)
    {
        return runningRegistry.isRunning(jobId);
    }

    /**
     * 直接执行任务（绕过Quartz trigger数据map问题）。
     *
     * 已经跑在后台线程上，异常不再往外抛：抛出去只会落到调度器的 errorHandler，
     * 而那里是 System.err，本项目 stdout 只有启动 banner，等于没人看得到。
     * 失败信息该去的地方是 sys_job_log 与 sys-error.log，下面两件都做了。
     */
    private void executeJobDirectly(SysJob sysJob)
    {
        Date startTime = new Date();
        try
        {
            JobInvokeUtil.invokeMethod(sysJob);
            saveJobLog(sysJob, Constants.SUCCESS, startTime, null);
        }
        catch (Exception e)
        {
            saveJobLog(sysJob, Constants.FAIL, startTime, ExceptionUtil.getExceptionMessage(e));
            log.error("手动执行定时任务失败：jobId={}, jobName={}, 调用目标={}, 原因={}",
                    sysJob.getJobId(), sysJob.getJobName(), sysJob.getInvokeTarget(), e.getMessage(), e);
        }
    }

    /**
     * 保存任务执行日志。
     *
     * startTime 必须是调用方在**执行前**取的那个时刻。原先这里写的是 startTime()——
     * 一个返回 new Date() 的私有方法，也就是「现在」，于是 start_time 与 end_time 相等，
     * 界面上算出来的耗时恒为 0。
     */
    private void saveJobLog(SysJob sysJob, String status, Date startTime, String exceptionInfo)
    {
        try
        {
            ISysJobLogService jobLogService = SpringUtils.getBean(ISysJobLogService.class);
            Date endTime = new Date();
            long runMs = endTime.getTime() - startTime.getTime();
            SysJobLog jobLog = new SysJobLog();
            jobLog.setJobName(sysJob.getJobName());
            jobLog.setJobGroup(sysJob.getJobGroup());
            jobLog.setInvokeTarget(sysJob.getInvokeTarget());
            jobLog.setStartTime(startTime);
            jobLog.setEndTime(endTime);
            jobLog.setJobMessage(sysJob.getJobName() + " 总共耗时：" + runMs + "毫秒");
            jobLog.setStatus(status);
            if (StringUtils.isNotEmpty(exceptionInfo))
            {
                jobLog.setExceptionInfo(StringUtils.substring(exceptionInfo, 0, 2000));
            }
            jobLogService.addJobLog(jobLog);
        }
        catch (Exception e)
        {
            log.error("保存任务执行日志失败：jobName={}, 原因={}", sysJob.getJobName(), e.getMessage(), e);
        }
    }

    /**
     * 新增任务
     * 
     * @param job 调度信息 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertJob(SysJob job) throws SchedulerException, TaskException
    {
        job.setStatus(ScheduleConstants.Status.PAUSE.getValue());
        int rows = jobMapper.insertJob(job);
        if (rows > 0)
        {
            ScheduleUtils.createScheduleJob(scheduler, job);
        }
        return rows;
    }

    /**
     * 更新任务的时间表达式
     * 
     * @param job 调度信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateJob(SysJob job) throws SchedulerException, TaskException
    {
        SysJob properties = selectJobById(job.getJobId());
        int rows = jobMapper.updateJob(job);
        if (rows > 0)
        {
            updateSchedulerJob(job, properties.getJobGroup());
        }
        return rows;
    }

    /**
     * 更新任务
     * 
     * @param job 任务对象
     * @param jobGroup 任务组名
     */
    public void updateSchedulerJob(SysJob job, String jobGroup) throws SchedulerException, TaskException
    {
        Long jobId = job.getJobId();
        // 判断是否存在
        JobKey jobKey = ScheduleUtils.getJobKey(jobId, jobGroup);
        if (scheduler.checkExists(jobKey))
        {
            // 防止创建时存在数据问题 先移除，然后在执行创建操作
            scheduler.deleteJob(jobKey);
        }
        ScheduleUtils.createScheduleJob(scheduler, job);
    }

    /**
     * 校验cron表达式是否有效
     * 
     * @param cronExpression 表达式
     * @return 结果
     */
    @Override
    public boolean checkCronExpressionIsValid(String cronExpression)
    {
        return CronUtils.isValid(cronExpression);
    }
}