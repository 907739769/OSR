package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;

import java.util.Date;
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

    /**
     * 只更新「逾期缺集」通知的两列（指纹、通知时间），<b>不碰实体上的任何其它字段</b>。
     * <p>
     * 与 {@link #updateAutoSearchMissState} 同样的理由，也是同一个踩过的坑：
     * {@code updateById(实体)} 在 MyBatis-Plus 默认的 {@code NOT_NULL} 策略下会把实体上
     * 所有非 null 字段一并写回，而体检拿到的那份订阅是扫描时刻的快照——把它整体写回，
     * 就会把补搜链路刚更新的 {@code last_search_time} 覆盖成旧值，导致订阅永远"已到期"、
     * 每次心跳都重搜一遍。而这件事没有任何错误现象，只是索引器被默默打了几十倍的请求。
     * </p>
     * <p>
     * 两个参数都允许传 {@code null}（缺集补齐后清空状态），因此必须走
     * {@code LambdaUpdateWrapper} 显式 set——NOT_NULL 策略下实体上的 null 字段会被直接跳过，
     * 清空写不进去。
     * </p>
     */
    void updateOverdueNotifyState(Integer subId, String sign, Date notifiedAt);

    /**
     * 只更新「缺集体检忽略」的两列，<b>不碰实体上的任何其它字段</b>。
     * <p>
     * 理由与 {@link #updateOverdueNotifyState} 完全相同：调用方手里那份订阅是扫描时刻的
     * 快照，{@code updateById(实体)} 会把它整体写回、覆盖掉补搜链路刚写入的
     * {@code last_search_time}，而且没有任何错误现象。
     * </p>
     * <p>
     * 取消忽略时要把时间列清成 null，因此同样必须走 {@code LambdaUpdateWrapper} 显式 set。
     * </p>
     *
     * @param ignored true=忽略，false=取消忽略
     * @return 实际更新的行数
     */
    int updateHealthIgnored(List<Integer> subIds, boolean ignored);

    /**
     * 查所有留有「逾期缺集」通知指纹的订阅（{@code last_overdue_notify_sign IS NOT NULL}）。
     * <p>
     * 用来找出"上次通知过、这次已经不缺了"的订阅并清空它们的状态。不清的话，这条订阅
     * 下次再出现缺集时会拿新指纹与陈旧的老指纹比较——多数情况下确实不等、于是照常通知，
     * 但只要新缺的恰好与上次是同一批（同一部剧反复缺同几集完全是常态），
     * 指纹相等，这次通知就被静默吞掉。
     * </p>
     */
    List<PtSubscriptionPlus> listOverdueNotified();
}
