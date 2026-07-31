package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtIndexerPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * PT Torznab 索引器配置 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
@Service
public class PtIndexerPlusServiceImpl extends ServiceImpl<PtIndexerPlusMapper, PtIndexerPlus> implements IPtIndexerPlusService {

    @Override
    public List<PtIndexerPlus> listEnabled() {
        return lambdaQuery()
                .eq(PtIndexerPlus::getEnabled, "1")
                .orderByAsc(PtIndexerPlus::getId)
                .list();
    }

    @Override
    public List<PtIndexerPlus> listDisabled() {
        return lambdaQuery()
                .eq(PtIndexerPlus::getEnabled, "0")
                .orderByAsc(PtIndexerPlus::getId)
                .list();
    }
}
