package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;

import java.util.List;

/**
 * <p>
 * PT 订阅 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
public interface IPtSubscriptionPlusService extends IService<PtSubscriptionPlus> {

    /**
     * 查询全部处于订阅中(ACTIVE)的订阅。RSS 轮询只匹配这些。
     */
    List<PtSubscriptionPlus> listActive();

    /**
     * 查询有缺集的 ACTIVE 订阅（至少有一集 MISSING 或 IN_FLIGHT），
     * 供 {@link com.osr.openliststrm.pt.task.LibrarySyncTask} 对账使用。
     * 比 {@link #listActive()} 更精准：跳过全部已入库的 ACTIVE 订阅，
     * 减少不必要的 TMDb/Emby API 调用。
     */
    List<PtSubscriptionPlus> listActiveWithMissing();

    /**
     * 定期自动补搜的候选：ACTIVE + 开着 {@code auto_search} + 至少有一集处于 MISSING。
     * <p>
     * 与 {@link #listActiveWithMissing()} 的差别是<b>不含 IN_FLIGHT</b>——那是"已经在下了"，
     * 补搜只处理 MISSING，把它们捞回来只会让 {@code searchAndPushMissing} 白查一次集表。
     * </p>
     * <p>
     * 三个条件都推到 SQL 而不是拉全部 ACTIVE 再在内存里筛：追完的老剧长期留在 ACTIVE 是常态，
     * 每轮为它们各查一次集表纯属浪费。到期判断留在 Java 侧——它要读全局周期配置，
     * 还要叠加落空退避与按 id 的抖动（见 {@code AutoSearchService#isDue}）。
     * </p>
     */
    List<PtSubscriptionPlus> listAutoSearchCandidates();

    /**
     * 只更新自动补搜的落空状态两列（连续落空次数、淘汰原因指纹），<b>不碰实体上的任何其它字段</b>。
     * <p>
     * 不能用 {@code updateById(sub)} 代替，这是踩过的坑：MyBatis-Plus 默认的
     * {@code FieldStrategy.NOT_NULL} 会把实体上所有非 null 字段一并写回，而
     * {@code AutoSearchService} 手里那份订阅是<b>本轮开始时</b>查出来的，
     * 它的 {@code last_search_time} 早已被 {@code SearchSupplementService#searchAndPushMissing}
     * 在本次搜索末尾更新过。用它 updateById 会把刚写入的新时间覆盖回旧值，于是这条订阅
     * 永远处于"已到期"状态、每 30 分钟心跳都重搜一遍——而通知那侧有去重，用户完全看不出来，
     * 只是索引器被默默打了几十倍的请求，落空退避也会因此彻底失效。
     * </p>
     * <p>
     * 指纹传 {@code null} 表示清空（命中后重置），这也是本方法必须走
     * {@code LambdaUpdateWrapper} 而不是传一个只填了两列的实体的原因——NOT_NULL 策略下
     * null 字段会被直接跳过，重置写不进去。
     * </p>
     */
    void updateAutoSearchMissState(Integer subId, int missStreak, String rejectSign);
}
