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
 * PT 洗版（质量升级）配置（单行表，id 恒为 1）
 * </p>
 * <p>
 * 只放"哪些维度参与比较、按什么顺序"与目标质量（cutoff），各维度内部"谁比谁好"的优先级列表
 * 复用 {@link PtFilterConfigPlus} 的 resolutionPriority / sourcePriority / releaseGroupPriority——
 * "2160p 比 1080p 好"这件事只该有一处定义，两份配置迟早会说法不一致。
 * </p>
 *
 * @author Jack
 * @since 2026-08-04
 */
@Getter
@Setter
@TableName("pt_upgrade_config")
public class PtUpgradeConfigPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 本表恒定只有一行 */
    public static final int SINGLETON_ID = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 洗版总开关 0-否 1-是。
     * <p>
     * 默认关闭是刻意的：开启前用户必须先确认目标质量，否则每一集都会永远搜下去，
     * 把索引器配额烧干。
     * </p>
     */
    @TableField("enabled")
    private String enabled;

    /**
     * 洗版比较的维度顺序，逗号分隔，取值见 {@code UpgradeDimension}。
     * <p>
     * <b>刻意不含 SEEDERS / SIZE / FREE</b>——那些不是画质。把它们放进升级判定会导致：
     * 同分辨率但做种更多的种子被判成"更优"→ 下载 → 下一轮又有别的种子做种更多 → 无限洗版。
     * </p>
     */
    @TableField("quality_priority")
    private String qualityPriority;

    /** 目标分辨率(cutoff)，达到即停止洗版；空表示该项不约束 */
    @TableField("target_resolution")
    private String targetResolution;

    /** 目标媒介来源(cutoff)，逗号分隔，命中其一即满足该项；空表示不约束 */
    @TableField("target_sources")
    private String targetSources;

    /** 目标质量标签(cutoff)，逗号分隔，须全部具备；空表示不约束 */
    @TableField("target_tags")
    private String targetTags;

    /** 洗版同时在途的下载数上限。独立于补缺集：缺集是刚需，洗版是锦上添花，不能抢名额 */
    @TableField("max_concurrent")
    private Integer maxConcurrent;

    /** 洗版扫描周期(小时) */
    @TableField("scan_interval_hours")
    private Integer scanIntervalHours;
}
