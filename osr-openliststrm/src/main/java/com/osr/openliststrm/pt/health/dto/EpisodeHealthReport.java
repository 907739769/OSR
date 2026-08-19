package com.osr.openliststrm.pt.health.dto;

import java.util.List;
import java.util.Map;

/**
 * 一次缺集体检的完整结果。
 *
 * @param overdueDays      判定「逾期」用的阈值天数，回传给前端是为了让页面上的文案
 *                         （「播出超过 N 天仍未入库」）与后端配置一致，而不是前端写死一个 3
 * @param subscriptionCount 有问题的订阅数
 * @param episodeCount      有问题的集数
 * @param bucketCounts      各分档的集数，key 为 {@code EpisodeHealthBucket} 名
 * @param diagnosisCounts   各诊断的集数，key 为 {@code EpisodeHealthDiagnosis} 名
 * @param ignoredCount      被忽略的订阅数。<b>恒按全量算，与本次是否包含它们无关</b>——
 *                          前端要靠它渲染「显示已忽略(N)」这个入口，按当前视图算的话
 *                          没包含时它恒为 0，入口就永远不出现，忽略变成一个无法撤销的操作
 * @param subscriptions     明细，按最大已播出天数倒序
 *
 * @author Jack
 */
public record EpisodeHealthReport(int overdueDays, int subscriptionCount, int episodeCount,
                                  Map<String, Integer> bucketCounts, Map<String, Integer> diagnosisCounts,
                                  int ignoredCount, List<SubscriptionHealthItem> subscriptions) {

    /** 空结果。计数字段给 0 而不是 null，前端不必为「一切正常」写一条特判 */
    public static EpisodeHealthReport empty(int overdueDays) {
        return new EpisodeHealthReport(overdueDays, 0, 0, Map.of(), Map.of(), 0, List.of());
    }
}
