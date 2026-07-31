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
}
