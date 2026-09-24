package com.osr.openliststrm.pt.task;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 自动补搜每轮有预算，顺序决定谁先补到：最近在看的剧排前面，读不到观看状态的保持原序排后面。
 */
class AutoSearchWatchOrderTest {

    private static PtSubscriptionPlus sub(int id, Long watchedAt) {
        PtSubscriptionPlus s = new PtSubscriptionPlus();
        s.setId(id);
        s.setLastWatchedTime(watchedAt == null ? null : new Date(watchedAt));
        return s;
    }

    @Test
    void 最近在看的排前面_没看过的保持原序() {
        List<PtSubscriptionPlus> ordered = AutoSearchService.inWatchOrder(List.of(
                sub(1, null), sub(2, 100L), sub(3, null), sub(4, 300L), sub(5, 200L)));

        assertEquals(List.of(4, 5, 2, 1, 3), ordered.stream().map(PtSubscriptionPlus::getId).toList());
    }
}
