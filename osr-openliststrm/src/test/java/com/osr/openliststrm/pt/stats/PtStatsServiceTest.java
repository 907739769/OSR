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

    /** 管理员范围：这几条用例关心的是聚合本身，归属过滤另有用例 */
    private static final PtStatsScope ALL = PtStatsScope.ALL;

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void overview_下载记录为空_返回全0而不抛异常() {
        when(subscriptionService.count(any(Wrapper.class))).thenReturn(0L, 0L);
        when(downloadRecordService.count(any(Wrapper.class))).thenReturn(0L);
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of());

        PtStatsOverviewDTO dto = service().overview(null, ALL);

        assertEquals(0L, dto.getTotalSubscriptions());
        assertEquals(0L, dto.getActiveSubscriptions());
        assertEquals(0L, dto.getTotalDownloadRecords());
        assertEquals(0L, dto.getCompletedCount());
        assertEquals(0L, dto.getFailedCount());
        assertEquals(0.0, dto.getSuccessRate());
        assertEquals(0.0, dto.getAvgDurationMinutes());
        assertEquals(0L, dto.getHrViolatedCount());
    }

    @Test
    void overview_成功率分母是完成加未接替失败_不含在途记录() {
        when(subscriptionService.count(any(Wrapper.class))).thenReturn(20L, 15L);
        // count 依次是：推送总数 / 未接替失败数 / 可能已 H&R 数
        when(downloadRecordService.count(any(Wrapper.class))).thenReturn(100L, 20L, 3L);
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("cnt", 60L, "avg_duration_minutes", 45.5)));

        PtStatsOverviewDTO dto = service().overview(30, ALL);

        assertEquals(20L, dto.getTotalSubscriptions());
        assertEquals(15L, dto.getActiveSubscriptions());
        assertEquals(100L, dto.getTotalDownloadRecords());
        assertEquals(60L, dto.getCompletedCount());
        assertEquals(20L, dto.getFailedCount());
        // 60 / (60 + 20)，另外 20 条还在下载的不进分母；旧口径 60/100 会把活跃下载算成失败
        assertEquals(75.0, dto.getSuccessRate());
        assertEquals(45.5, dto.getAvgDurationMinutes());
        assertEquals(3L, dto.getHrViolatedCount());
        assertEquals(30, dto.getRangeDays());
    }

    @Test
    void overview_失败数只统计未被接替的失败() {
        when(subscriptionService.count(any(Wrapper.class))).thenReturn(0L, 0L);
        when(downloadRecordService.count(any(Wrapper.class))).thenReturn(0L);
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of());

        service().overview(7, ALL);

        org.mockito.ArgumentCaptor<Wrapper> captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(downloadRecordService, org.mockito.Mockito.times(3)).count(captor.capture());
        String failedSql = captor.getAllValues().get(1).getCustomSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(failedSql.contains("NOT EXISTS"),
                "失败数必须排除已被后续推送接替的记录：" + failedSql);
        org.junit.jupiter.api.Assertions.assertTrue(failedSql.contains("update_time"),
                "带了统计范围时失败数必须按失败日期落区间：" + failedSql);
    }

    @Test
    void trend_三条线各按自己的日期列分组且缺失日期补0() {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String todayKey = today.format(fmt);
        String yesterdayKey = today.minusDays(1).format(fmt);
        // 三次 listMaps 依次是：推送(pushed_time) / 完成(completed_time) / 失败(update_time)
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(
                List.of(row("day", yesterdayKey, "cnt", 3L)),
                List.of(row("day", todayKey, "cnt", 2L, "avg_duration_minutes", 30.0)),
                List.of(row("day", todayKey, "cnt", 1L)));

        List<com.osr.openliststrm.pt.stats.dto.PtStatsTrendPointDTO> points = service().trend(7, ALL);

        assertEquals(7, points.size());
        var last = points.get(points.size() - 1);
        assertEquals(todayKey, last.getDate());
        // 昨天推送、今天完成：完成数必须落在今天这一格，而不是跟着推送日期跑到昨天去
        assertEquals(0L, last.getPushedCount());
        assertEquals(2L, last.getCompletedCount());
        assertEquals(1L, last.getFailedCount());
        assertEquals(30.0, last.getAvgDurationMinutes());

        var yesterday = points.get(points.size() - 2);
        assertEquals(3L, yesterday.getPushedCount());
        assertEquals(0L, yesterday.getCompletedCount());
        org.junit.jupiter.api.Assertions.assertNull(yesterday.getAvgDurationMinutes());

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

        List<com.osr.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO> result = service().indexerHitRate(ALL);

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

    /**
     * 按码聚合是这张饼图能读的前提：fail_reason 原文里嵌着集号与超时小时数，
     * 按原文分组会碎成一堆计数为 1 的扇形。
     */
    @Test
    void failReasons_按码聚合并给出中文标签() {
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("code", "ZOMBIE_TIMEOUT", "cnt", 12L),
                row("code", "NO_TARGET_EPISODE", "cnt", 5L)));

        List<com.osr.openliststrm.pt.stats.dto.PtStatsFailReasonDTO> result = service().failReasons(30, ALL);

        assertEquals(2, result.size());
        assertEquals("ZOMBIE_TIMEOUT", result.get(0).getCode());
        assertEquals("下载超时", result.get(0).getReason());
        assertEquals(12L, result.get(0).getCount());
        assertEquals("NO_TARGET_EPISODE", result.get(1).getCode());
        assertEquals("无目标集", result.get(1).getReason());
        assertEquals(5L, result.get(1).getCount());
    }

    /** 历史记录 fail_reason_code 为空时归到 OTHER，不能在图上画出一个叫 "null" 的扇形 */
    @Test
    void failReasons_历史未分类记录归为其他原因() {
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("code", null, "cnt", 4L)));

        List<com.osr.openliststrm.pt.stats.dto.PtStatsFailReasonDTO> result = service().failReasons(30, ALL);

        assertEquals(1, result.size());
        assertEquals("OTHER", result.get(0).getCode());
        assertEquals("其他原因", result.get(0).getReason());
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
                service().topSubscriptions(30, 2, ALL);

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

    /**
     * 归属过滤要真的落到 SQL 上。删掉 scoped* 里的 inSql 不会让任何功能报错，
     * 只是别人的订阅重新出现在统计里，所以这条断言盯的是 wrapper 本身。
     */
    @Test
    void 受限范围_下载记录查询带上可见订阅子查询() {
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of());

        service().failReasons(30, PtStatsScope.of(false, 9L));

        org.mockito.ArgumentCaptor<Wrapper> captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(downloadRecordService).listMaps(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("sub_id IN"), sql);
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("owner_user_id = 9"), sql);
    }

    @Test
    void 管理员范围_不加任何归属条件() {
        when(searchLogService.listMaps(any(Wrapper.class))).thenReturn(List.of());

        service().rejectReasons(PtStatsScope.ALL);

        org.mockito.ArgumentCaptor<Wrapper> captor = org.mockito.ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(searchLogService).listMaps(captor.capture());
        org.junit.jupiter.api.Assertions.assertFalse(captor.getValue().getSqlSegment().contains("owner_user_id"));
    }

    /** 取不到当前用户时只放行公共订阅，绝不能拼出恒为 unknown 的 `owner_user_id = NULL` */
    @Test
    void 取不到用户_只放行无归属的公共订阅() {
        String sql = PtStatsScope.of(false, null).visibleSubIdSql();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("owner_user_id IS NULL"), sql);
        org.junit.jupiter.api.Assertions.assertFalse(sql.contains("= NULL"), sql);
    }
}
