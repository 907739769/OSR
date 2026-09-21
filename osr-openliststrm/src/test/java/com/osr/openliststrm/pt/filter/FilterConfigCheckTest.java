package com.osr.openliststrm.pt.filter;

import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterConfigCheckTest {

    private PtFilterConfigPlus valid() {
        PtFilterConfigPlus c = new PtFilterConfigPlus();
        c.setMinSeeders(1);
        c.setMinSize(0L);
        c.setMaxSize(0L);
        c.setPreferredSize(0L);
        c.setSortPriority("RESOLUTION,SEEDERS");
        return c;
    }

    @Test
    void 合法配置_没有错误() {
        assertTrue(FilterConfigCheck.errors(valid()).isEmpty());
    }

    @Test
    void 体积下限大于上限_拒绝() {
        // 存进去的话所有种子都会被淘汰，而界面上什么都看不出来
        PtFilterConfigPlus c = valid();
        c.setMinSize(5L << 30);
        c.setMaxSize(2L << 30);
        assertTrue(FilterConfigCheck.errors(c).stream().anyMatch(e -> e.contains("体积下限大于")));
    }

    @Test
    void 只设下限或只设上限_不算冲突() {
        PtFilterConfigPlus c = valid();
        c.setMinSize(5L << 30);
        assertTrue(FilterConfigCheck.errors(c).isEmpty());
    }

    @Test
    void 做种数为空或为负_拒绝() {
        PtFilterConfigPlus c = valid();
        c.setMinSeeders(null);
        assertTrue(FilterConfigCheck.errors(c).stream().anyMatch(e -> e.contains("最低做种数")));
        c.setMinSeeders(-1);
        assertTrue(FilterConfigCheck.errors(c).stream().anyMatch(e -> e.contains("最低做种数")));
    }

    @Test
    void 无法识别的排序维度_拒绝() {
        PtFilterConfigPlus c = valid();
        c.setSortPriority("RESOLUTION,SEEDER");
        assertTrue(FilterConfigCheck.errors(c).stream().anyMatch(e -> e.contains("SEEDER")));
    }
}
