package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddLogPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtAutoAddLogPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddLogPlusService;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 热门自动订阅执行日志 服务实现类
 * </p>
 * <p>
 * 条件一律用列名字符串的 {@link QueryWrapper}：值可能为 null 的列要走 IS NULL，
 * 交给 eq(null) 会生成恒不成立的 {@code = NULL}。
 * </p>
 *
 * @author Jack
 * @since 2026-07-29
 */
@Service
public class PtAutoAddLogPlusServiceImpl extends ServiceImpl<PtAutoAddLogPlusMapper, PtAutoAddLogPlus> implements IPtAutoAddLogPlusService {

    /** ADDED 结果取值，与 AutoAddPopularService 写入的一致 */
    private static final String RESULT_ADDED = "ADDED";

    @Override
    public boolean everAdded(String tmdbId, String mediaType, Integer season) {
        if (tmdbId == null) {
            return false;
        }
        QueryWrapper<PtAutoAddLogPlus> wrapper = new QueryWrapper<PtAutoAddLogPlus>()
                .eq("result", RESULT_ADDED)
                .eq("tmdb_id", tmdbId)
                .eq("media_type", mediaType);
        eqOrNull(wrapper, "season", season);
        return count(wrapper) > 0;
    }

    @Override
    public PtAutoAddLogPlus latestBySourceItem(String sourceItemId, String mediaType) {
        if (sourceItemId == null) {
            return null;
        }
        return getOne(new QueryWrapper<PtAutoAddLogPlus>()
                .eq("source_item_id", sourceItemId)
                .eq("media_type", mediaType)
                .orderByDesc("id")
                .last("limit 1"), false);
    }

    @Override
    public boolean alreadyLogged(PtAutoAddLogPlus entry) {
        QueryWrapper<PtAutoAddLogPlus> wrapper = new QueryWrapper<PtAutoAddLogPlus>()
                .eq("rule_id", entry.getRuleId())
                .eq("result", entry.getResult());
        eqOrNull(wrapper, "tmdb_id", entry.getTmdbId());
        eqOrNull(wrapper, "source_item_id", entry.getSourceItemId());
        eqOrNull(wrapper, "season", entry.getSeason());
        // 两边都没有 id 时（TMDb 源不会出现，豆瓣源缺 subject id 时会）只能退回按标题认
        if (entry.getTmdbId() == null && entry.getSourceItemId() == null) {
            eqOrNull(wrapper, "title", entry.getTitle());
        }
        return count(wrapper) > 0;
    }

    @Override
    public int purgeSkippedBefore(String before) {
        return getBaseMapper().delete(new QueryWrapper<PtAutoAddLogPlus>()
                .ne("result", RESULT_ADDED)
                .lt("create_time", before));
    }

    private static void eqOrNull(QueryWrapper<PtAutoAddLogPlus> wrapper, String column, Object value) {
        if (value == null) {
            wrapper.isNull(column);
        } else {
            wrapper.eq(column, value);
        }
    }
}
