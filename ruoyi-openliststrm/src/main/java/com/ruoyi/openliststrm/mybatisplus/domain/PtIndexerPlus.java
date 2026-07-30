package com.ruoyi.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.mybatisplus.BaseEntity;
import com.ruoyi.openliststrm.mybatisplus.handler.EncryptedStringTypeHandler;
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
}
