package com.osr.openliststrm.pt.health;

import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.health.dto.EpisodeHealthItem;

import java.util.List;

/**
 * 一条订阅的体检结果（服务内部形态，带着订阅实体）。
 * <p>
 * 与对外的 {@code SubscriptionHealthItem} 分开：通知那侧要读 {@code owner_user_id} 做定向投递、
 * 要读/写落空指纹，而这些字段不该随接口下发给前端。让两边共用一个 DTO 的话，
 * 要么把归属人泄露到页面上，要么让通知去反查一次订阅。
 * </p>
 *
 * @param subscription 订阅实体
 * @param episodes     有问题的集，按集号升序
 *
 * @author Jack
 */
public record SubscriptionHealth(PtSubscriptionPlus subscription, List<EpisodeHealthItem> episodes) {

    /** 取某一分档下的集号，按升序。用于通知文案与统计 */
    public List<Integer> episodesIn(EpisodeHealthBucket bucket) {
        return episodes.stream()
                .filter(e -> bucket.name().equals(e.bucket()))
                .map(EpisodeHealthItem::episode)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    /** 名下最大已播出天数；全都没有播出日期时返回 null（不是 0，见 {@link EpisodeHealthItem}） */
    public Integer maxOverdueDays() {
        return episodes.stream()
                .map(EpisodeHealthItem::overdueDays)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }
}
