package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;

import java.util.List;

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
}
