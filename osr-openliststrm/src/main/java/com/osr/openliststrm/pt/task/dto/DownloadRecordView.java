package com.osr.openliststrm.pt.task.dto;

import lombok.Data;

import java.util.Date;

/**
 * 下载记录的展示视图：在 {@link com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus}
 * 基础上补充订阅/索引器/下载器的展示名，避免前端拿着一堆 id 自己拼。
 *
 * @author Jack
 */
@Data
public class DownloadRecordView {

    private Integer id;

    private Integer subId;
    /** 关联订阅已被删除时为 null，前端按"订阅已删除"处理 */
    private String subTitle;
    private String episodeLabel;

    private Integer indexerId;
    private String indexerName;

    private Integer downloaderId;
    private String downloaderName;

    private String title;
    /** 种子 hash，从下载器回填；供用户去下载器里定位任务，推送后还没回填到时为 null */
    private String torrentHash;
    private Long size;
    /** 推送那一刻索引器给出的做种数（快照，不随时间更新） */
    private Integer seeders;
    private String state;
    private Double progress;
    private String failReason;
    private String failReasonCode;
    private Date pushedTime;
    private Date completedTime;

    /** H&R 保种状态 PENDING/SATISFIED/VIOLATED；null 表示来源站点没有 H&R 考核，前端不展示该列 */
    private String hrState;
    /** 最近一次采样到的累计做种秒数 */
    private Long hrSeedSeconds;
    /** 最近一次采样到的分享率 */
    private Double hrRatio;
    /** 来源站点要求的最短做种时长(小时)，供前端算"还差多久"；站点不按时长考核时为 0 */
    private Integer hrSeedHoursRequired;
    /** 来源站点要求的最低分享率；站点不按分享率考核时为 0 */
    private Double hrRatioRequired;

    /** 标题解析出的发布组；解析不出时为 null，前端据此隐藏「拉黑该发布组」 */
    private String releaseGroup;
    /** 这个种子（GUID 维度）是否已在黑名单中 */
    private Boolean guidBlacklisted;
    /** 这个发布组是否已在黑名单中；解析不出发布组时为 false */
    private Boolean releaseGroupBlacklisted;
    /**
     * 失败记录之后，同一订阅同一集已经有了更新的推送（重试或自动补搜的结果），这里是其中最早那条的 id。
     * 非 FAILED 或没有后续推送时为 null。前端据此把这条标成「已由 #x 接替」并隐藏重试——
     * 该去看、该去重试的是后面那条。
     */
    private Integer supersededById;
}
