package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * <p>
 * PT 下载器自动删种规则：一条「体积区间 → 最短做种时长」的判定。
 * </p>
 * <p>
 * 一个下载器可以配多条，按 {@code sortOrder} 从小到大取<b>第一条体积区间命中</b>的规则，
 * 命中之后不再往后看——分级删除（大包做满 3 天就删、小包做满 7 天再删）就是这样表达的。
 * 一个种子的体积落不进任何规则的区间时<b>不删</b>：没有规则说该删它，就不该动它。
 * </p>
 *
 * @author Jack
 * @since 2026-08-10
 */
@Getter
@Setter
@TableName("pt_clean_rule")
public class PtCleanRulePlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 所属下载器 ID */
    @TableField("downloader_id")
    private Integer downloaderId;

    /** 规则名，仅用于展示 */
    @TableField("name")
    private String name;

    /** 体积区间下界（GB，含） */
    @TableField("min_size_gb")
    private BigDecimal minSizeGb;

    /** 体积区间上界（GB，不含），null 表示不限 */
    @TableField("max_size_gb")
    private BigDecimal maxSizeGb;

    /** 最短做种时长（小时），种子累计做种达到该值才允许删除 */
    @TableField("min_seed_hours")
    private Integer minSeedHours;

    /** 是否连同文件一起删除 0-否 1-是 */
    @TableField("delete_files")
    private String deleteFiles;

    /** 是否启用 0-否 1-是 */
    @TableField("enabled")
    private String enabled;

    /** 匹配顺序，值小的先匹配 */
    @TableField("sort_order")
    private Integer sortOrder;

    /** 备注 */
    @TableField("remark")
    private String remark;

    /**
     * 是否启用。
     * <p>
     * <b>方法名不能叫 {@code isEnabled()}</b>：Lombok 已经给 {@code String enabled} 生成了
     * {@code getEnabled()}，再加一个返回 boolean 的 {@code isEnabled()} 会让 MyBatis 认为
     * 属性 {@code enabled} 有两个类型不一致的 getter，直接抛
     * {@code Illegal overloaded getter method with ambiguous type}，连 INSERT 都跑不了。
     * 同样的理由，{@code getXxx}/{@code isXxx} 形式的辅助方法一律不要往 *Plus 实体上加——
     * 参考 {@code PtIndexerPlus#hitAndRunEnabled()} 的命名。
     * </p>
     */
    public boolean enabledOn() {
        return "1".equals(enabled);
    }

    /** 删种时是否连同文件一起删 */
    public boolean deleteFilesToo() {
        return !"0".equals(deleteFiles);
    }

    /**
     * 种子体积是否落在本规则的区间内。区间是<b>左闭右开</b>的，相邻规则
     * （0~50、50~不限）因此不会同时命中同一个体积，也不会在边界上留缝。
     *
     * @param sizeBytes 种子体积（字节）
     */
    public boolean sizeMatches(long sizeBytes) {
        double gb = sizeBytes / (1024.0 * 1024 * 1024);
        double min = minSizeGb == null ? 0.0 : minSizeGb.doubleValue();
        if (gb < min) {
            return false;
        }
        return maxSizeGb == null || gb < maxSizeGb.doubleValue();
    }

    /** 最短做种时长换算成秒；未配置或非正数按 0 处理（该维度不设限） */
    public long minSeedSeconds() {
        return minSeedHours == null || minSeedHours <= 0 ? 0L : minSeedHours * 3600L;
    }
}
