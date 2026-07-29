package com.ruoyi.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * <p>
 * 热门自动订阅规则
 * </p>
 *
 * @author Jack
 * @since 2026-07-29
 */
@Getter
@Setter
@TableName("pt_auto_add_rule")
public class PtAutoAddRulePlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 规则名称 */
    @TableField("name")
    private String name;

    /** 是否启用 0-否 1-是 */
    @TableField("enabled")
    private String enabled;

    /** 媒体类型 TV/MOVIE */
    @TableField("media_type")
    private String mediaType;

    /** 数据源：TMDB_TRENDING_DAY / TMDB_TRENDING_WEEK / TMDB_DISCOVER */
    @TableField("source")
    private String source;

    /** 排除的 TMDb 类型 ID，逗号分隔，命中任意一个即跳过 */
    @TableField("genre_exclude")
    private String genreExclude;

    /** 最低评分，低于此值跳过，为空不过滤 */
    @TableField("min_vote_average")
    private Double minVoteAverage;

    /** 最低评分人数，低于此值跳过，为空不过滤 */
    @TableField("min_vote_count")
    private Integer minVoteCount;

    /** 地区，仅 TMDB_DISCOVER 用于 region 参数，为空不限制 */
    @TableField("region")
    private String region;

    /** 单轮最多新增几部，防止一次拉爆索引器/下载器 */
    @TableField("max_add_per_run")
    private Integer maxAddPerRun;

    /** 执行间隔（小时） */
    @TableField("interval_hours")
    private Integer intervalHours;

    /** 指定下载器，空表示用唯一启用的那个 */
    @TableField("downloader_id")
    private Integer downloaderId;

    /** 建订阅时透传的过滤覆盖(JSON)，可空 */
    @TableField("filter_override")
    private String filterOverride;

    /** 上次执行时间 */
    @TableField("last_run_time")
    private Date lastRunTime;
}
