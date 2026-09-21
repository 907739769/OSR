package com.osr.quartz.domain;

import java.io.Serializable;
import java.util.Date;
import jakarta.validation.constraints.*;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.constant.ScheduleConstants;
import com.osr.common.core.domain.BaseEntity;
import com.osr.common.utils.StringUtils;
import com.osr.quartz.util.CronUtils;

/**
 * 定时任务调度表 sys_job
 * 
 * @author osr
 */
@TableName("sys_job")
public class SysJob extends BaseEntity implements Serializable
{
    private static final long serialVersionUID = 1L;

    /** 任务ID */
    @TableId(value = "job_id", type = IdType.AUTO)
    private Long jobId;

    /** 任务名称 */
    private String jobName;

    /** 任务组名 */
    private String jobGroup;

    /** 调用目标字符串 */
    private String invokeTarget;

    /** cron执行表达式 */
    private String cronExpression;

    /** cron计划策略 */
    private String misfirePolicy = ScheduleConstants.MISFIRE_DEFAULT;

    /** 是否并发执行（0允许 1禁止） */
    private String concurrent;

    /** 任务状态（0正常 1暂停） */
    private String status;

    /** 上次执行时间（列表查询时由 sys_job_log 回查，非本表字段） */
    @TableField(exist = false)
    private Date lastRunTime;

    /** 上次执行结果（0成功 1失败，同上，非本表字段） */
    @TableField(exist = false)
    private String lastRunStatus;

    /** 是否正在手动执行中（内存态，非本表字段） */
    @TableField(exist = false)
    private Boolean running;

    public Long getJobId()
    {
        return jobId;
    }

    public void setJobId(Long jobId)
    {
        this.jobId = jobId;
    }

    @NotBlank(message = "任务名称不能为空")
    @Size(min = 0, max = 64, message = "任务名称不能超过64个字符")
    public String getJobName()
    {
        return jobName;
    }

    public void setJobName(String jobName)
    {
        this.jobName = jobName;
    }

    public String getJobGroup()
    {
        return jobGroup;
    }

    public void setJobGroup(String jobGroup)
    {
        this.jobGroup = jobGroup;
    }

    @NotBlank(message = "调用目标字符串不能为空")
    @Size(min = 0, max = 1000, message = "调用目标字符串长度不能超过500个字符")
    public String getInvokeTarget()
    {
        return invokeTarget;
    }

    public void setInvokeTarget(String invokeTarget)
    {
        this.invokeTarget = invokeTarget;
    }

    @NotBlank(message = "Cron执行表达式不能为空")
    @Size(min = 0, max = 255, message = "Cron执行表达式不能超过255个字符")
    public String getCronExpression()
    {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression)
    {
        this.cronExpression = cronExpression;
    }

    /**
     * 下次执行时间。没有对应的库表字段，靠 cron 现算，序列化时一并发给前端。
     *
     * 这里必须吞掉非法表达式的异常：getNextExecution 解析失败会抛 IllegalArgumentException，
     * 而本方法在列表接口序列化每一行时都会被调用——库里只要有一条被手工改坏的 cron，
     * 整个定时任务列表就会 500，而不是那一行显示不出下次时间。
     */
    public Date getNextValidTime()
    {
        if (StringUtils.isEmpty(cronExpression))
        {
            return null;
        }
        try
        {
            return CronUtils.getNextExecution(cronExpression);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    public Date getLastRunTime()
    {
        return lastRunTime;
    }

    public void setLastRunTime(Date lastRunTime)
    {
        this.lastRunTime = lastRunTime;
    }

    public String getLastRunStatus()
    {
        return lastRunStatus;
    }

    public void setLastRunStatus(String lastRunStatus)
    {
        this.lastRunStatus = lastRunStatus;
    }

    public Boolean getRunning()
    {
        return running;
    }

    public void setRunning(Boolean running)
    {
        this.running = running;
    }

    public String getMisfirePolicy()
    {
        return misfirePolicy;
    }

    public void setMisfirePolicy(String misfirePolicy)
    {
        this.misfirePolicy = misfirePolicy;
    }

    public String getConcurrent()
    {
        return concurrent;
    }

    public void setConcurrent(String concurrent)
    {
        this.concurrent = concurrent;
    }

    public String getStatus()
    {
        return status;
    }

    public void setStatus(String status)
    {
        this.status = status;
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this,ToStringStyle.MULTI_LINE_STYLE)
            .append("jobId", getJobId())
            .append("jobName", getJobName())
            .append("jobGroup", getJobGroup())
            .append("cronExpression", getCronExpression())
            .append("nextValidTime", getNextValidTime())
            .append("misfirePolicy", getMisfirePolicy())
            .append("concurrent", getConcurrent())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}