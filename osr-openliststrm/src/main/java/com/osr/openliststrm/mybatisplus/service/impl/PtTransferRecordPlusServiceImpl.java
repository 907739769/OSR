package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRecordPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtTransferRecordPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRecordPlusService;
import com.osr.openliststrm.pt.transfer.TransferState;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * PT 转移做种记录 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-08-15
 */
@Service
public class PtTransferRecordPlusServiceImpl extends ServiceImpl<PtTransferRecordPlusMapper, PtTransferRecordPlus>
        implements IPtTransferRecordPlusService {

    @Override
    public List<PtTransferRecordPlus> listVerifying(Integer ruleId) {
        if (ruleId == null) {
            return List.of();
        }
        return list(new QueryWrapper<PtTransferRecordPlus>()
                .eq("rule_id", ruleId)
                .eq("state", TransferState.VERIFYING.value())
                .orderByAsc("id"));
    }

    @Override
    public boolean hasVerifying(Integer ruleId, String torrentHash) {
        if (ruleId == null || torrentHash == null) {
            return false;
        }
        return count(new QueryWrapper<PtTransferRecordPlus>()
                .eq("rule_id", ruleId)
                .eq("torrent_hash", torrentHash.toLowerCase())
                .eq("state", TransferState.VERIFYING.value())) > 0;
    }

    @Override
    public Map<Integer, Map<String, Object>> summarizeByRule() {
        List<Map<String, Object>> rows = listMaps(new QueryWrapper<PtTransferRecordPlus>()
                .select("rule_id as rule_id, state as state, count(*) as cnt, max(create_time) as last_time")
                .groupBy("rule_id", "state"));
        Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Object ruleId = row.get("rule_id");
            if (ruleId == null) {
                continue;
            }
            Map<String, Object> summary = result.computeIfAbsent(((Number) ruleId).intValue(), k -> {
                Map<String, Object> init = new HashMap<>();
                for (TransferState state : TransferState.values()) {
                    init.put(state.value(), 0L);
                }
                return init;
            });
            summary.put(String.valueOf(row.get("state")), ((Number) row.get("cnt")).longValue());
            // 驱动返回的可能是 LocalDateTime 也可能是 Timestamp，统一成秒级字符串再比较与下发
            String lastTime = formatTime(row.get("last_time"));
            if (lastTime != null && (summary.get("lastTime") == null
                    || lastTime.compareTo((String) summary.get("lastTime")) > 0)) {
                summary.put("lastTime", lastTime);
            }
        }
        return result;
    }

    private static String formatTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp ts) {
            value = ts.toLocalDateTime();
        } else if (value instanceof java.util.Date date) {
            value = new java.sql.Timestamp(date.getTime()).toLocalDateTime();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return String.valueOf(value);
    }
}
