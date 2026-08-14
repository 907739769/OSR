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

    /**
     * 逗号分隔，<b>种子描述</b>命中任一则淘汰；空表示不限。
     * <p>
     * 与 {@link #excludeKeywords} 是同一条语义，只是判定对象换成 description——有一类属性
     * 标题里根本不写，最典型的是蓝光原盘：国内 PT 站普遍只在描述里标一句「原盘」，
     * 标题与压制版逐字同构（两者都解析成 {@code source=BluRay}，来源白名单分不开），
     * 体积上限又会连体积区间重叠的 REMUX 一起切掉。
     * </p>
     * <p>描述为空时放行而非淘汰：不少索引器压根不返回 {@code <description>}。</p>
     */
    @TableField("description_exclude_keywords")
    private String descriptionExcludeKeywords;

    /** 分辨率优先级，逗号分隔，越靠前越优先 */
    @TableField("resolution_priority")
    private String resolutionPriority;

    /**
     * 分辨率白名单，逗号分隔，空表示不限。
     * 与 resolutionPriority 不同：这是硬性过滤(不在白名单里的直接淘汰)，priority 只影响排序
     */
    @TableField("resolution_whitelist")
    private String resolutionWhitelist;

    /**
     * 媒介来源白名单(WEB-DL/BluRay/REMUX 等)，逗号分隔，空表示不限。
     * 语义与 resolutionWhitelist 完全一致：硬性过滤，解析不出来源的种子在白名单非空时也淘汰
     */
    @TableField("source_whitelist")
    private String sourceWhitelist;

    /** 媒介来源优先级，逗号分隔，越靠前越优先，只影响排序 */
    @TableField("source_priority")
    private String sourcePriority;

    /** 必需的质量标签(HDR/ATMOS/REMUX 等)，逗号分隔，种子须全部具备；空表示不限 */
    @TableField("required_tags")
    private String requiredTags;

    /** 命中任一则淘汰的质量标签，逗号分隔 */
    @TableField("exclude_tags")
    private String excludeTags;

    /** 发布组优先级，逗号分隔，越靠前越优先，只影响排序；不在列表内的排最后，不淘汰 */
    @TableField("release_group_priority")
    private String releaseGroupPriority;

    /** 排序维度顺序，逗号分隔，取值见 SortDimension 枚举 */
    @TableField("sort_priority")
    private String sortPriority;

    /** 体积接近度的目标值(字节)，0 表示该维度不参与比较 */
    @TableField("preferred_size")
    private Long preferredSize;

    /**
     * 体积上下限与偏好体积是否按<b>每集</b>判定 0-否 1-是，默认 1。
     * <p>
     * 剧集的种子常是区间包或季包，整包体积是单集的几倍到几十倍。关掉这个开关后，
     * 同一份体积阈值对单集和多集包不可能同时成立——上限会把所有包切光，下限会放行所有单集。
     * 单集资源折算前后取值相同，因此这个开关只影响多集包。
     * </p>
     */
    @TableField("size_per_episode")
    private String sizePerEpisode;

    /** 外语电影是否需要中文字幕 0-否 1-是 */
    @TableField("require_chinese_subtitle")
    private String requireChineseSubtitle;

    /** 是否直接淘汰来自 H&R 考核站点的种子 0-否 1-是。软性规避请改用 SortDimension.HR 降权维度 */
    @TableField("avoid_hit_and_run")
    private String avoidHitAndRun;

    /** 自动补搜的全局周期(小时) */
    @TableField("auto_search_interval_hours")
    private Integer autoSearchIntervalHours;
}
