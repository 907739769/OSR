package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;

/**
 * <p>
 * PT 全局过滤与排序配置 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
public interface IPtFilterConfigPlusService extends IService<PtFilterConfigPlus> {

    /**
     * 取全局配置（单行表，id=1）。迁移脚本已插入种子数据，正常不会为 null；
     * 若确实缺失则返回一份内置默认值，保证过滤引擎永远拿得到配置。
     */
    PtFilterConfigPlus getConfig();
}
