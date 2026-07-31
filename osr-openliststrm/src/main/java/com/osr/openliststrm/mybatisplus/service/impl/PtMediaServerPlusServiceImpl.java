package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtMediaServerPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * PT 媒体服务器配置 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
@Service
public class PtMediaServerPlusServiceImpl extends ServiceImpl<PtMediaServerPlusMapper, PtMediaServerPlus> implements IPtMediaServerPlusService {

    @Override
    public PtMediaServerPlus getActive() {
        return lambdaQuery()
                .eq(PtMediaServerPlus::getEnabled, "1")
                .orderByAsc(PtMediaServerPlus::getId)
                .last("limit 1")
                .one();
    }
}
