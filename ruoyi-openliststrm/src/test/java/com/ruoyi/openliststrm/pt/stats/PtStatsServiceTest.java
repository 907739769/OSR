package com.ruoyi.openliststrm.pt.stats;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PtStatsServiceTest {

    @Mock private IPtDownloadRecordPlusService downloadRecordService;
    @Mock private IPtSearchLogPlusService searchLogService;
    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtIndexerPlusService indexerService;

    private PtStatsService service() {
        return new PtStatsService(downloadRecordService, searchLogService, subscriptionService, indexerService);
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void overview_下载记录为空_返回全0而不抛异常() {
        when(subscriptionService.count()).thenReturn(0L);
        when(subscriptionService.count(any(Wrapper.class))).thenReturn(0L);
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of());

        PtStatsOverviewDTO dto = service().overview();

        assertEquals(0L, dto.getTotalSubscriptions());
        assertEquals(0L, dto.getActiveSubscriptions());
        assertEquals(0L, dto.getTotalDownloadRecords());
        assertEquals(0L, dto.getCompletedCount());
        assertEquals(0L, dto.getFailedCount());
        assertEquals(0.0, dto.getSuccessRate());
        assertEquals(0.0, dto.getAvgDurationMinutes());
    }

    @Test
    void overview_正常数据_成功率与平均耗时计算正确() {
        when(subscriptionService.count()).thenReturn(20L);
        when(subscriptionService.count(any(Wrapper.class))).thenReturn(15L);
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("total", 100L, "completed_count", 80L, "failed_count", 10L, "avg_duration_minutes", 45.5)));

        PtStatsOverviewDTO dto = service().overview();

        assertEquals(20L, dto.getTotalSubscriptions());
        assertEquals(15L, dto.getActiveSubscriptions());
        assertEquals(100L, dto.getTotalDownloadRecords());
        assertEquals(80L, dto.getCompletedCount());
        assertEquals(10L, dto.getFailedCount());
        assertEquals(80.0, dto.getSuccessRate());
        assertEquals(45.5, dto.getAvgDurationMinutes());
    }
}
