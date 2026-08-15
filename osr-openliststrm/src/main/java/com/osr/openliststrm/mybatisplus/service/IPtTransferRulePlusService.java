package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRulePlus;

import java.util.List;

/**
 * <p>
 * PT 转移做种规则 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-08-15
 */
public interface IPtTransferRulePlusService extends IService<PtTransferRulePlus> {

    /**
     * 查询全部<b>启用中</b>的规则，按 id 升序。
     * <p>
     * 定时任务的入口用它。用专门的方法而不是在调用点内联 QueryWrapper，是为了让
     * "只跑启用的规则"这个约束只有一处实现——漏掉一次的表现是关掉的规则照样在搬种子。
     * </p>
     */
    List<PtTransferRulePlus> listEnabled();
}
