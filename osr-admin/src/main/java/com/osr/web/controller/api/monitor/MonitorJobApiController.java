package com.osr.web.controller.api.monitor;

import java.util.List;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.osr.common.constant.Constants;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.core.page.PageDomain;
import com.osr.common.core.page.TableSupport;
import com.osr.common.exception.job.TaskException;
import com.osr.common.utils.StringUtils;
import com.osr.quartz.domain.SysJob;
import com.osr.quartz.service.ISysJobService;
import com.osr.quartz.util.CronUtils;
import com.osr.quartz.util.ScheduleUtils;

/**
 * 定时任务监控REST API控制器
 *
 * @author osr
 */
@RestController
@RequestMapping("/api/monitor/job")
public class MonitorJobApiController extends BaseController
{
    @Autowired
    private ISysJobService jobService;

    /**
     * 查询定时任务分页列表
     */
    @GetMapping("/list")
    public Result<PageResult<SysJob>> list(SysJob job)
    {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        Page<SysJob> page = new Page<>(pageDomain.getPageNum(), pageDomain.getPageSize());
        List<SysJob> list = jobService.selectJobListPage(page, job);
        return Result.success(PageResult.of(list, page.getTotal(), (int) page.getCurrent(), (int) page.getSize()));
    }

    /**
     * 根据任务ID查询定时任务信息
     */
    @GetMapping("/{jobId}")
    public Result<SysJob> getInfo(@PathVariable("jobId") Long jobId)
    {
        SysJob job = jobService.selectJobById(jobId);
        return Result.success(job);
    }

    /**
     * 新增定时任务
     */
    @PostMapping
    public Result<Integer> add(@Validated @RequestBody SysJob job) throws SchedulerException, TaskException
    {
        if (!CronUtils.isValid(job.getCronExpression()))
        {
            return Result.error("新增任务'" + job.getJobName() + "'失败，Cron表达式不正确");
        }
        else if (StringUtils.containsIgnoreCase(job.getInvokeTarget(), Constants.LOOKUP_RMI))
        {
            return Result.error("新增任务'" + job.getJobName() + "'失败，目标字符串不允许'rmi'调用");
        }
        else if (StringUtils.containsAnyIgnoreCase(job.getInvokeTarget(), new String[] { Constants.LOOKUP_LDAP, Constants.LOOKUP_LDAPS }))
        {
            return Result.error("新增任务'" + job.getJobName() + "'失败，目标字符串不允许'ldap(s)'调用");
        }
        else if (StringUtils.containsAnyIgnoreCase(job.getInvokeTarget(), new String[] { Constants.HTTP, Constants.HTTPS }))
        {
            return Result.error("新增任务'" + job.getJobName() + "'失败，目标字符串不允许'http(s)'调用");
        }
        else if (StringUtils.containsAnyIgnoreCase(job.getInvokeTarget(), Constants.JOB_ERROR_STR))
        {
            return Result.error("新增任务'" + job.getJobName() + "'失败，目标字符串存在违规");
        }
        else if (!ScheduleUtils.whiteList(job.getInvokeTarget()))
        {
            return Result.error("新增任务'" + job.getJobName() + "'失败，目标字符串不在白名单内");
        }
        job.setCreateBy(getLoginName());
        int rows = jobService.insertJob(job);
        return Result.success(rows);
    }

    /**
     * 修改定时任务
     */
    @PutMapping
    public Result<Integer> edit(@Validated @RequestBody SysJob job) throws SchedulerException, TaskException
    {
        if (!CronUtils.isValid(job.getCronExpression()))
        {
            return Result.error("修改任务'" + job.getJobName() + "'失败，Cron表达式不正确");
        }
        else if (StringUtils.containsIgnoreCase(job.getInvokeTarget(), Constants.LOOKUP_RMI))
        {
            return Result.error("修改任务'" + job.getJobName() + "'失败，目标字符串不允许'rmi'调用");
        }
        else if (StringUtils.containsAnyIgnoreCase(job.getInvokeTarget(), new String[] { Constants.LOOKUP_LDAP, Constants.LOOKUP_LDAPS }))
        {
            return Result.error("修改任务'" + job.getJobName() + "'失败，目标字符串不允许'ldap'调用");
        }
        else if (StringUtils.containsAnyIgnoreCase(job.getInvokeTarget(), new String[] { Constants.HTTP, Constants.HTTPS }))
        {
            return Result.error("修改任务'" + job.getJobName() + "'失败，目标字符串不允许'http(s)'调用");
        }
        else if (StringUtils.containsAnyIgnoreCase(job.getInvokeTarget(), Constants.JOB_ERROR_STR))
        {
            return Result.error("修改任务'" + job.getJobName() + "'失败，目标字符串存在违规");
        }
        else if (!ScheduleUtils.whiteList(job.getInvokeTarget()))
        {
            return Result.error("修改任务'" + job.getJobName() + "'失败，目标字符串不在白名单内");
        }
        int rows = jobService.updateJob(job);
        return Result.success(rows);
    }

    /**
     * 删除定时任务（单个）
     */
    @DeleteMapping("/{jobId}")
    public Result<Integer> remove(@PathVariable("jobId") Long jobId) throws SchedulerException
    {
        jobService.deleteJobByIds(jobId.toString());
        return Result.success(1);
    }

    /**
     * 删除定时任务（批量）
     */
    @DeleteMapping
    public Result<Integer> removeBatch(@RequestBody String jobIds) throws SchedulerException
    {
        jobService.deleteJobByIds(jobIds);
        return Result.success(1);
    }

    /**
     * 修改定时任务状态
     */
    @PutMapping("/changeStatus/{jobId}")
    public Result<Integer> changeStatus(@PathVariable("jobId") Long jobId, @RequestBody SysJob job) throws SchedulerException
    {
        SysJob newJob = jobService.selectJobById(jobId);
        newJob.setStatus(job.getStatus());
        int rows = jobService.changeStatus(newJob);
        return Result.success(rows);
    }

    /**
     * 立即执行一次定时任务
     */
    @PostMapping("/run/{jobId}")
    public Result<Boolean> run(@PathVariable("jobId") Long jobId) throws SchedulerException
    {
        SysJob job = jobService.selectJobById(jobId);
        boolean result = jobService.run(job);
        return result ? Result.success(true) : Result.error("任务不存在或已过期！");
    }
}
