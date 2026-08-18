package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * PT 订阅每集状态 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
public interface IPtSubscriptionEpisodePlusService extends IService<PtSubscriptionEpisodePlus> {

    /**
     * 按订阅查全部集，按集号升序。
     */
    List<PtSubscriptionEpisodePlus> listBySubscription(Integer subId);

    /**
     * 查「卡死在途」的集：状态仍是 IN_FLIGHT，但它关联的下载记录早已 COMPLETED 超过指定时长。
     * <p>
     * 这批集是异常态，不是正常在途——下载都完成好几个小时了，Emby 对账却一直没把它们推进
     * IN_LIBRARY，说明文件根本不在那个种子里（典型成因：季包只含部分集，占位时多占了）。
     * 而 {@code SubscriptionService#refresh} 只升不降、补搜与 RSS 只认 MISSING，
     * 没人来收这个尾，它们会永久停在在途。
     * </p>
     * <p>
     * 独立成命名方法而不是内联 QueryWrapper：它与「正常在途」是语义完全不同的集合，
     * 混在泛化的 {@code list(Wrapper)} 调用里读不出意图，测试里也无从区分
     * （与 {@code IPtDownloadRecordPlusService#listSeedingPending} 同样的理由）。
     * </p>
     *
     * @param completedHoursAgo 下载记录完成后至少经过多少小时才纳入，必须为正数
     */
    List<PtSubscriptionEpisodePlus> listStuckInFlight(int completedHoursAgo);

    /**
     * 查「缺集体检」的候选集：ACTIVE 订阅名下、尚未入库、且不属于「还没播」的集。
     * <p>
     * 三个条件都推到 SQL 而不是拉全表再内存筛：集表是本项目最大的一张表（订阅数 × 集数），
     * 而体检真正关心的通常只有几十行。
     * </p>
     * <p>
     * <b>{@code air_date IS NULL} 的行也要捞回来</b>，交给上层单独分档。在这里就把它们滤掉
     * 看似干净，代价是尚未被 {@code EpisodeAirDateSyncTask} 扫到的存量行会整批从体检里消失——
     * 而「缺了却看不见」正是这个功能要解决的问题本身。
     * </p>
     * <p>
     * 电影订阅的过滤<b>不在这里做</b>，在 {@code EpisodeHealthService#scan}：电影每条订阅只贡献
     * 一行集记录（哨兵集号），推到 SQL 省不下什么，放在 Java 侧反而能被单测直接盖住，
     * 「为什么不报电影」这个判断也该和分档/诊断的逻辑待在一起。
     * </p>
     * <p>
     * 未播出的集靠 {@code airedBefore} 上界排除，与 {@code SearchSupplementService#aired}
     * 是同一条业务判据：还没播的集恒为 MISSING，报成"缺集"只会让整页被未来的集淹掉。
     * </p>
     *
     * @param airedBefore 播出日期上界（含当天）；比它晚的集视为「还没播够时间」不纳入
     */
    List<PtSubscriptionEpisodePlus> listHealthCandidates(Date airedBefore);

    /**
     * 批量统计一批订阅各状态的集数，返回 {@code {subId -> {state -> count}}}。
     * <p>
     * 走一条 {@code GROUP BY sub_id, state} 聚合，而不是每条订阅查一次集表：列表页一页
     * 最多接近百条订阅，逐条查就是近百次往返，而集表是本项目最大的一张表（订阅数 × 集数），
     * 把整季的集记录拉回内存只为了数个数更是白费。
     * </p>
     * <p>
     * 只返回聚合行，<b>不做任何业务口径的换算</b>（比如「洗版中也算已入库」）——那条判据
     * 属于 {@code SubscriptionService}，放在这里会变成第二处定义。
     * </p>
     *
     * @param subIds 订阅 id；为空时返回空 Map，不发 SQL
     */
    Map<Integer, Map<String, Integer>> countStatesBySubscriptions(Collection<Integer> subIds);
}
