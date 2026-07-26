package com.ruoyi.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;

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
     * 供 {@link com.ruoyi.openliststrm.pt.task.LibrarySyncTask} 对账使用。
     * 比 {@link #listActive()} 更精准：跳过全部已入库的 ACTIVE 订阅，
     * 减少不必要的 TMDb/Emby API 调用。
     */
    List<PtSubscriptionPlus> listActiveWithMissing();
}
