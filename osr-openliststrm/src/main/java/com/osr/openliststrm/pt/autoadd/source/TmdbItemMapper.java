package com.osr.openliststrm.pt.autoadd.source;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.pt.subscription.SubscriptionService;

import java.util.ArrayList;
import java.util.List;

/**
 * TMDb 的 JSON 条目 → {@link PopularItem} 的字段映射。纯函数，无 IO，无 Spring 依赖。
 * <p>
 * <b>为什么独立成类：</b>榜单（trending/discover）与搜索（search）返回的条目结构完全一致，
 * 而两条路径分属 {@link TmdbPopularSource} 与 {@code PopularItemResolver} 两个 bean。
 * 各写一份的下场是「豆瓣源和 TMDb 源对同一部作品算出不同的评分人数」——而那两个数字
 * 直接决定规则里的过滤器放不放行，漂移之后从日志里根本看不出来。
 * </p>
 * <p>
 * 剧集与电影的字段名不同（name/original_name/first_air_date 对 title/original_title/release_date），
 * 本类屏蔽这个差异。
 * </p>
 *
 * @author Jack
 */
public final class TmdbItemMapper {

    private TmdbItemMapper() {
    }

    /**
     * @param mediaType 规则上配置的媒体类型 TV / MOVIE，决定读哪一组字段名
     */
    public static PopularItem toItem(JSONObject json, String mediaType) {
        boolean tv = !SubscriptionService.TYPE_MOVIE.equalsIgnoreCase(mediaType);
        PopularItem item = new PopularItem();
        item.setTmdbId(json.getString("id"));
        item.setMediaType(tv ? "TV" : "MOVIE");
        item.setTitle(json.getString(tv ? "name" : "title"));
        item.setOriginalTitle(json.getString(tv ? "original_name" : "original_title"));
        item.setYear(extractYear(json.getString(tv ? "first_air_date" : "release_date")));
        item.setVoteAverage(json.getDouble("vote_average"));
        item.setVoteCount(json.getInteger("vote_count"));
        item.setPosterPath(json.getString("poster_path"));
        item.setGenreIds(toGenreIds(json.getJSONArray("genre_ids")));
        return item;
    }

    private static List<Integer> toGenreIds(JSONArray genreIds) {
        if (genreIds == null) {
            return null;
        }
        List<Integer> genres = new ArrayList<>();
        for (int i = 0; i < genreIds.size(); i++) {
            genres.add(genreIds.getInteger(i));
        }
        return genres;
    }

    /** 取 {@code yyyy-MM-dd} 的年份部分；非法或缺失时返回 null（而不是 0，"算不出来"与"公元 0 年"不是一回事） */
    public static String extractYear(String date) {
        if (StringUtils.isBlank(date) || date.length() < 4) {
            return null;
        }
        String year = date.substring(0, 4);
        return year.chars().allMatch(Character::isDigit) ? year : null;
    }
}
