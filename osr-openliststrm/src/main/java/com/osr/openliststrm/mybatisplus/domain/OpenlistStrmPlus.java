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
}
