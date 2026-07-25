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
 * PT 订阅
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Getter
@Setter
@TableName("pt_subscription")
public class PtSubscriptionPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** TMDb ID */
    @TableField("tmdb_id")
    private String tmdbId;

    /** IMDb ID（如 tt0125664），建订阅时从 TMDb 详情/external_ids 获取，用于索引器 ID 精确搜索 */
    @TableField("imdb_id")
    private String imdbId;

    /** 媒体类型 TV/MOVIE */
    @TableField("media_type")
    private String mediaType;

    /** 作品标题 */
    @TableField("title")
    private String title;

    /** 英文原名，与中文 title 一起用于匹配种子标题 */
    @TableField("original_title")
    private String originalTitle;

    /** 年份 */
    @TableField("year")
    private String year;

    /** 季号；电影恒为 0（哨兵值，不用 null——否则唯一索引对电影失效） */
    @TableField("season")
    private Integer season;

    /** 总集数，电影恒为 1 */
    @TableField("total_episodes")
    private Integer totalEpisodes;

    /** 状态 ACTIVE/COMPLETED/PAUSED */
    @TableField("status")
    private String status;

    /** 订阅级过滤覆盖(JSON)，空表示全用全局配置 */
    @TableField("filter_override")
    private String filterOverride;

    /** 订阅级下载追踪覆盖(JSON)，当前仅支持 zombieTimeoutHours 键，空表示全用全局配置 */
    @TableField("download_override")
    private String downloadOverride;

    /** 指定下载器，空表示用唯一启用的那个 */
    @TableField("downloader_id")
    private Integer downloaderId;

    /** TMDb 海报路径，列表展示用 */
    @TableField("poster_path")
    private String posterPath;

    /** 上次命中种子的时间 */
    @TableField("last_match_time")
    private Date lastMatchTime;

    /** 是否开启自动定时补搜 0-否 1-是 */
    @TableField("auto_search")
    private String autoSearch;

    /** 上次发起搜索补集的时间，用于自动补搜到期判断与前端展示 */
    @TableField("last_search_time")
    private Date lastSearchTime;

    /** 排序方式：lastMatchTime=按上次命中时间倒序；其余/空=默认按 id 倒序。仅供列表查询用，不落库 */
    @TableField(exist = false)
    private String sortBy;
}
