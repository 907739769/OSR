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
 * 重命名孤儿记录（一致性检查待清理项）
 * </p>
 *
 * @author Jack
 * @since 2026-07-19
 */
@Getter
@Setter
@TableName("rename_orphan")
public class RenameOrphanPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 自增主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 关联 rename_detail.id
     */
    @TableField("detail_id")
    private Integer detailId;

    /**
     * 重命名后目录
     */
    @TableField("new_path")
    private String newPath;

    /**
     * 重命名后文件名
     */
    @TableField("new_name")
    private String newName;

    /**
     * 标题
     */
    @TableField("title")
    private String title;

    /**
     * 年份
     */
    @TableField("year")
    private String year;

    /**
     * 媒体类型
     */
    @TableField("media_type")
    private String mediaType;

    /**
     * 孤儿原因 local_missing-本地文件已删除 source_missing-网盘源已删除
     */
    @TableField("reason")
    private String reason;

    /**
     * 状态 0-待处理 1-已清理 2-已忽略
     */
    @TableField("status")
    private String status;

    /**
     * 发现时间
     */
    @TableField("found_time")
    private Date foundTime;

    /**
     * 清理/忽略时间
     */
    @TableField("clean_time")
    private Date cleanTime;
}
