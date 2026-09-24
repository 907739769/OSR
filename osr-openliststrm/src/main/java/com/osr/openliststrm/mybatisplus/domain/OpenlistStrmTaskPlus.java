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

    /**
     * 任务级配置覆盖（JSON），为空表示全部沿用全局配置。
     * 键：outputDir / downloadSub / minFileSize，语义见 StrmSettingsFactory。
     */
    @TableField("strm_override")
    private String strmOverride;

    /**
     * 定时执行时是否增量扫描（跳过修改时间没变的叶子目录）：0-否 1-是。
     * 只影响定时任务；页面/TG 手动执行、复制完成触发的生成一律全量。
     */
    @TableField("incremental")
    private String incremental;

    /** 上次全量扫描完成的时间，NULL 表示还没全量扫过（下次定时执行必定全量） */
    @TableField("last_full_scan_time")
    private Date lastFullScanTime;

    /**
     * 是否开启了增量扫描。刻意不叫 isIncremental()：Lombok 已为 incremental 字段生成 getIncremental()，
     * 再加一个 boolean 的 isXxx 会让 MyBatis 在第一次写库时报 getter 歧义（见根 AGENTS.md）
     */
    public boolean incrementalOn() {
        return "1".equals(incremental);
    }
}
