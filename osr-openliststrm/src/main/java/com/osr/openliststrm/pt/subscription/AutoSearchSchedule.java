package com.osr.openliststrm.pt.subscription;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import java.time.LocalDate;
import java.time.ZoneId;

/** 自动任务与诊断页共用的补搜周期；不发请求、不修改状态。 */
public final class AutoSearchSchedule {
    private AutoSearchSchedule() {}

    public static int intervalHours(Integer configured) {
        return configured == null || configured <= 0 ? 24 : configured;
    }

    public static boolean recentMissing(PtSubscriptionEpisodePlus ep, LocalDate today) {
        if (!"MISSING".equals(ep.getState()) || ep.getAirDate() == null) return false;
        LocalDate air = java.time.Instant.ofEpochMilli(ep.getAirDate().getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
        return !air.isAfter(today) && !air.isBefore(today.minusDays(6));
    }

    public static long intervalMillis(PtSubscriptionPlus sub, int streak, int hours, boolean recent) {
        long base = intervalHours(hours) * 3600_000L;
        // 近期播出且仍缺失：最多 12 小时一次基础周期，不叠加长期落空退避。
        long backed = recent ? Math.min(base, 12 * 3600_000L)
                : streak <= 0 ? base : Math.min(base * (1L << Math.min(streak, 3)), 7 * 24 * 3600_000L);
        long id = sub.getId() == null ? 0 : Math.abs((long) sub.getId());
        // 包含抖动在内封顶一周。
        return Math.min(backed + backed / 100 * (id % 21), 7 * 24 * 3600_000L);
    }
}
