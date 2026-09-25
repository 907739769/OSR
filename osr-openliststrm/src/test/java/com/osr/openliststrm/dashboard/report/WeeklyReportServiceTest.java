package com.osr.openliststrm.dashboard.report;

import com.osr.openliststrm.dashboard.stats.DashboardStatsService;
import com.osr.openliststrm.dashboard.stats.dto.DashboardTrendPointDTO;
import com.osr.openliststrm.openai.AiChatService;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import com.osr.openliststrm.pt.stats.PtStatsService;
import com.osr.openliststrm.pt.stats.dto.PtStatsActiveSubscriptionDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsFailReasonDTO;
import com.osr.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 周报：数字来自现成统计口径、动态内容要转义、AI 只追加点评且失败时照发。
 */
class WeeklyReportServiceTest {

    private DashboardStatsService dashboard;
    private PtStatsService pt;
    private AiChatService ai;
    private WeeklyReportService service;

    @BeforeEach
    void setUp() {
        dashboard = mock(DashboardStatsService.class);
        pt = mock(PtStatsService.class);
        ai = mock(AiChatService.class);
        service = new WeeklyReportService(dashboard, pt, ai);

        when(dashboard.trend(eq("copy"), anyInt())).thenReturn(List.of(point(10, 9, 1), point(5, 5, 0)));
        when(dashboard.trend(eq("strm"), anyInt())).thenReturn(List.of());
        when(dashboard.trend(eq("rename"), anyInt())).thenReturn(List.of(point(3, 3, 0)));
        PtStatsOverviewDTO overview = new PtStatsOverviewDTO();
        overview.setTotalDownloadRecords(20);
        overview.setCompletedCount(15);
        overview.setFailedCount(2);
        overview.setSuccessRate(88.2);
        overview.setActiveSubscriptions(8);
        overview.setTotalSubscriptions(12);
        when(pt.overview(any(), any(PtStatsScope.class))).thenReturn(overview);
        PtStatsFailReasonDTO reason = new PtStatsFailReasonDTO();
        reason.setReason("僵尸种");
        reason.setCount(2);
        when(pt.failReasons(anyInt(), any())).thenReturn(List.of(reason));
        PtStatsActiveSubscriptionDTO sub = new PtStatsActiveSubscriptionDTO();
        sub.setTitle("Tom & Jerry");
        sub.setCompletedCount(6);
        when(pt.topSubscriptions(anyInt(), anyInt(), any())).thenReturn(List.of(sub));
    }

    private static DashboardTrendPointDTO point(long total, long success, long failed) {
        DashboardTrendPointDTO p = new DashboardTrendPointDTO();
        p.setTotalCount(total);
        p.setSuccessCount(success);
        p.setFailedCount(failed);
        return p;
    }

    @Test
    void 没配AI时只有数字段落_动态内容转义() {
        when(ai.available()).thenReturn(false);

        String text = service.build();

        assertTrue(text.contains("同步：成功 14 · 失败 1"), text);
        assertTrue(text.contains("STRM：无记录"), text);
        assertTrue(text.contains("成功率 88.2%"), text);
        assertTrue(text.contains("僵尸种 2"), text);
        assertTrue(text.contains("《Tom &amp; Jerry》6 个"), text);
        assertFalse(text.contains("AI 点评"));
    }

    @Test
    void AI点评追加在末尾_失败时照发() {
        when(ai.available()).thenReturn(true);
        when(ai.chat(anyString(), anyString(), anyInt())).thenReturn("一切正常 <b>");
        String text = service.build();
        assertTrue(text.endsWith("<b>AI 点评</b>\n一切正常 &lt;b&gt;"), text);

        when(ai.chat(anyString(), anyString(), anyInt())).thenReturn(null);
        assertFalse(service.build().contains("AI 点评"));
    }
}
