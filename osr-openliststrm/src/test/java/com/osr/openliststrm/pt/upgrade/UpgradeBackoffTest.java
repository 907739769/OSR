package com.osr.openliststrm.pt.upgrade;

import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeBackoffTest {

    private static final long HOUR = 3600_000L;

    @Test
    void 等待时长按落空次数翻倍_封顶7天() {
        assertEquals(6, UpgradeBackoff.waitHours(0, 6));
        assertEquals(12, UpgradeBackoff.waitHours(1, 6));
        assertEquals(96, UpgradeBackoff.waitHours(4, 6));
        assertEquals(168, UpgradeBackoff.waitHours(5, 6));
        assertEquals(168, UpgradeBackoff.waitHours(100, 6));
        assertEquals(6, UpgradeBackoff.waitHours(null, 6));
    }

    @Test
    void 周期本身超过7天时以周期为准() {
        // 否则封顶反而会让退避后的间隔比不退避还短
        assertEquals(200, UpgradeBackoff.waitHours(3, 200));
    }

    @Test
    void 从没搜过_恒到期() {
        assertTrue(UpgradeBackoff.isDue(null, 5, 6, System.currentTimeMillis()));
    }

    @Test
    void 只差几秒不算错过一个周期() {
        // 扫描按「上次开始 + 周期」触发，searched_at 记的是本轮里搜到这一集的时刻，
        // 比本轮开始晚几秒；不留余量的话这一集会白白错过一整个周期
        long now = System.currentTimeMillis();
        Date searched = new Date(now - 6 * HOUR + 30_000);
        assertTrue(UpgradeBackoff.isDue(searched, 0, 6, now));
    }

    @Test
    void 连续落空后退避期内不到期() {
        long now = System.currentTimeMillis();
        Date searched = new Date(now - 7 * HOUR);
        assertFalse(UpgradeBackoff.isDue(searched, 1, 6, now));
        assertTrue(UpgradeBackoff.isDue(searched, 0, 6, now));
    }
}
