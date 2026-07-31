package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * strm任务配置
 * </p>
 *
 * @author Jack
 * @since 2025-07-23
 */
@Getter
@Setter
@TableName("openlist_strm_task")
public class OpenlistStrmTaskPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 自增主键
     */
    @TableId(value = "strm_task_id", type = IdType.AUTO)
    private Integer strmTaskId;

    /**
     * strm目录
     */
    @TableField("strm_task_path")
    private String strmTaskPath;

    /**
     * 状态0-停用1-启用
     */
    @TableField("strm_task_status")
    private String strmTaskStatus;
}
