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
 * PT 下载记录
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Getter
@Setter
@TableName("pt_download_record")
public class PtDownloadRecordPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 订阅ID */
    @TableField("sub_id")
    private Integer subId;

    /** 集号，电影恒为 0；区间匹配时为区间起始集号 */
    @TableField("episode")
    private Integer episode;

    /** 区间匹配种子的区间结尾集号（如 S01E01-E02 对应 episode=1, episodeEnd=2）；非区间匹配为 null */
    @TableField("episode_end")
    private Integer episodeEnd;

    /** 来源索引器ID */
    @TableField("indexer_id")
    private Integer indexerId;

    /** 索引器给出的条目唯一标识(RSS guid) */
    @TableField("guid")
    private String guid;

    /** guid 的 SHA-256 十六进制，用于唯一索引 */
    @TableField("guid_hash")
    private String guidHash;

    /** 种子hash，从下载器回填，仅供排查 */
    @TableField("torrent_hash")
    private String torrentHash;

    /** 推送时打的唯一标签 osr-pt-{id} */
    @TableField("tracking_tag")
    private String trackingTag;

    /** 原始种子标题 */
    @TableField("title")
    private String title;

    /** 体积(字节) */
    @TableField("size")
    private Long size;

    /** 做种数 */
    @TableField("seeders")
    private Integer seeders;

    /** 推送到的下载器ID */
    @TableField("downloader_id")
    private Integer downloaderId;

    /** 状态 PUSHED/DOWNLOADING/COMPLETED/FAILED */
    @TableField("state")
    private String state;

    /** 下载进度 0~1，仅 DOWNLOADING/COMPLETED 状态有意义 */
    @TableField("progress")
    private Double progress;

    /** 是否已完成文件级过滤（排除季包/区间匹配中非目标集数的文件），避免每轮轮询重复调用下载器 API */
    @TableField("files_selected")
    private Boolean filesSelected;

    /** 失败原因 */
    @TableField("fail_reason")
    private String failReason;

    /** 失败原因结构化分类：TORRENT_NOT_FOUND/ZOMBIE_TIMEOUT/OTHER，历史记录（分类能力上线前产生）为 null */
    @TableField("fail_reason_code")
    private String failReasonCode;

    /** 推送时间 */
    @TableField("pushed_time")
    private Date pushedTime;

    /** 完成时间 */
    @TableField("completed_time")
    private Date completedTime;

    /**
     * H&R 保种状态，取值见 {@link com.osr.openliststrm.pt.task.HitAndRunState}。
     * {@code null} 表示不适用——来源站点没开 H&R 考核，或还没下载完成
     */
    @TableField("hr_state")
    private String hrState;

    /** 最近一次采样到的累计做种秒数，供前端展示"还差多久"，也是判定 VIOLATED 时的依据 */
    @TableField("hr_seed_seconds")
    private Long hrSeedSeconds;

    /** 最近一次采样到的分享率 */
    @TableField("hr_ratio")
    private Double hrRatio;

    /** 达标时间，hr_state 转为 SATISFIED 时写入 */
    @TableField("hr_satisfied_time")
    private Date hrSatisfiedTime;

    /**
     * 是否已按站点 H&R 规则给下载器里的这个种子下发过分享限额。
     * <p>
     * 独立于 {@code filesSelected}：两者时机相近但语义无关，共用一个标记会让
     * 任意一边失败时另一边被误判为已完成。
     * </p>
     */
    @TableField("hr_limits_applied")
    private Boolean hrLimitsApplied;
}
