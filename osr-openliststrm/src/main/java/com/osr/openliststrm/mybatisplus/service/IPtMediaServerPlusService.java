package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;

/**
 * <p>
 * PT 媒体服务器配置 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
public interface IPtMediaServerPlusService extends IService<PtMediaServerPlus> {

    /**
     * 取当前启用的媒体服务器。多条启用时取 ID 最小的一条，无启用时返回 null。
     */
    PtMediaServerPlus getActive();
}
