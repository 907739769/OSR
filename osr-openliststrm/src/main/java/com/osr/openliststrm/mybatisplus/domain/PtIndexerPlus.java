package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import com.osr.openliststrm.mybatisplus.handler.EncryptedStringTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * <p>
 * PT Torznab 索引器配置
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
@Getter
@Setter
@TableName(value = "pt_indexer", autoResultMap = true)
public class PtIndexerPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 索引器展示名 */
    @TableField("name")
    private String name;

    /** Torznab 接口地址 */
    @TableField("url")
    private String url;

    /** Torznab apikey，落库前经 {@link EncryptedStringTypeHandler} 透明加密，业务代码全程只接触明文 */
    @TableField(value = "api_key", typeHandler = EncryptedStringTypeHandler.class)
    private String apiKey;

    /** 逗号分隔的 Torznab 分类，空表示不限 */
    @TableField("categories")
    private String categories;

    /** RSS 轮询周期（秒） */
    @TableField("poll_interval")
    private Integer pollInterval;

    /** 是否启用 0-否 1-是 */
    @TableField("enabled")
    private String enabled;

    /** 上次轮询时间 */
    @TableField("last_poll_time")
    private Date lastPollTime;

    /** 上次轮询结果，OK 或错误信息 */
    @TableField("last_status")
    private String lastStatus;

    /** 连续失败次数，成功后归零 */
    @TableField("fail_count")
    private Integer failCount;

    /** 自动停用/最近一次自愈探测失败的时间，用于冷却期计时 */
    @TableField("disabled_at")
    private Date disabledAt;

    /** 上一轮拉取到的最新种子 guid 的 SHA-256 哈希，用于校验下一轮拉取窗口是否覆盖完整 */
    @TableField("last_seen_guid_hash")
    private String lastSeenGuidHash;

    /**
     * 该站点是否有 H&R（Hit and Run）考核 0-否 1-是。
     * <p>
     * H&R 是站点属性而不是种子属性：Torznab 协议里没有标准的 H&R 字段，
     * 索引器也不会逐条告诉你哪个种子要考核，只能按站点整体判定。
     * </p>
     */
    @TableField("hr_enabled")
    private String hrEnabled;

    /** H&R 要求的最短做种时长（小时），0 表示该站点不按时长考核 */
    @TableField("hr_seed_hours")
    private Integer hrSeedHours;

    /** H&R 要求的最低分享率，0 表示该站点不按分享率考核 */
    @TableField("hr_ratio")
    private Double hrRatio;

    /**
     * 该站点是否启用 H&R 考核。{@code hr_enabled} 为 1、且至少配了一个有效阈值才算数——
     * 只开开关不填阈值是一份不完整的配置，此时无从判断"做到什么程度才算达标"，
     * 按未启用处理，避免每个种子都永远停在"保种中"并反复提醒。
     */
    public boolean hitAndRunEnabled() {
        return "1".equals(hrEnabled) && (positive(hrSeedHours) || positive(hrRatio));
    }

    private static boolean positive(Number value) {
        return value != null && value.doubleValue() > 0;
    }
}
