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

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「跟」一个对象而不是一张榜单：TMDb 电影系列（合集）与 TMDb 人物。
 * <p>
 * 系列：订阅系列里的全部电影（已有的由下游去重跳过，新出的续集下一轮自然补进来）。<br>
 * 人物：只取担任导演、或演员表排前 {@value #TOP_CAST} 位的电影，且上映日期在最近一年内或尚未上映——
 * 一位演员的全部作品动辄上百部，全订下来等于把索引器和下载器拉爆，而用户要的是「他的新片出了告诉我」。
 * </p>
 * 参数填在规则的 {@code sourceUrl}：纯数字 ID，或直接贴 TMDb 页面链接。只支持电影。
 *
 * @author Jack
 */
@Slf4j
@Component
public class TmdbFollowSource implements PopularSource {

    public static final String SOURCE_COLLECTION = "TMDB_COLLECTION";
    public static final String SOURCE_PERSON = "TMDB_PERSON";

    /** 人物来源只看演员表前几位，再往后多是客串，订下来没人想看 */
    static final int TOP_CAST = 5;

    /** 人物来源的时间窗：上映满一年的老片不再自动订 */
    static final int RECENT_DAYS = 365;

    private static final Pattern ID_IN_URL = Pattern.compile("/(?:collection|person)/(\\d+)");
    private static final Pattern PLAIN_ID = Pattern.compile("\\d+");

    @Autowired
    private TMDbApiService tmDbApiService;

    @Autowired
    private OpenlistConfig openlistConfig;

    @Override
    public boolean supports(String source) {
        return SOURCE_COLLECTION.equals(source) || SOURCE_PERSON.equals(source);
    }

    @Override
    public List<PopularItem> fetch(PtAutoAddRulePlus rule) {
        if (!SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(rule.getMediaType())) {
            log.warn("热门自动订阅规则[{}] 数据源 {} 只支持电影，当前媒体类型为 {}，跳过", rule.getId(), rule.getSource(), rule.getMediaType());
            return List.of();
        }
        Integer id = parseId(rule.getSourceUrl());
        if (id == null) {
            log.warn("热门自动订阅规则[{}] 的 TMDb ID 无法识别：{}（填纯数字 ID 或 TMDb 页面链接）", rule.getId(), rule.getSourceUrl());
            return List.of();
        }
        String apiKey = openlistConfig.getTmdbApiKey();
        if (StringUtils.isBlank(apiKey)) {
            log.warn("TMDb API Key 未配置，热门自动订阅规则[{}]无法拉取", rule.getId());
            return List.of();
        }
        if (SOURCE_COLLECTION.equals(rule.getSource())) {
            return collectionItems(readObject(tmDbApiService.getCollection(apiKey, id)));
        }
        return personItems(readObject(tmDbApiService.getPersonMovieCredits(apiKey, id)), LocalDate.now());
    }

    /** 纯数字直接用；链接取 {@code /collection/} 或 {@code /person/} 后的数字（TMDb 链接形如 /person/287-brad-pitt） */
    static Integer parseId(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        String s = raw.trim();
        if (PLAIN_ID.matcher(s).matches()) {
            return Integer.valueOf(s);
        }
        Matcher m = ID_IN_URL.matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    static List<PopularItem> collectionItems(JSONObject json) {
        JSONArray parts = json == null ? null : json.getJSONArray("parts");
        List<PopularItem> items = new ArrayList<>();
        if (parts == null) {
            return items;
        }
        for (int i = 0; i < parts.size(); i++) {
            items.add(TmdbItemMapper.toItem(parts.getJSONObject(i), SubscriptionService.TYPE_MOVIE));
        }
        return items;
    }

    /**
     * 导演（crew 里 job=Director）或主演（cast 里 order 在前 {@value #TOP_CAST} 位），上映日期在最近一年内或未上映。
     * 没有上映日期的一律跳过——TMDb 上那多半是只立了项的传闻片，订下来可能几年都搜不到资源。
     * 同一部电影既导又演时只出一条。
     */
    static List<PopularItem> personItems(JSONObject json, LocalDate today) {
        Map<String, PopularItem> byId = new LinkedHashMap<>();
        if (json == null) {
            return new ArrayList<>();
        }
        LocalDate since = today.minusDays(RECENT_DAYS);
        JSONArray cast = json.getJSONArray("cast");
        if (cast != null) {
            for (int i = 0; i < cast.size(); i++) {
                JSONObject c = cast.getJSONObject(i);
                Integer order = c.getInteger("order");
                if (order != null && order < TOP_CAST && recent(c.getString("release_date"), since)) {
                    byId.putIfAbsent(c.getString("id"), TmdbItemMapper.toItem(c, SubscriptionService.TYPE_MOVIE));
                }
            }
        }
        JSONArray crew = json.getJSONArray("crew");
        if (crew != null) {
            for (int i = 0; i < crew.size(); i++) {
                JSONObject c = crew.getJSONObject(i);
                if ("Director".equals(c.getString("job")) && recent(c.getString("release_date"), since)) {
                    byId.putIfAbsent(c.getString("id"), TmdbItemMapper.toItem(c, SubscriptionService.TYPE_MOVIE));
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static boolean recent(String releaseDate, LocalDate since) {
        if (StringUtils.isBlank(releaseDate)) {
            return false;
        }
        try {
            return !LocalDate.parse(releaseDate).isBefore(since);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private JSONObject readObject(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return JSONObject.parseObject(raw);
        } catch (Exception e) {
            log.warn("TMDb 系列/人物响应不是合法 JSON：{}", e.getMessage());
            return null;
        }
    }
}
