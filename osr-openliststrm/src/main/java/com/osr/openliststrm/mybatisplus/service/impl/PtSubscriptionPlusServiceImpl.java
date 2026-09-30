package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtSubscriptionPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.openliststrm.pt.subscription.SeasonPackPolicy;
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
    public void updateLastSearchTime(Integer subId, Date lastSearchTime) {
        if (subId == null) {
            return;
        }
        update(new LambdaUpdateWrapper<PtSubscriptionPlus>()
                .eq(PtSubscriptionPlus::getId, subId)
                .set(PtSubscriptionPlus::getLastSearchTime, lastSearchTime));
    }

    @Override
    public void updateWatchState(Integer subId, Integer watchedCount, Date lastWatchedTime) {
        if (subId == null) {
            return;
        }
        update(new LambdaUpdateWrapper<PtSubscriptionPlus>()
                .eq(PtSubscriptionPlus::getId, subId)
                .set(PtSubscriptionPlus::getWatchedCount, watchedCount)
                .set(PtSubscriptionPlus::getLastWatchedTime, lastWatchedTime));
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
    public int updateHealthIgnored(List<Integer> subIds, boolean ignored) {
        if (subIds == null || subIds.isEmpty()) {
            return 0;
        }
        boolean ok = update(new LambdaUpdateWrapper<PtSubscriptionPlus>()
                .in(PtSubscriptionPlus::getId, subIds)
                .set(PtSubscriptionPlus::getHealthIgnored, ignored ? "1" : "0")
                // 取消忽略时清空时刻，留着的话「这条是什么时候被忽略的」会指向一次早已撤销的操作
                .set(PtSubscriptionPlus::getHealthIgnoredTime, ignored ? new Date() : null));
        return ok ? subIds.size() : 0;
    }

    @Override
    public List<PtSubscriptionPlus> listOverdueNotified() {
        return lambdaQuery()
                .isNotNull(PtSubscriptionPlus::getLastOverdueNotifySign)
                .list();
    }

    @Override
    public boolean learnEpisodeFirst(Integer subId) {
        if (subId == null) {
            return false;
        }
        return update(new LambdaUpdateWrapper<PtSubscriptionPlus>()
                .eq(PtSubscriptionPlus::getId, subId)
                .and(w -> w.eq(PtSubscriptionPlus::getSeasonPackPolicy, SeasonPackPolicy.AUTO.name())
                        .or().isNull(PtSubscriptionPlus::getSeasonPackPolicy))
                .set(PtSubscriptionPlus::getSeasonPackPolicy, SeasonPackPolicy.EPISODE_LEARNED.name()));
    }

    @Override
    public int enableAutoSearchByHealth(List<Integer> subIds, Date enabledAt) {
        if (subIds == null || subIds.isEmpty()) {
            return 0;
        }
        return getBaseMapper().update(null, new LambdaUpdateWrapper<PtSubscriptionPlus>()
                .in(PtSubscriptionPlus::getId, subIds)
                .and(w -> w.ne(PtSubscriptionPlus::getAutoSearch, "1").or().isNull(PtSubscriptionPlus::getAutoSearch))
                .set(PtSubscriptionPlus::getAutoSearch, "1")
                .set(PtSubscriptionPlus::getHealthAutoSearchTime, enabledAt));
    }

    @Override
    public boolean disableHealthAutoSearch(Integer subId) {
        if (subId == null) {
            return false;
        }
        return update(new LambdaUpdateWrapper<PtSubscriptionPlus>()
                .eq(PtSubscriptionPlus::getId, subId)
                .isNotNull(PtSubscriptionPlus::getHealthAutoSearchTime)
                .set(PtSubscriptionPlus::getAutoSearch, "0")
                .set(PtSubscriptionPlus::getHealthAutoSearchTime, null));
    }

    @Override
    public void clearHealthAutoSearchMark(List<Integer> subIds) {
        if (subIds == null || subIds.isEmpty()) {
            return;
        }
        update(new LambdaUpdateWrapper<PtSubscriptionPlus>()
                .in(PtSubscriptionPlus::getId, subIds)
                .isNotNull(PtSubscriptionPlus::getHealthAutoSearchTime)
                .set(PtSubscriptionPlus::getHealthAutoSearchTime, null));
    }

    @Override
    public List<PtSubscriptionPlus> listHealthAutoSearched() {
        return lambdaQuery()
                .isNotNull(PtSubscriptionPlus::getHealthAutoSearchTime)
                .list();
    }
}
