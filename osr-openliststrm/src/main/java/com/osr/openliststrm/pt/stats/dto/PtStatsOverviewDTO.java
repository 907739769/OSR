package com.osr.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * PT 统计总览：仪表盘顶部统计卡片与首页 PT 概览卡的数据来源。
 * 下载相关各项按 {@link #rangeDays} 统计（null 为全部历史），订阅数与 H&R 数是当前状态。
 *
 * @author Jack
 */
@Data
public class PtStatsOverviewDTO {

    /** 总订阅数 */
    private long totalSubscriptions;

    /** 活跃订阅数（status=ACTIVE） */
    private long activeSubscriptions;

    /** 统计区间天数；null 表示全部历史 */
    private Integer rangeDays;

    /** 区间内推送的下载记录数（不限状态） */
    private long totalDownloadRecords;

    /** 区间内完成数（按 completed_time） */
    private long completedCount;

    /** 区间内失败且尚未被后续推送接替的记录数（按 FAILED 行的 update_time） */
    private long failedCount;

    /** 成功率 = 完成 / (完成 + 未接替失败)，百分比数值(0~100)，保留1位小数；分母为0时记0 */
    private double successRate;

    /** 区间内完成记录的平均下载耗时(分钟)，pushed_time~completed_time；无完成记录时记0 */
    private double avgDurationMinutes;

    /** 当前处于「可能已 H&R」(hr_state=VIOLATED) 的记录数，不受区间影响 */
    private long hrViolatedCount;
}
