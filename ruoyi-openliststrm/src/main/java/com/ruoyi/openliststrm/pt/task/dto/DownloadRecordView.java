package com.ruoyi.openliststrm.pt.task.dto;

import lombok.Data;

import java.util.Date;

/**
 * 下载记录的展示视图：在 {@link com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus}
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
    private Long size;
    private Integer seeders;
    private String state;
    private Double progress;
    private String failReason;
    private String failReasonCode;
    private Date pushedTime;
    private Date completedTime;
}
