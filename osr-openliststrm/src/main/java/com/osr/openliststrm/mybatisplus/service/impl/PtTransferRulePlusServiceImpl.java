package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRulePlus;
import com.osr.openliststrm.mybatisplus.mapper.PtTransferRulePlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRulePlusService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * PT 转移做种规则 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-08-15
 */
@Service
public class PtTransferRulePlusServiceImpl extends ServiceImpl<PtTransferRulePlusMapper, PtTransferRulePlus>
        implements IPtTransferRulePlusService {

    @Override
    public List<PtTransferRulePlus> listEnabled() {
        return list(new QueryWrapper<PtTransferRulePlus>()
                .eq("enabled", "1")
                .orderByAsc("id"));
    }
}
