package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtCleanRulePlus;
import com.osr.openliststrm.mybatisplus.mapper.PtCleanRulePlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtCleanRulePlusService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * PT 下载器自动删种规则 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-08-10
 */
@Service
public class PtCleanRulePlusServiceImpl extends ServiceImpl<PtCleanRulePlusMapper, PtCleanRulePlus>
        implements IPtCleanRulePlusService {

    @Override
    public List<PtCleanRulePlus> listEnabledByDownloader(Integer downloaderId) {
        if (downloaderId == null) {
            return List.of();
        }
        return list(new QueryWrapper<PtCleanRulePlus>()
                .eq("downloader_id", downloaderId)
                .eq("enabled", "1")
                .orderByAsc("sort_order")
                .orderByAsc("id"));
    }
}
