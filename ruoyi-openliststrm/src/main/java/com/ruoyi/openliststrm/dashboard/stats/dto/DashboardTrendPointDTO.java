package com.ruoyi.openliststrm.dashboard.stats.dto;

import lombok.Data;

/**
 * 首页仪表盘趋势图的单日数据点：按 create_time 所在日期分组，日期连续补齐（缺失日期记0），
 * 前端折线图需要连续的日期轴。
 *
 * @author Jack
 */
@Data
public class DashboardTrendPointDTO {

    /** 日期，格式 yyyy-MM-dd */
    private String date;

    /** 当日总数 */
    private long totalCount;

    /** 当日成功数 */
    private long successCount;

    /** 当日失败数 */
    private long failedCount;
}
