package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtSubscriptionPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import org.springframework.stereotype.Service;

import java.util.Date;
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
    public List<PtSubscriptionPlus> listAutoSearchCandidates() {
        return lambdaQuery()
                .eq(PtSubscriptionPlus::getStatus, "ACTIVE")
                .eq(PtSubscriptionPlus::getAutoSearch, "1")
                // 只看 MISSING：IN_FLIGHT 已经在下了，补搜对它无事可做。
                // 电影订阅也有一行集记录（episodeNumbers 给电影发一个哨兵集号），因此同样能被选中
                .inSql(PtSubscriptionPlus::getId,
                        "SELECT DISTINCT sub_id FROM pt_subscription_episode WHERE state = 'MISSING'")
                .orderByAsc(PtSubscriptionPlus::getId)
                .list();
    }

    @Override
    public void updateAutoSearchMissState(Integer subId, int missStreak, String rejectSign) {
        if (subId == null) {
            return;
        }
        update(new LambdaUpdateWrapper<PtSubscriptionPlus>()
                .eq(PtSubscriptionPlus::getId, subId)
                .set(PtSubscriptionPlus::getLastAutoSearchNoResult, missStreak)
                .set(PtSubscriptionPlus::getLastAutoSearchRejectSign, rejectSign));
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

    @Override
    public void updateOverdueNotifyState(Integer subId, String sign, Date notifiedAt) {
        if (subId == null) {
            return;
        }
        update(new LambdaUpdateWrapper<PtSubscriptionPlus>()
                .eq(PtSubscriptionPlus::getId, subId)
                .set(PtSubscriptionPlus::getLastOverdueNotifySign, sign)
                .set(PtSubscriptionPlus::getLastOverdueNotifyTime, notifiedAt));
    }

    @Override
    public List<PtSubscriptionPlus> listOverdueNotified() {
        return lambdaQuery()
                .isNotNull(PtSubscriptionPlus::getLastOverdueNotifySign)
                .list();
    }
}
