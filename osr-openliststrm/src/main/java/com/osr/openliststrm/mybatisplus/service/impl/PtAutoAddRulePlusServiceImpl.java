package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;
import com.osr.openliststrm.mybatisplus.mapper.PtAutoAddRulePlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddRulePlusService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * 热门自动订阅规则 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-29
 */
@Service
public class PtAutoAddRulePlusServiceImpl extends ServiceImpl<PtAutoAddRulePlusMapper, PtAutoAddRulePlus> implements IPtAutoAddRulePlusService {

    @Override
    public List<PtAutoAddRulePlus> listEnabled() {
        return lambdaQuery()
                .eq(PtAutoAddRulePlus::getEnabled, "1")
                .orderByAsc(PtAutoAddRulePlus::getId)
                .list();
    }
}
