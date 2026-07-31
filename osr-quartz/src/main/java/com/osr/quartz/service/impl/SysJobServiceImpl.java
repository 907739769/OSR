package com.osr.quartz.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.osr.common.constant.Constants;
import com.osr.common.constant.ScheduleConstants;
import com.osr.common.core.text.Convert;
import com.osr.common.exception.job.TaskException;
import com.osr.common.utils.ExceptionUtil;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.quartz.domain.SysJob;
import com.osr.quartz.domain.SysJobLog;
import com.osr.quartz.mapper.SysJobMapper;
import com.osr.quartz.service.ISysJobLogService;
import com.osr.quartz.service.ISysJobService;
import com.osr.quartz.util.CronUtils;
import com.osr.quartz.util.JobInvokeUtil;
import com.osr.quartz.util.ScheduleUtils;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private Scheduler scheduler;

    @Autowired
    private SysJobMapper jobMapper;

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
    @Transactional(rollbackFor = Exception.class)
    public boolean run(SysJob job) throws SchedulerException
    {
        Long jobId = job.getJobId();
        SysJob tmpObj = selectJobById(job.getJobId());
        if (tmpObj == null)
        {
            log.warn("任务不存在，jobId: {}", jobId);
            return false;
        }
        JobKey jobKey = ScheduleUtils.getJobKey(jobId, tmpObj.getJobGroup());

        // 如果任务不在 Scheduler 中，先重新注册
        if (!scheduler.checkExists(jobKey))
        {
            log.warn("任务不在 Scheduler 中，尝试重新注册: jobId={}, jobName={}, jobGroup={}",
                    jobId, tmpObj.getJobName(), tmpObj.getJobGroup());
            try
            {
                ScheduleUtils.createScheduleJob(scheduler, tmpObj);
            }
            catch (TaskException e)
            {
                log.error("重新注册定时任务失败，jobId: {}", jobId, e);
                return false;
            }
        }
        // 直接执行任务（绕过Quartz trigger数据map合并问题）
        executeJobDirectly(tmpObj);
        log.info("定时任务已触发执行: jobId={}, jobName={}", jobId, tmpObj.getJobName());
        return true;
    }

    /**
     * 直接执行任务（绕过Quartz trigger数据map问题）
     */
    private void executeJobDirectly(SysJob sysJob)
    {
        Date startTime = new Date();
        try
        {
            JobInvokeUtil.invokeMethod(sysJob);
            long runMs = System.currentTimeMillis() - startTime.getTime();
            saveJobLog(sysJob, Constants.SUCCESS, runMs, null);
        }
        catch (Exception e)
        {
            long runMs = System.currentTimeMillis() - startTime.getTime();
            saveJobLog(sysJob, Constants.FAIL, runMs, ExceptionUtil.getExceptionMessage(e));
            log.error("定时任务执行失败，jobId: {}", sysJob.getJobId(), e);
            throw new RuntimeException("定时任务执行失败", e);
        }
    }

    /**
     * 保存任务执行日志
     */
    private void saveJobLog(SysJob sysJob, String status, long runMs, String exceptionInfo)
    {
        try
        {
            ISysJobLogService jobLogService = SpringUtils.getBean(ISysJobLogService.class);
            SysJobLog jobLog = new SysJobLog();
            jobLog.setJobName(sysJob.getJobName());
            jobLog.setJobGroup(sysJob.getJobGroup());
            jobLog.setInvokeTarget(sysJob.getInvokeTarget());
            jobLog.setStartTime(startTime());
            jobLog.setEndTime(new Date());
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
            log.error("保存任务日志失败", e);
        }
    }

    private Date startTime()
    {
        return new Date();
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