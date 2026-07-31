package com.osr.openliststrm.pt.stats.dto;

import lombok.Data;

import java.util.Date;

/**
 * Top 活跃订阅：按下载记录数分组的订阅排行。订阅已被删除但历史下载记录还在时，
 * title 兜底显示"（订阅已删除）"，season/mediaType/lastMatchTime 为 null，不抛异常。
 *
 * @author Jack
 */
@Data
public class PtStatsActiveSubscriptionDTO {

    private Integer subId;

    /** 订阅已被删除时显示"（订阅已删除）" */
    private String title;

    /** 订阅已被删除时为 null */
    private Integer season;

    /** 订阅已被删除时为 null，取值 TV/MOVIE */
    private String mediaType;

    private long downloadCount;

    private long completedCount;

    private long failedCount;

    /** 订阅已被删除时为 null */
    private Date lastMatchTime;
}
