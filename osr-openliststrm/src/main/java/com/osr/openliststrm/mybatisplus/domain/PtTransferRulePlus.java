package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import com.osr.common.utils.StringUtils;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * <p>
 * PT 转移做种规则：一对「源下载器 → 目标下载器」，加上决定"哪些种子该搬"的筛选条件。
 * </p>
 * <p>
 * 与自动删种规则（{@link PtCleanRulePlus}）的结构差异是刻意的：删种规则是同一个下载器上的
 * 多条分级规则、按顺序取第一条命中的；转移规则之间没有优先级关系，每条各管一对下载器，
 * 因此没有 {@code sort_order}，多条规则各自独立执行。
 * </p>
 *
 * @author Jack
 * @since 2026-08-15
 */
@Getter
@Setter
@TableName("pt_transfer_rule")
public class PtTransferRulePlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 规则名，仅用于展示 */
    @TableField("name")
    private String name;

    /** 源下载器 ID（种子从这里搬走） */
    @TableField("source_downloader_id")
    private Integer sourceDownloaderId;

    /** 目标下载器 ID（种子搬到这里继续做种） */
    @TableField("target_downloader_id")
    private Integer targetDownloaderId;

    /** 是否启用 0-否 1-是 */
    @TableField("enabled")
    private String enabled;

    /** 源下载器上最短做种时长（小时），达到才转移；0 表示不限 */
    @TableField("min_seed_hours")
    private Integer minSeedHours;

    /** 体积区间下界（GB，含） */
    @TableField("min_size_gb")
    private BigDecimal minSizeGb;

    /** 体积区间上界（GB，不含），null 表示不限 */
    @TableField("max_size_gb")
    private BigDecimal maxSizeGb;

    /** 只转移带其中任一标签的种子，逗号分隔；为空表示不限 */
    @TableField("include_tags")
    private String includeTags;

    /** 带其中任一标签的种子永不转移，逗号分隔 */
    @TableField("exclude_tags")
    private String excludeTags;

    /** 保存路径前缀映射，JSON 数组；两个下载器挂载一致时留空。解析在 Service 层做 */
    @TableField("path_mapping")
    private String pathMapping;

    /** 在目标下载器上打的标签；为空则不打 */
    @TableField("target_tag")
    private String targetTag;

    /** 目标端校验通过后是否删除源下载器上的种子 0-否 1-是 */
    @TableField("delete_source")
    private String deleteSource;

    /** 单轮最多发起多少个转移，0 表示不限 */
    @TableField("max_per_round")
    private Integer maxPerRound;

    /** 目标端校验超时（分钟） */
    @TableField("verify_timeout_minutes")
    private Integer verifyTimeoutMinutes;

    /** 备注 */
    @TableField("remark")
    private String remark;

    /**
     * 是否启用。
     * <p>
     * <b>方法名不能叫 {@code isEnabled()}</b>：Lombok 已经给 {@code String enabled} 生成了
     * {@code getEnabled()}，再加一个返回 boolean 的 {@code isEnabled()} 会让 MyBatis 认为
     * 属性 {@code enabled} 有两个类型不一致的 getter，第一条 INSERT 才抛
     * {@code Illegal overloaded getter method with ambiguous type}——编译、启动、单测全能过。
     * 命名参考 {@code PtCleanRulePlus#enabledOn()}。
     * </p>
     */
    public boolean enabledOn() {
        return "1".equals(enabled);
    }

    /**
     * 转移成功后是否删除源下载器上的种子。
     * <p>
     * 这里只表达"删不删种"，<b>文件永远不删</b>——那份文件正是目标下载器接下来要做种的数据，
     * 删了等于把刚搬过去的种子立刻变成"文件丢失"。所以 Service 调 deleteTorrent 时
     * deleteFiles 参数恒为 false，不受本开关影响。
     * </p>
     */
    public boolean deleteSourceOn() {
        return !"0".equals(deleteSource);
    }

    /**
     * 种子体积是否落在本规则的区间内，<b>左闭右开</b>，与删种规则口径一致。
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

    /** 单轮上限；null 或非正数表示不限 */
    public int roundLimit() {
        return maxPerRound == null || maxPerRound <= 0 ? Integer.MAX_VALUE : maxPerRound;
    }

    /** 校验超时秒数；未配置时用 120 分钟兜底，不能是 0——0 会让每个转移在下一轮立刻被判超时 */
    public long verifyTimeoutSeconds() {
        return verifyTimeoutMinutes == null || verifyTimeoutMinutes <= 0 ? 7200L : verifyTimeoutMinutes * 60L;
    }

    /** 必须命中的标签集合（小写）；空集表示不限 */
    public Set<String> includeTagSet() {
        return splitTags(includeTags);
    }

    /** 一票否决的标签集合（小写） */
    public Set<String> excludeTagSet() {
        return splitTags(excludeTags);
    }

    private Set<String> splitTags(String raw) {
        Set<String> tags = new HashSet<>();
        if (StringUtils.isBlank(raw)) {
            return tags;
        }
        for (String tag : raw.split(",")) {
            String trimmed = tag.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                tags.add(trimmed);
            }
        }
        return tags;
    }
}
