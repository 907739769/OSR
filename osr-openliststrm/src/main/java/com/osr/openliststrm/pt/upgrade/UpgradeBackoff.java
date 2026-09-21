package com.osr.openliststrm.pt.upgrade;

import java.util.Date;

/**
 * 洗版搜索的指数退避：一集连续 n 次没搜到更好的版本，下次搜索要等「扫描周期 × 2^n」，
 * 封顶 7 天（扫描周期本身超过 7 天时以周期为准）。纯函数。
 * <p>
 * 没有退避的话，已经搜过十几次都没有 4K 的老剧，每 6 小时照样对全部索引器再搜一遍；
 * 而更好的版本出现的节奏是按周、按月计的。一旦搜到并推送成功，计数清零。
 * </p>
 *
 * @author Jack
 */
public final class UpgradeBackoff {

    /** 封顶 7 天 */
    static final long MAX_WAIT_HOURS = 168;

    /** 指数封顶，防溢出；2^6 × 6h 已经超过 7 天 */
    private static final int MAX_EXPONENT = 6;

    /**
     * 判到期时让出一小时的余量。扫描任务每小时心跳一次、按 {@code 上次开始 + 周期} 触发，
     * 而 searched_at 记的是本轮里搜到这一集的时刻（比本轮开始晚几秒到几分钟）——不留余量的话
     * 「只差几秒」会让一集白白错过一整个周期。
     */
    private static final long SLACK_MILLIS = 3600_000L;

    private UpgradeBackoff() {
    }

    /** 这一集下一次洗版搜索要等多少小时 */
    public static long waitHours(Integer missCount, int intervalHours) {
        int interval = Math.max(1, intervalHours);
        int exponent = Math.min(Math.max(0, missCount == null ? 0 : missCount), MAX_EXPONENT);
        long wait = (long) interval << exponent;
        return Math.min(wait, Math.max(MAX_WAIT_HOURS, interval));
    }

    /** 这一集本轮是否该搜。从没搜过的恒为 true */
    public static boolean isDue(Date searchedAt, Integer missCount, int intervalHours, long nowMillis) {
        if (searchedAt == null) {
            return true;
        }
        long waitMillis = waitHours(missCount, intervalHours) * 3600_000L - SLACK_MILLIS;
        return nowMillis - searchedAt.getTime() >= waitMillis;
    }
}
