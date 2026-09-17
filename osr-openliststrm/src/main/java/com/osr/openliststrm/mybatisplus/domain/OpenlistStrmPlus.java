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
 * strm生成
 * </p>
 *
 * @author Jack
 * @since 2025-07-21
 */
@Getter
@Setter
@TableName("openlist_strm")
public class OpenlistStrmPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 自增主键
     */
    @TableId(value = "strm_id", type = IdType.AUTO)
    private Integer strmId;

    /**
     * strm目录
     */
    @TableField("strm_path")
    private String strmPath;

    /**
     * strm文件名称
     */
    @TableField("strm_file_name")
    private String strmFileName;

    /**
     * 状态0-失败1-成功
     */
    @TableField("strm_status")
    private String strmStatus;

    /**
     * 网盘源文件大小（字节）。目录级生成时采集；单文件生成拿不到，为 null
     */
    @TableField("file_size")
    private Long fileSize;

    /**
     * 失败原因。只在失败状态下有值，写入成功状态时必须一并清空。
     * <p>
     * 刻意用 {@code FieldStrategy.ALWAYS}：默认的 NOT_NULL 策略下实体字段为 null 会被跳过，
     * 一条曾经失败、后来重跑成功的记录经 updateById 写成成功时，旧原因会原样留在库里，
     * 页面上出现「成功，原因：写入 .strm 文件失败」。ALWAYS 让状态与原因每次一起写，
     * 代价是任何按实体更新本表的地方都要带上当前原因（没带就是清空），见各 status 写入点。
     */
    @TableField(value = "fail_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String failReason;
}
