package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;

import java.util.List;

/**
 * <p>
 * 热门自动订阅规则 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-29
 */
public interface IPtAutoAddRulePlusService extends IService<PtAutoAddRulePlus> {

    /**
     * 查询全部已启用的规则。定时任务只处理这些。
     */
    List<PtAutoAddRulePlus> listEnabled();
}
