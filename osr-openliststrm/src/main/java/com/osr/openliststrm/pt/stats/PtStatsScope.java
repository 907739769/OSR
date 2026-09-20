package com.osr.openliststrm.pt.stats;

/**
 * 统计面板的可见范围。
 * <p>
 * 订阅本身是按归属隔离的（见 {@code PtSubscriptionRestController#denyIfInaccessible}），
 * 而统计面板此前是全站裸奔的：任何登录用户都能看到全站订阅总数，以及 Top 活跃订阅里
 * <b>别人的剧名</b>——点进去又会被订阅页的归属判据挡住，等于给出一个"看得见摸不着"的
 * 泄漏面。这个值对象把同一条判据搬到统计侧：管理员看全站，其余用户只看自己的订阅
 * 与无归属的公共订阅（{@code owner_user_id IS NULL}，即归属列上线前建的历史订阅）。
 * </p>
 *
 * @param all    是否可见全站数据（管理员）
 * @param userId 受限时的当前用户 id，取不到时为 null（只放行公共订阅）
 * @author Jack
 */
public record PtStatsScope(boolean all, Long userId) {

    /** 管理员：不加任何归属条件 */
    public static final PtStatsScope ALL = new PtStatsScope(true, null);

    /** 按当前登录用户构造：{@code admin} 为真时等同 {@link #ALL} */
    public static PtStatsScope of(boolean admin, Long userId) {
        return admin ? ALL : new PtStatsScope(false, userId);
    }

    /**
     * 可见订阅 id 的子查询，供 {@code sub_id} 列做 {@code IN (...)}。{@link #all} 时返回 null。
     * <p>
     * 用子查询而不是先查一把 id 列表再 {@code in(...)}：订阅上百条时后者会拼出一条
     * 上百个参数的 SQL，而这里的两张事实表（下载记录、匹配日志）本来就要扫。
     * {@code userId} 是 JWT 里解析出的 Long，拼进 SQL 无注入面（与
     * {@code SearchLogService} 里 {@code last("limit " + n)} 同一种写法）。
     * </p>
     */
    public String visibleSubIdSql() {
        if (all) {
            return null;
        }
        return userId == null
                ? "SELECT id FROM pt_subscription WHERE owner_user_id IS NULL"
                : "SELECT id FROM pt_subscription WHERE owner_user_id = " + userId + " OR owner_user_id IS NULL";
    }
}
