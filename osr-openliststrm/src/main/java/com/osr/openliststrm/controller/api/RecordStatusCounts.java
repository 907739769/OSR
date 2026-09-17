package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 记录页顶部统计条的数据：在当前筛选条件下按状态分组计数。
 * <p>
 * <b>传进来的 wrapper 只能带筛选条件，不能带排序</b>：{@code GROUP BY status} 配上
 * {@code ORDER BY create_time} 在 MySQL 的 ONLY_FULL_GROUP_BY 下直接报错，整条统计条出不来。
 * 所以三个记录页的 Controller 都把「筛选」与「排序」拆成两步，列表拼上排序、统计只用筛选。
 * <p>
 * 统计要跟着搜索条件走（去掉状态这一项），否则用户搜了一个目录，统计条上还是全表的数字，
 * 点「失败 17」筛出来却只有 2 条，两个数对不上。
 */
final class RecordStatusCounts {

    private RecordStatusCounts() {
    }

    /**
     * @return 状态值 → 条数，另含 {@code total}。状态为 NULL 的历史行只计入 total
     */
    static <T> Map<String, Long> count(BaseMapper<T> mapper, QueryWrapper<T> conditions, String statusColumn) {
        conditions.select(statusColumn + " AS status", "COUNT(*) AS cnt").groupBy(statusColumn);
        List<Map<String, Object>> rows = mapper.selectMaps(conditions);
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = 0;
        for (Map<String, Object> row : rows) {
            long cnt = row.get("cnt") instanceof Number n ? n.longValue() : 0L;
            total += cnt;
            Object status = row.get("status");
            if (status != null) {
                counts.merge(String.valueOf(status), cnt, Long::sum);
            }
        }
        counts.put("total", total);
        return counts;
    }
}
