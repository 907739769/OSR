package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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

    /**
     * 源文件大小（字节）。存量记录为 null
     */
    @TableField("file_size")
    private Long fileSize;

    /**
     * 失败原因。只在失败 / 未知状态下有值，写入处理中或成功状态时必须一并清空。
     * <p>
     * 刻意用 {@code FieldStrategy.ALWAYS}：默认的 NOT_NULL 策略下实体字段为 null 会被跳过，
     * 一条曾经失败、后来重跑成功的记录经 updateById 写成成功时，旧原因会原样留在库里，
     * 页面上出现「成功，原因：OpenList 复制任务失败」。ALWAYS 让状态与原因每次一起写，
     * 代价是任何按实体更新本表的地方都要带上当前原因（没带就是清空），见各 status 写入点。
     */
    @TableField(value = "fail_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String failReason;
}
