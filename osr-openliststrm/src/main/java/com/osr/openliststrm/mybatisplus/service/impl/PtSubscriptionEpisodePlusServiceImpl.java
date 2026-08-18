package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.osr.openliststrm.mybatisplus.mapper.PtSubscriptionEpisodePlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    @Override
    public List<PtSubscriptionEpisodePlus> listHealthCandidates(Date airedBefore) {
        return lambdaQuery()
                // UPGRADING 不纳入：那一集本来就在库里，用户手上有能看的版本，
                // 报进「缺集」会让体检页混进一批其实不缺的条目
                .in(PtSubscriptionEpisodePlus::getState,
                        SubscriptionEpisodeState.MISSING.value(),
                        SubscriptionEpisodeState.IN_FLIGHT.value(),
                        SubscriptionEpisodeState.BLOCKED.value())
                // 暂停/已完成的订阅不体检：用户已经明确表态不再追它，报出来只是噪音
                .inSql(PtSubscriptionEpisodePlus::getSubId,
                        "SELECT id FROM pt_subscription WHERE status = 'ACTIVE'")
                .and(w -> w.isNull(PtSubscriptionEpisodePlus::getAirDate)
                        .or().le(PtSubscriptionEpisodePlus::getAirDate, airedBefore))
                .orderByAsc(PtSubscriptionEpisodePlus::getSubId)
                .orderByAsc(PtSubscriptionEpisodePlus::getEpisode)
                .list();
    }

    @Override
    public Map<Integer, Map<String, Integer>> countStatesBySubscriptions(Collection<Integer> subIds) {
        if (subIds == null || subIds.isEmpty()) {
            return Collections.emptyMap();
        }
        QueryWrapper<PtSubscriptionEpisodePlus> wrapper = new QueryWrapper<>();
        wrapper.select("sub_id AS subId", "state AS state", "COUNT(*) AS cnt")
                .in("sub_id", subIds)
                .groupBy("sub_id", "state");
        Map<Integer, Map<String, Integer>> grouped = new HashMap<>();
        for (Map<String, Object> row : listMaps(wrapper)) {
            Integer subId = toInt(row.get("subId"));
            String state = row.get("state") == null ? null : String.valueOf(row.get("state"));
            Integer count = toInt(row.get("cnt"));
            if (subId == null || state == null || count == null) {
                continue;
            }
            grouped.computeIfAbsent(subId, k -> new HashMap<>()).merge(state, count, Integer::sum);
        }
        return grouped;
    }

    /**
     * COUNT(*) 在不同驱动下回来的可能是 Long/BigInteger/Integer，统一收成 int。
     * 直接强转 Integer 在 MySQL 驱动上会 ClassCastException（它给的是 Long）。
     */
    private static Integer toInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
