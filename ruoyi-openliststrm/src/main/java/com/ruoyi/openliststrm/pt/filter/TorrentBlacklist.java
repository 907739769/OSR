package com.ruoyi.openliststrm.pt.filter;

import com.ruoyi.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 过滤引擎的黑名单输入：一次性从库里查好的全量规则，按类型归一化拆成两个集合。
 * 不可变，与 {@link FilterCriteria} 同一角色——引擎本身不读数据库，生效的黑名单
 * 由调用方（{@link com.ruoyi.openliststrm.pt.subscription.SubscriptionEngine}）
 * 一次性查好传入。
 *
 * @param guidHashes         GUID 黑名单命中集合（已是 SHA-256 哈希，无需再归一化大小写）
 * @param releaseGroupsUpper 发布组黑名单命中集合，统一大写
 * @author Jack
 */
public record TorrentBlacklist(Set<String> guidHashes, Set<String> releaseGroupsUpper) {

    /** 未配置任何黑名单时使用，供旧的两参 {@code evaluate}/{@code filter} 签名内部转调 */
    public static final TorrentBlacklist EMPTY = new TorrentBlacklist(Set.of(), Set.of());

    public TorrentBlacklist {
        guidHashes = guidHashes == null ? Set.of() : Set.copyOf(guidHashes);
        releaseGroupsUpper = releaseGroupsUpper == null ? Set.of() : Set.copyOf(releaseGroupsUpper);
    }

    /**
     * 从数据库全量规则构建。{@code null}/空列表都返回等价于 {@link #EMPTY} 的空集合。
     */
    public static TorrentBlacklist from(List<PtTorrentBlacklistPlus> rules) {
        if (rules == null || rules.isEmpty()) {
            return EMPTY;
        }
        Set<String> guids = new HashSet<>();
        Set<String> groups = new HashSet<>();
        for (PtTorrentBlacklistPlus rule : rules) {
            if (rule == null || rule.getValue() == null) {
                continue;
            }
            if (PtTorrentBlacklistPlus.TYPE_GUID.equals(rule.getType())) {
                guids.add(rule.getValue());
            } else if (PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP.equals(rule.getType())) {
                groups.add(rule.getValue().toUpperCase(Locale.ROOT));
            }
        }
        return new TorrentBlacklist(guids, groups);
    }
}
