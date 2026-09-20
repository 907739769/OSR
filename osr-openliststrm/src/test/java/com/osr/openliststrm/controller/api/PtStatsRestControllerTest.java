package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.common.utils.CurrentUserService;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import com.osr.openliststrm.pt.stats.PtStatsService;
import com.osr.openliststrm.pt.stats.dto.PtStatsTrendPointDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PtStatsRestControllerTest {

    @Mock
    private PtStatsService statsService;

    @Mock
    private CurrentUserService currentUserService;

    private PtStatsRestController controller;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        controller = new PtStatsRestController(statsService);
        inject("currentUserService", currentUserService);
        when(currentUserService.getUserId()).thenReturn(9L);
    }

    /** 逐级向上找字段：currentUserService 声明在 BaseController 上，不在控制器自身 */
    private void inject(String fieldName, Object value) throws Exception {
        for (Class<?> type = controller.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(controller, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(fieldName);
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
        when(statsService.trend(anyInt(), any())).thenReturn(List.of());

        Result<List<PtStatsTrendPointDTO>> result = controller.trend(999);

        assertEquals(200, result.getCode());
        verify(statsService).trend(eq(30), any(PtStatsScope.class));
    }

    @Test
    void topSubscriptions_limit超过50被截断转调service() {
        when(statsService.topSubscriptions(anyInt(), anyInt(), any())).thenReturn(List.of());

        controller.topSubscriptions(null, 999);

        verify(statsService).topSubscriptions(eq(30), eq(50), any(PtStatsScope.class));
    }

    @Test
    void failReasons_合法days原样转调service() {
        when(statsService.failReasons(anyInt(), any())).thenReturn(List.of());

        controller.failReasons(7);

        verify(statsService).failReasons(eq(7), any(PtStatsScope.class));
    }

    @Test
    void overview_直接转调service() {
        when(statsService.overview(any())).thenReturn(new com.osr.openliststrm.pt.stats.dto.PtStatsOverviewDTO());

        Result<com.osr.openliststrm.pt.stats.dto.PtStatsOverviewDTO> result = controller.overview();

        assertEquals(200, result.getCode());
    }

    @Test
    void indexerHitRate_直接转调service() {
        when(statsService.indexerHitRate(any())).thenReturn(List.of());

        Result<List<com.osr.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO>> result = controller.indexerHitRate();

        assertEquals(200, result.getCode());
    }

    /**
     * 普通用户拿到的是受限范围。这条断言是归属隔离在统计侧唯一的守卫——
     * 把 scope() 改回恒定 ALL 不会让任何功能报错，只是别人的剧名重新出现在 Top 活跃订阅里。
     */
    @Test
    void 普通用户_scope限定到自己的订阅() {
        when(statsService.indexerHitRate(any())).thenReturn(List.of());

        controller.indexerHitRate();

        ArgumentCaptor<PtStatsScope> captor = ArgumentCaptor.forClass(PtStatsScope.class);
        verify(statsService).indexerHitRate(captor.capture());
        assertFalse(captor.getValue().all());
        assertEquals(9L, captor.getValue().userId());
    }

    @Test
    void 管理员_scope放行全站() {
        when(currentUserService.getUserId()).thenReturn(1L);
        when(statsService.indexerHitRate(any())).thenReturn(List.of());

        controller.indexerHitRate();

        ArgumentCaptor<PtStatsScope> captor = ArgumentCaptor.forClass(PtStatsScope.class);
        verify(statsService).indexerHitRate(captor.capture());
        assertTrue(captor.getValue().all());
    }
}
