package com.osr.openliststrm.pt.stats;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
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

    @Test
    void trend_缺失日期补齐为0且平均耗时为null() {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String onlyDay = today.format(fmt);
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("day", onlyDay, "pushed_count", 3L, "completed_count", 2L, "failed_count", 0L, "avg_duration_minutes", 30.0)));

        List<com.osr.openliststrm.pt.stats.dto.PtStatsTrendPointDTO> points = service().trend(7);

        assertEquals(7, points.size());
        var last = points.get(points.size() - 1);
        assertEquals(onlyDay, last.getDate());
        assertEquals(3L, last.getPushedCount());
        assertEquals(2L, last.getCompletedCount());
        assertEquals(0L, last.getFailedCount());
        assertEquals(30.0, last.getAvgDurationMinutes());

        var first = points.get(0);
        assertEquals(0L, first.getPushedCount());
        assertEquals(0L, first.getCompletedCount());
        assertEquals(0L, first.getFailedCount());
        org.junit.jupiter.api.Assertions.assertNull(first.getAvgDurationMinutes());
    }

    @Test
    void indexerHitRate_按索引器计算命中率_除零记0且未产生日志的索引器仍出现() {
        com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus withData =
                new com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus();
        withData.setId(1);
        withData.setName("索引器A");
        com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus withoutData =
                new com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus();
        withoutData.setId(2);
        withoutData.setName("索引器B");
        when(indexerService.list()).thenReturn(List.of(withData, withoutData));
        when(searchLogService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("indexer_id", 1, "accepted_count", 30L, "rejected_count", 10L)));

        List<com.osr.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO> result = service().indexerHitRate();

        assertEquals(2, result.size());
        var a = result.get(0);
        assertEquals(1, a.getIndexerId());
        assertEquals("索引器A", a.getIndexerName());
        assertEquals(30L, a.getAcceptedCount());
        assertEquals(10L, a.getRejectedCount());
        org.junit.jupiter.api.Assertions.assertTrue(a.isHasData());
        assertEquals(0.75, a.getHitRate());

        var b = result.get(1);
        assertEquals(2, b.getIndexerId());
        assertEquals(0L, b.getAcceptedCount());
        assertEquals(0L, b.getRejectedCount());
        org.junit.jupiter.api.Assertions.assertFalse(b.isHasData());
        assertEquals(0.0, b.getHitRate());
    }

    @Test
    void failReasons_返回两种固定文案的计数与顺序() {
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("reason", "下载超过 24 小时仍未完成，判定为僵尸种子", "count", 12L),
                row("reason", "下载器中已找不到该种子（可能被删除或元数据解析失败）", "count", 5L)));

        List<com.osr.openliststrm.pt.stats.dto.PtStatsFailReasonDTO> result = service().failReasons(30);

        assertEquals(2, result.size());
        assertEquals("下载超过 24 小时仍未完成，判定为僵尸种子", result.get(0).getReason());
        assertEquals(12L, result.get(0).getCount());
        assertEquals("下载器中已找不到该种子（可能被删除或元数据解析失败）", result.get(1).getReason());
        assertEquals(5L, result.get(1).getCount());
    }

    @Test
    void topSubscriptions_limit生效且订阅已删除时兜底展示() {
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("sub_id", 10, "download_count", 8L, "completed_count", 6L, "failed_count", 1L),
                row("sub_id", 11, "download_count", 3L, "completed_count", 3L, "failed_count", 0L)));
        com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus sub10 =
                new com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus();
        sub10.setId(10);
        sub10.setTitle("怪奇物语");
        sub10.setSeason(4);
        sub10.setMediaType("TV");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub10));

        List<com.osr.openliststrm.pt.stats.dto.PtStatsActiveSubscriptionDTO> result =
                service().topSubscriptions(30, 2);

        assertEquals(2, result.size());
        assertEquals(10, result.get(0).getSubId());
        assertEquals("怪奇物语", result.get(0).getTitle());
        assertEquals(4, result.get(0).getSeason());
        assertEquals("TV", result.get(0).getMediaType());
        assertEquals(8L, result.get(0).getDownloadCount());

        assertEquals(11, result.get(1).getSubId());
        assertEquals("（订阅已删除）", result.get(1).getTitle());
        org.junit.jupiter.api.Assertions.assertNull(result.get(1).getSeason());
        org.junit.jupiter.api.Assertions.assertNull(result.get(1).getMediaType());
        org.junit.jupiter.api.Assertions.assertNull(result.get(1).getLastMatchTime());
    }
}
