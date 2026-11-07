package com.osr.openliststrm.pt.subscription;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class AutoSearchScheduleTest {
    @Test
    void 近期日期边界_不把未播或未知日期当作近期缺集() {
        LocalDate today = LocalDate.of(2026, 11, 7);
        var ep = new PtSubscriptionEpisodePlus();
        ep.setState("MISSING");
        assertFalse(AutoSearchSchedule.recentMissing(ep, today));
        ep.setAirDate(java.sql.Date.valueOf(today.plusDays(1)));
        assertFalse(AutoSearchSchedule.recentMissing(ep, today));
        ep.setAirDate(java.sql.Date.valueOf(today.minusDays(6)));
        assertTrue(AutoSearchSchedule.recentMissing(ep, today));
        ep.setAirDate(java.sql.Date.valueOf(today.minusDays(7)));
        assertFalse(AutoSearchSchedule.recentMissing(ep, today));
    }

    @Test
    void 老资源退避含抖动也不超过一周_近期资源尊重较短的用户周期() {
        var sub = new PtSubscriptionPlus();
        sub.setId(20);
        assertEquals(7 * 24 * 3600_000L, AutoSearchSchedule.intervalMillis(sub, 99, 24, false));
        sub.setId(21);
        assertEquals(6 * 3600_000L, AutoSearchSchedule.intervalMillis(sub, 99, 6, true));
        assertEquals(12 * 3600_000L, AutoSearchSchedule.intervalMillis(sub, 99, 24, true));
    }
}
