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
 * 重命名任务配置
 * </p>
 *
 * @author Jack
 * @since 2025-10-10
 */
@Getter
@Setter
@TableName("rename_task")
public class RenameTaskPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /**
     * 自增主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 源目录
     */
    @TableField("source_folder")
    private String sourceFolder;

    /**
     * 目标目录
     */
    @TableField("target_root")
    private String targetRoot;

    /**
     * 状态
     */
    @TableField("status")
    private String status;

    /**
     * 是否启用刮削 0-否 1-是
     */
    @TableField("scrape_enabled")
    private String scrapeEnabled;

    /**
     * 是否生成NFO 0-否 1-是
     */
    @TableField("scrape_nfo")
    private String scrapeNfo;

    /**
     * 是否下载图片 0-否 1-是
     */
    @TableField("scrape_images")
    private String scrapeImages;

    /**
     * 刮削时是否强制覆盖已有的 NFO/图片文件 0-否 1-是
     */
    @TableField("scrape_force_overwrite")
    private String scrapeForceOverwrite;
}
