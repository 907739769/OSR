package com.ruoyi.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.mapper.PtSubscriptionPlusMapper;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * PT 订阅 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Service
public class PtSubscriptionPlusServiceImpl extends ServiceImpl<PtSubscriptionPlusMapper, PtSubscriptionPlus> implements IPtSubscriptionPlusService {

    @Override
    public List<PtSubscriptionPlus> listActive() {
        return lambdaQuery()
                .eq(PtSubscriptionPlus::getStatus, "ACTIVE")
                .orderByAsc(PtSubscriptionPlus::getId)
                .list();
    }

    @Override
    public List<PtSubscriptionPlus> listActiveWithMissing() {
        return lambdaQuery()
                .eq(PtSubscriptionPlus::getStatus, "ACTIVE")
                .inSql(PtSubscriptionPlus::getId,
                        "SELECT DISTINCT sub_id FROM pt_subscription_episode WHERE state IN ('MISSING', 'IN_FLIGHT')")
                .orderByAsc(PtSubscriptionPlus::getId)
                .list();
    }
}
