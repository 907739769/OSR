package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * <p>
 * 文件同步任务
 * </p>
 *
 * @author Jack
 * @since 2025-07-23
 */
@Getter
@Setter
@TableName("openlist_copy_task")
public class OpenlistCopyTaskPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 自增主键
     */
    @TableId(value = "copy_task_id", type = IdType.AUTO)
    private Integer copyTaskId;

    /**
     * 源目录
     */
    @TableField("copy_task_src")
    private String copyTaskSrc;

    /**
     * 目标目录
     */
    @TableField("copy_task_dst")
    private String copyTaskDst;

    /**
     * 监控目录
     */
    @TableField("monitor_dir")
    private String monitorDir;

    /**
     * 状态0-停用1-启用
     */
    @TableField("copy_task_status")
    private String copyTaskStatus;

    /**
     * 上次同步完成时间（增量同步基准）
     */
    @TableField("last_sync_time")
    private Date lastSyncTime;
}
