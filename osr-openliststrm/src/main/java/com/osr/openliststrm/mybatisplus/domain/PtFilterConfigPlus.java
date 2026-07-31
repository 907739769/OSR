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
 * PT 全局过滤与排序配置（单行表，id 恒为 1）
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Getter
@Setter
@TableName("pt_filter_config")
public class PtFilterConfigPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 本表恒定只有一行 */
    public static final int SINGLETON_ID = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 最低做种数，低于此值淘汰 */
    @TableField("min_seeders")
    private Integer minSeeders;

    /** 最小体积(字节)，0 表示不限 */
    @TableField("min_size")
    private Long minSize;

    /** 最大体积(字节)，0 表示不限 */
    @TableField("max_size")
    private Long maxSize;

    /** 是否仅下载免费种 0-否 1-是 */
    @TableField("free_only")
    private String freeOnly;

    /** 逗号分隔，标题须命中其一，空表示不限 */
    @TableField("include_keywords")
    private String includeKeywords;

    /** 逗号分隔，标题命中任一则淘汰 */
    @TableField("exclude_keywords")
    private String excludeKeywords;

    /** 分辨率优先级，逗号分隔，越靠前越优先 */
    @TableField("resolution_priority")
    private String resolutionPriority;

    /**
     * 分辨率白名单，逗号分隔，空表示不限。
     * 与 resolutionPriority 不同：这是硬性过滤(不在白名单里的直接淘汰)，priority 只影响排序
     */
    @TableField("resolution_whitelist")
    private String resolutionWhitelist;

    /** 排序维度顺序，逗号分隔，取值见 SortDimension 枚举 */
    @TableField("sort_priority")
    private String sortPriority;

    /** 体积接近度的目标值(字节)，0 表示该维度不参与比较 */
    @TableField("preferred_size")
    private Long preferredSize;

    /** 外语电影是否需要中文字幕 0-否 1-是 */
    @TableField("require_chinese_subtitle")
    private String requireChineseSubtitle;

    /** 自动补搜的全局周期(小时) */
    @TableField("auto_search_interval_hours")
    private Integer autoSearchIntervalHours;
}
