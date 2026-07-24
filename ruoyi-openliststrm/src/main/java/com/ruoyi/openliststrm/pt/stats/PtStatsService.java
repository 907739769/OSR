package com.ruoyi.openliststrm.pt.stats;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionService;
import com.ruoyi.openliststrm.pt.task.DownloadRecordState;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * PT 订阅统计仪表盘的聚合查询：全部用 QueryWrapper 原生 select/groupBy + IService.listMaps
 * 完成分组统计，不新建 XML Mapper，见设计文档 2.2 节。
 *
 * @author Jack
 */
@Service
public class PtStatsService {

    private static final String STATE_COMPLETED = DownloadRecordState.COMPLETED.value();
    private static final String STATE_FAILED = DownloadRecordState.FAILED.value();

    private final IPtDownloadRecordPlusService downloadRecordService;
    private final IPtSearchLogPlusService searchLogService;
    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtIndexerPlusService indexerService;

    public PtStatsService(IPtDownloadRecordPlusService downloadRecordService,
                           IPtSearchLogPlusService searchLogService,
                           IPtSubscriptionPlusService subscriptionService,
                           IPtIndexerPlusService indexerService) {
        this.downloadRecordService = downloadRecordService;
        this.searchLogService = searchLogService;
        this.subscriptionService = subscriptionService;
        this.indexerService = indexerService;
    }

    /**
     * 总览统计：订阅总数/活跃数 + 下载记录一次性聚合(总数/完成/失败/成功率/全局平均耗时)，
     * 不做时间范围筛选(设计文档2.1，overview 覆盖全量历史)。
     */
    public PtStatsOverviewDTO overview() {
        PtStatsOverviewDTO dto = new PtStatsOverviewDTO();
        dto.setTotalSubscriptions(subscriptionService.count());
        dto.setActiveSubscriptions(subscriptionService.count(
                Wrappers.<PtSubscriptionPlus>query().eq("status", SubscriptionService.STATUS_ACTIVE)));

        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                Wrappers.<PtDownloadRecordPlus>query().select(
                        "count(*) as total, "
                                + "SUM(CASE WHEN state='" + STATE_COMPLETED + "' THEN 1 ELSE 0 END) as completed_count, "
                                + "SUM(CASE WHEN state='" + STATE_FAILED + "' THEN 1 ELSE 0 END) as failed_count, "
                                + "AVG(CASE WHEN state='" + STATE_COMPLETED
                                + "' THEN TIMESTAMPDIFF(MINUTE, pushed_time, completed_time) ELSE NULL END) as avg_duration_minutes"));
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);

        long total = asLong(row.get("total"));
        long completed = asLong(row.get("completed_count"));
        long failed = asLong(row.get("failed_count"));
        dto.setTotalDownloadRecords(total);
        dto.setCompletedCount(completed);
        dto.setFailedCount(failed);
        dto.setSuccessRate(total > 0 ? Math.round(completed * 1000.0 / total) / 10.0 : 0.0);
        Double avg = asDouble(row.get("avg_duration_minutes"));
        dto.setAvgDurationMinutes(avg == null ? 0.0 : avg);
        return dto;
    }

    private static long asLong(Object v) {
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    private static Double asDouble(Object v) {
        return v == null ? null : Double.parseDouble(v.toString());
    }
}
