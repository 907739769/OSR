package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtFilterConfigPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * <p>
 * PT 全局过滤与排序配置 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Slf4j
@Service
public class PtFilterConfigPlusServiceImpl extends ServiceImpl<PtFilterConfigPlusMapper, PtFilterConfigPlus> implements IPtFilterConfigPlusService {

    @Override
    public PtFilterConfigPlus getConfig() {
        PtFilterConfigPlus config = getById(PtFilterConfigPlus.SINGLETON_ID);
        if (config != null) {
            return config;
        }
        // 迁移脚本的种子数据被误删时的兜底，取值与 20260725-pt-subscription.sql 中的种子一致
        log.warn("pt_filter_config 缺少 id=1 的配置行，使用内置默认值");
        PtFilterConfigPlus fallback = new PtFilterConfigPlus();
        fallback.setId(PtFilterConfigPlus.SINGLETON_ID);
        fallback.setMinSeeders(1);
        fallback.setMinSize(0L);
        fallback.setMaxSize(0L);
        fallback.setFreeOnly("0");
        fallback.setExcludeKeywords("预告,花絮,samples");
        fallback.setResolutionPriority("2160p,1080p,720p");
        fallback.setSortPriority("RESOLUTION,FREE,SEEDERS,SIZE");
        fallback.setPreferredSize(0L);
        return fallback;
    }
}
