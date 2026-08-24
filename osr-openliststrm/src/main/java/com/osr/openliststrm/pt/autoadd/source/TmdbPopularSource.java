package com.osr.openliststrm.pt.autoadd.source;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;
import com.osr.openliststrm.pt.subscription.SubscriptionService;
import com.osr.openliststrm.tmdb.TMDbApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TMDb 榜单数据源：热门趋势(trending) 与 条件发现(discover)。
 *
 * @author Jack
 */
@Slf4j
@Component
public class TmdbPopularSource implements PopularSource {

    public static final String SOURCE_TRENDING_DAY = "TMDB_TRENDING_DAY";
    public static final String SOURCE_TRENDING_WEEK = "TMDB_TRENDING_WEEK";
    public static final String SOURCE_DISCOVER = "TMDB_DISCOVER";

    /** 每轮最多翻的页数，一页 20 条，3 页足够覆盖绝大多数规则的 maxAddPerRun */
    private static final int MAX_PAGES = 3;

    @Autowired
    private TMDbApiService tmDbApiService;

    @Autowired
    private OpenlistConfig openlistConfig;

    @Override
    public boolean supports(String source) {
        return SOURCE_TRENDING_DAY.equals(source) || SOURCE_TRENDING_WEEK.equals(source) || SOURCE_DISCOVER.equals(source);
    }

    @Override
    public List<PopularItem> fetch(PtAutoAddRulePlus rule) {
        String apiKey = openlistConfig.getTmdbApiKey();
        if (StringUtils.isBlank(apiKey)) {
            log.warn("TMDb API Key 未配置，热门自动订阅规则[{}]无法拉取榜单", rule.getId());
            return List.of();
        }
        String type = SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(rule.getMediaType()) ? "movie" : "tv";
        List<PopularItem> result = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String raw = fetchPage(apiKey, type, rule, page);
            JSONObject json = readObject(raw);
            JSONArray results = json == null ? null : json.getJSONArray("results");
            if (results == null || results.isEmpty()) {
                break;
            }
            for (int i = 0; i < results.size(); i++) {
                result.add(TmdbItemMapper.toItem(results.getJSONObject(i), rule.getMediaType()));
            }
            Integer totalPages = json.getInteger("total_pages");
            if (totalPages != null && page >= totalPages) {
                break;
            }
        }
        return result;
    }

    private String fetchPage(String apiKey, String type, PtAutoAddRulePlus rule, int page) {
        if (SOURCE_DISCOVER.equals(rule.getSource())) {
            Map<String, String> params = new HashMap<>();
            params.put("sort_by", "popularity.desc");
            if (rule.getMinVoteAverage() != null) {
                params.put("vote_average.gte", String.valueOf(rule.getMinVoteAverage()));
            }
            if (rule.getMinVoteCount() != null) {
                params.put("vote_count.gte", String.valueOf(rule.getMinVoteCount()));
            }
            if (StringUtils.isNotBlank(rule.getRegion())) {
                // discover/movie 用 region，discover/tv 用 with_origin_country，字段名不同
                params.put("movie".equals(type) ? "region" : "with_origin_country", rule.getRegion());
            }
            return tmDbApiService.getDiscover(apiKey, type, params, page);
        }
        String timeWindow = SOURCE_TRENDING_WEEK.equals(rule.getSource()) ? "week" : "day";
        return tmDbApiService.getTrending(apiKey, type, timeWindow, page);
    }

    private JSONObject readObject(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return JSONObject.parseObject(raw);
        } catch (Exception e) {
            log.warn("TMDb 榜单响应不是合法 JSON：{}", e.getMessage());
            return null;
        }
    }
}
