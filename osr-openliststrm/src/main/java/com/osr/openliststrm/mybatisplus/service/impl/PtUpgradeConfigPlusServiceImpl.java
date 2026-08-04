package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtUpgradeConfigPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtUpgradeConfigPlusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <p>
 * PT 洗版配置 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-08-04
 */
@Slf4j
@Service
public class PtUpgradeConfigPlusServiceImpl extends ServiceImpl<PtUpgradeConfigPlusMapper, PtUpgradeConfigPlus>
        implements IPtUpgradeConfigPlusService {

    @Override
    public PtUpgradeConfigPlus getConfig() {
        PtUpgradeConfigPlus config = getById(PtUpgradeConfigPlus.SINGLETON_ID);
        if (config != null) {
            return config;
        }
        // 迁移脚本的种子数据被误删时的兜底。注意 enabled 取 "0"：拿不到配置时绝不能擅自开始洗版，
        // 那会对全部已入库的集发起搜索，是最糟糕的失败方向
        log.warn("pt_upgrade_config 缺少 id=1 的配置行，按洗版未启用处理");
        PtUpgradeConfigPlus fallback = new PtUpgradeConfigPlus();
        fallback.setId(PtUpgradeConfigPlus.SINGLETON_ID);
        fallback.setEnabled("0");
        fallback.setQualityPriority("RESOLUTION,SOURCE,TAG,RELEASE_GROUP");
        fallback.setTargetResolution("2160p");
        fallback.setTargetSources("REMUX,BluRay");
        fallback.setMaxConcurrent(2);
        fallback.setScanIntervalHours(6);
        return fallback;
    }
}
