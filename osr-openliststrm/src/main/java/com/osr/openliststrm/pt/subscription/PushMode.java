package com.osr.openliststrm.pt.subscription;

/**
 * {@code SubscriptionEngine#handleGroup} 的推送语义。
 * <p>
 * 两种模式共用同一条「过滤择优 → 占位 → 落库 → 推送 → 落账」的主干，只在少数几个点上分叉。
 * 单独抽枚举而不是复制一份平行的洗版链路：主干里那些反复踩过坑的细节
 * （唯一索引冲突回滚、并发占位、下载器负载均衡的原子性、推送失败的对称回滚）
 * 只该有一份实现，复制出去必然随时间漂移，而这些恰恰是最难查的问题。
 * </p>
 *
 * @author Jack
 */
public enum PushMode {

    /**
     * 补缺集：目标是 MISSING 的集，占位后转 IN_FLIGHT，失败退回 MISSING。
     * 这是 RSS 轮询与搜索补集的默认语义。
     */
    FILL_MISSING,

    /**
     * 洗版：目标是<b>已入库</b>的某一集，占位后转 UPGRADING，失败退回 IN_LIBRARY。
     * <p>
     * 与补缺集的实质差异：
     * <ul>
     *   <li>目标集只有调用方指定的那一集，且必须处于 IN_LIBRARY。不做季包/区间展开——
     *       洗版是「把这一集换个更好的版本」，用一个季包去覆盖会连带动到那些没打算升级的集</li>
     *   <li>失败回滚到 IN_LIBRARY 而不是 MISSING：旧文件一直都在，退成 MISSING 会让 RSS 从头重下一遍</li>
     * </ul>
     * </p>
     * <p>
     * 「洗版不能把缺集堵在门外」这条约束不在这里实现，而是由 {@code UpgradeScanService} 在发起前
     * 用 {@code pt_upgrade_config.max_concurrent} 限制全局在途洗版数——那是一个直接、可解释的
     * 总量闸门，比在下载器容量判定里塞一个模式相关的折扣阈值更容易说清楚。
     * </p>
     */
    UPGRADE;

    public boolean isUpgrade() {
        return this == UPGRADE;
    }
}
