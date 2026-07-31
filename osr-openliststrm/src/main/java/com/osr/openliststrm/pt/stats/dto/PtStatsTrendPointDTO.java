package com.osr.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * 下载量趋势的单日数据点：按 pushed_time 所在日期分组，日期连续补齐（缺失日期记0），
 * 前端折线图需要连续的日期轴，缺口交给 avgDurationMinutes=null 由前端跳过该点。
 *
 * @author Jack
 */
@Data
public class PtStatsTrendPointDTO {

    /** 日期，格式 yyyy-MM-dd */
    private String date;

    /** 当日推送数(该日 pushed_time 落在当天的记录数，不限最终状态) */
    private long pushedCount;

    /** 当日推送且已完成的数量 */
    private long completedCount;

    /** 当日推送且已失败的数量 */
    private long failedCount;

    /** 当日完成记录的平均耗时(分钟)；当日无完成记录时为 null，前端据此跳过该点不画线段 */
    private Double avgDurationMinutes;
}
