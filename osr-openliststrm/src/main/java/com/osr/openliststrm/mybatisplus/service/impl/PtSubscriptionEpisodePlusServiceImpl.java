package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.mapper.PtSubscriptionEpisodePlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * PT 订阅每集状态 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Service
public class PtSubscriptionEpisodePlusServiceImpl extends ServiceImpl<PtSubscriptionEpisodePlusMapper, PtSubscriptionEpisodePlus> implements IPtSubscriptionEpisodePlusService {

    @Override
    public List<PtSubscriptionEpisodePlus> listBySubscription(Integer subId) {
        return lambdaQuery()
                .eq(PtSubscriptionEpisodePlus::getSubId, subId)
                .orderByAsc(PtSubscriptionEpisodePlus::getEpisode)
                .list();
    }
}
