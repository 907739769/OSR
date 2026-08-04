package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;

/**
 * <p>
 * PT 洗版配置 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-08-04
 */
public interface IPtUpgradeConfigPlusService extends IService<PtUpgradeConfigPlus> {

    /**
     * 取洗版配置（单行表，id=1）。迁移脚本已插入种子数据，正常不会为 null；
     * 若确实缺失则返回一份内置默认值（总开关关闭），保证扫描任务永远拿得到配置且不会误启动。
     */
    PtUpgradeConfigPlus getConfig();
}
