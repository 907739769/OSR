package com.osr.openliststrm.pt.task;

import com.osr.openliststrm.pt.subscription.SubscriptionMatcher;

/**
 * 「还没被后续推送接替的失败记录」的 SQL 判据，供统计与列表筛选共用。
 * <p>
 * 与 {@code DownloadRecordAdminService#markSuperseded / covers} 是<b>同一条规则的两种写法</b>：
 * 同一订阅、id 更大、覆盖了同一集的下载记录即为接替者；季包只被后续季包接替，单集被后续单集 / 区间 /
 * 季包覆盖都算。那边是内存判定（给本页卡片打标记），这边是 NOT EXISTS 子查询（给计数与筛选用），
 * <b>改一边必须改另一边</b>——漂移的表现是「列表里标着已接替，统计里却还算失败」，不报错，只是数字对不上。
 * </p>
 * <p>
 * 为什么统计要排除接替掉的失败：重试或自动补搜成功时是新建一条记录，失败那条原样留着。
 * 全部算进失败数的话，一集失败一次、补上一次，成功率就被记成 50%，而用户关心的是「现在还有多少没着落」。
 * </p>
 *
 * @author Jack
 */
public final class UnresolvedFailureSql {

    private UnresolvedFailureSql() {
    }

    /**
     * 直接用于 {@code wrapper.apply(...)} 的条件片段。外层表<b>不能起别名</b>——子查询按表名
     * {@code pt_download_record} 引用外层行；外层还要自己加 {@code state='FAILED'}。
     */
    public static final String NOT_SUPERSEDED = "NOT EXISTS (SELECT 1 FROM pt_download_record n"
            + " WHERE n.sub_id = pt_download_record.sub_id AND n.id > pt_download_record.id"
            + " AND (n.episode = " + SubscriptionMatcher.SEASON_PACK
            + " OR (pt_download_record.episode <> " + SubscriptionMatcher.SEASON_PACK
            + " AND n.episode <= pt_download_record.episode"
            + " AND pt_download_record.episode <= GREATEST(COALESCE(n.episode_end, n.episode), n.episode))))";
}
