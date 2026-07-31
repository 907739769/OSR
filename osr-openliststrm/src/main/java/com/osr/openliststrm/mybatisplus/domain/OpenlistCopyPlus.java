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
 * 文件同步
 * </p>
 *
 * @author Jack
 * @since 2025-07-21
 */
@Getter
@Setter
@TableName("openlist_copy")
public class OpenlistCopyPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 自增主键
     */
    @TableId(value = "copy_id", type = IdType.AUTO)
    private Integer copyId;

    /**
     * 源目录
     */
    @TableField("copy_src_path")
    private String copySrcPath;

    /**
     * 目标目录
     */
    @TableField("copy_dst_path")
    private String copyDstPath;

    /**
     * 源文件名称
     */
    @TableField("copy_src_file_name")
    private String copySrcFileName;

    /**
     * 目标文件名称
     */
    @TableField("copy_dst_file_name")
    private String copyDstFileName;

    /**
     * openlist的复制任务ID
     */
    @TableField("copy_task_id")
    private String copyTaskId;

    /**
     * 复制状态1-处理中2-失败3-成功4-未知
     */
    @TableField("copy_status")
    private String copyStatus;
}
