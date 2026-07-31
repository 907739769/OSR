package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;

import java.util.List;

/**
 * <p>
 * PT Torznab 索引器配置 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
public interface IPtIndexerPlusService extends IService<PtIndexerPlus> {

    /**
     * 查询全部启用中的索引器
     */
    List<PtIndexerPlus> listEnabled();

    /**
     * 查询全部已停用的索引器（供自愈探测扫描）
     */
    List<PtIndexerPlus> listDisabled();
}
