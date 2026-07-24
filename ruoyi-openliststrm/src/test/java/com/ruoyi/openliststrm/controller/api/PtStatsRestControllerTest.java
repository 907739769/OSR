package com.ruoyi.openliststrm.controller.api;

import com.ruoyi.common.core.domain.Result;
import com.ruoyi.openliststrm.pt.stats.PtStatsService;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsTrendPointDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PtStatsRestControllerTest {

    @Mock
    private PtStatsService statsService;

    private PtStatsRestController controller() {
        return new PtStatsRestController(statsService);
    }

    @Test
    void normalizeDays_合法值原样返回() {
        assertEquals(7, PtStatsRestController.normalizeDays(7));
        assertEquals(30, PtStatsRestController.normalizeDays(30));
        assertEquals(90, PtStatsRestController.normalizeDays(90));
    }

    @Test
    void normalizeDays_非法值或null回退到30() {
        assertEquals(30, PtStatsRestController.normalizeDays(null));
        assertEquals(30, PtStatsRestController.normalizeDays(15));
        assertEquals(30, PtStatsRestController.normalizeDays(-1));
    }

    @Test
    void normalizeLimit_超过50截断() {
        assertEquals(50, PtStatsRestController.normalizeLimit(100));
        assertEquals(50, PtStatsRestController.normalizeLimit(50));
    }

    @Test
    void normalizeLimit_null或非正数回退到默认10() {
        assertEquals(10, PtStatsRestController.normalizeLimit(null));
        assertEquals(10, PtStatsRestController.normalizeLimit(0));
        assertEquals(10, PtStatsRestController.normalizeLimit(-5));
    }

    @Test
    void trend_非法days参数按30转调service() {
        when(statsService.trend(30)).thenReturn(List.of());

        Result<List<PtStatsTrendPointDTO>> result = controller().trend(999);

        assertEquals(200, result.getCode());
        verify(statsService).trend(30);
    }

    @Test
    void topSubscriptions_limit超过50被截断转调service() {
        when(statsService.topSubscriptions(30, 50)).thenReturn(List.of());

        controller().topSubscriptions(null, 999);

        verify(statsService).topSubscriptions(30, 50);
    }

    @Test
    void failReasons_合法days原样转调service() {
        when(statsService.failReasons(7)).thenReturn(List.of());

        controller().failReasons(7);

        verify(statsService).failReasons(7);
    }

    @Test
    void overview_直接转调service() {
        when(statsService.overview()).thenReturn(new com.ruoyi.openliststrm.pt.stats.dto.PtStatsOverviewDTO());

        Result<com.ruoyi.openliststrm.pt.stats.dto.PtStatsOverviewDTO> result = controller().overview();

        assertEquals(200, result.getCode());
    }

    @Test
    void indexerHitRate_直接转调service() {
        when(statsService.indexerHitRate()).thenReturn(List.of());

        Result<List<com.ruoyi.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO>> result = controller().indexerHitRate();

        assertEquals(200, result.getCode());
    }
}
