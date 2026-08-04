package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.mapper.PtSubscriptionEpisodePlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
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

    @Override
    public List<PtSubscriptionEpisodePlus> listStuckInFlight(int completedHoursAgo) {
        // 时长参数拼进 SQL 前先做正数校验：它来自 @Value 配置，虽不是用户请求参数，
        // 但拼串就得自己保证形态，非正数直接按 0 小时会把刚下完的记录全扫进来
        int hours = Math.max(1, completedHoursAgo);
        return lambdaQuery()
                .eq(PtSubscriptionEpisodePlus::getState, SubscriptionEpisodeState.IN_FLIGHT.value())
                .isNotNull(PtSubscriptionEpisodePlus::getDownloadId)
                .inSql(PtSubscriptionEpisodePlus::getDownloadId,
                        "SELECT id FROM pt_download_record WHERE state = 'COMPLETED'"
                                + " AND completed_time IS NOT NULL"
                                + " AND completed_time < DATE_SUB(NOW(), INTERVAL " + hours + " HOUR)")
                .orderByAsc(PtSubscriptionEpisodePlus::getSubId)
                .orderByAsc(PtSubscriptionEpisodePlus::getEpisode)
                .list();
    }
}
