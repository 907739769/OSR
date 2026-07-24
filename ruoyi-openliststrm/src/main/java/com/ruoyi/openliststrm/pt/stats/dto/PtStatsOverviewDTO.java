package com.ruoyi.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * PT 统计总览：仪表盘顶部 5 张统计卡片的数据来源，一次查询覆盖，不做时间范围筛选。
 *
 * @author Jack
 */
@Data
public class PtStatsOverviewDTO {

    /** 总订阅数 */
    private long totalSubscriptions;

    /** 活跃订阅数（status=ACTIVE） */
    private long activeSubscriptions;

    /** 下载记录总数（不限状态） */
    private long totalDownloadRecords;

    /** 完成数（state=COMPLETED） */
    private long completedCount;

    /** 失败数（state=FAILED） */
    private long failedCount;

    /** 成功率，百分比数值(0~100)，保留1位小数；总数为0时记0，不做除零 */
    private double successRate;

    /** 全局平均下载耗时(分钟)，基于 COMPLETED 记录的 pushed_time~completed_time；无 COMPLETED 记录时记0 */
    private double avgDurationMinutes;
}
