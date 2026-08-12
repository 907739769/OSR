package com.osr.openliststrm.pt.subscription;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.pt.subscription.dto.TmdbSearchItem;
import com.osr.openliststrm.tmdb.TMDbApiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 TMDb 返回的原始 JSON 转成结构化 DTO。
 * <p>
 * 剧集与电影的字段名不同：剧集用 name / original_name / first_air_date，
 * 电影用 title / original_title / release_date。本类屏蔽这个差异。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Service
public class TmdbSearchService {

    /** 媒体类型：剧集 */
    public static final String TYPE_TV = "TV";

    /** 媒体类型：电影 */
    public static final String TYPE_MOVIE = "MOVIE";

    /** 中文别名的地区优先级（TMDb alternative_titles 的 iso_3166_1）：大陆 → 台湾 → 香港 → 新加坡 */
    private static final List<String> CHINESE_REGIONS = List.of("CN", "TW", "HK", "SG");

    @Autowired
    private TMDbApiService tmDbApiService;

    @Autowired
    private OpenlistConfig openlistConfig;

    /**
     * 按关键词搜索。
     *
     * @param mediaType TV / MOVIE
     * @return 搜索结果；关键词为空、响应异常或无结果时返回空列表（不抛异常，搜索失败不该让页面报错）
     */
    public List<TmdbSearchItem> search(String mediaType, String keyword) {
        List<TmdbSearchItem> result = new ArrayList<>();
        if (StringUtils.isBlank(keyword)) {
            return result;
        }
        String raw = tmDbApiService.search(openlistConfig.getTmdbApiKey(), tmdbType(mediaType), keyword.trim(), null);
        JSONArray results = readArray(raw, "results");
        if (results == null) {
            return result;
        }
        for (int i = 0; i < results.size(); i++) {
            result.add(toItem(results.getJSONObject(i), mediaType));
        }
        return result;
    }

    /**
     * 按 TMDb ID 取详情，用于建订阅时补全标题/年份/海报。
     *
     * @throws IllegalArgumentException 响应无法解析
     */
    public TmdbSearchItem getDetail(String mediaType, String tmdbId) {
        JSONObject detail = readObject(tmDbApiService.getDetails(
                openlistConfig.getTmdbApiKey(), tmdbType(mediaType), Integer.parseInt(tmdbId)));
        if (detail == null) {
            throw new IllegalArgumentException("TMDb 未返回 " + tmdbId + " 的详情");
        }
        TmdbSearchItem item = toItem(detail, mediaType);
        item.setTitle(resolveChineseTitle(mediaType, tmdbId, item.getTitle()));
        item.setImdbId(resolveImdbId(mediaType, tmdbId, detail));
        item.setEnglishTitle(resolveEnglishTitle(mediaType, tmdbId, detail));
        return item;
    }

    /**
     * 解析真正的英文标题：原始语言本就是英文时直接取 original_title/name（省一次请求）；
     * 否则（日剧/韩剧/动画等）用 language=en-US 重新查一次详情取 TMDb 的英文规范名。
     * PT 站种子标题绝大多数是英文/罗马字，用真正的英文标题而非 original_title 匹配才不会漏判日韩剧。
     * <p>
     * 之前直接查 /alternative_titles 取第一条 iso_3166_1=US 的别名，但该接口是众包数据，
     * 长篇动画常年被粉丝登记了大量"篇章别名"（如 One Piece 被登记成
     * "One Piece Log: Fish-Man Island Saga" 这类分篇标题也标了 US），
     * 取到的根本不是剧集本身的英文名，导致标题归一化后与种子标题（"One Piece"）无交集，
     * 所有候选都被 {@code SearchSupplementService#titleMatches} 误判为标题不匹配。
     * 改用 en-US 详情的 name/title 字段更可靠，alternative_titles 只作最后兜底。
     * </p>
     */
    private String resolveEnglishTitle(String mediaType, String tmdbId, JSONObject detail) {
        String originalLanguage = detail.getString("original_language");
        boolean tv = !TYPE_MOVIE.equalsIgnoreCase(mediaType);
        if ("en".equalsIgnoreCase(originalLanguage)) {
            return detail.getString(tv ? "name" : "title");
        }
        String enUsTitle = fetchEnUsTitle(mediaType, tmdbId, tv);
        if (StringUtils.isNotBlank(enUsTitle)) {
            return enUsTitle;
        }
        return fetchEnglishAlias(mediaType, tmdbId);
    }

    /**
     * 用 language=en-US 重新查一次详情，取 TMDb 的英文规范名（不受 alternative_titles 众包数据干扰）。
     * 网络异常等情况静默返回 null，交由调用方走 alternative_titles 兜底。
     */
    private String fetchEnUsTitle(String mediaType, String tmdbId, boolean tv) {
        try {
            String raw = tmDbApiService.getDetails(
                    openlistConfig.getTmdbApiKey(), tmdbType(mediaType), Integer.parseInt(tmdbId), "en-US");
            JSONObject detail = readObject(raw);
            if (detail == null) {
                return null;
            }
            return detail.getString(tv ? "name" : "title");
        } catch (Exception e) {
            log.warn("获取 TMDb 英文详情异常（tmdbId={}）：{}", tmdbId, e.getMessage());
            return null;
        }
    }

    /**
     * 查 /movie|tv/{id}/alternative_titles，取 iso_3166_1 为 US 的别名标题，取不到时退而求其次取 GB。
     * 网络异常/无 key 等情况静默返回 null，交由调用方按"未知"处理（不阻断建订阅）。
     */
    private String fetchEnglishAlias(String mediaType, String tmdbId) {
        JSONArray titles = fetchAlternativeTitles(mediaType, tmdbId);
        if (titles == null) {
            return null;
        }
        String gbTitle = null;
        for (int i = 0; i < titles.size(); i++) {
            JSONObject t = titles.getJSONObject(i);
            String country = t.getString("iso_3166_1");
            String title = aliasTitle(t);
            if (StringUtils.isBlank(title)) {
                continue;
            }
            if ("US".equals(country)) {
                return title;
            }
            if ("GB".equals(country) && gbTitle == null) {
                gbTitle = title;
            }
        }
        return gbTitle;
    }

    /**
     * 订阅标题优先用中文。详情接口已经按配置的元数据语言（默认 zh-CN）请求，但 TMDb 缺中文翻译时
     * name/title 会直接退回英文（冷门日韩剧、纪录片、动画常见），页面上就是一串英文。
     * 这种情况下查一次 /alternative_titles 取 CN/TW/HK/SG 的中文别名补上；
     * 别名里也没有中文（或请求失败）就<b>原样返回入参</b>，绝不因为这次锦上添花的请求而阻断建订阅。
     * <p>
     * 建订阅时由 {@link #getDetail} 调用；存量订阅在对账刷新时也会调它补齐早先存下的英文标题。
     * </p>
     */
    public String resolveChineseTitle(String mediaType, String tmdbId, String title) {
        if (containsChinese(title)) {
            return title;
        }
        String alias = fetchChineseAlias(mediaType, tmdbId);
        return StringUtils.isNotBlank(alias) ? alias : title;
    }

    /**
     * 按 {@link #CHINESE_REGIONS} 的地区优先级挑中文别名。
     * <p>
     * 必须逐条校验"确实含中文"：alternative_titles 是众包数据，CN 条目里登记罗马音、
     * 拼音、纯英文副标题的不在少数，只按地区取会把英文换成另一串英文。
     * </p>
     */
    private String fetchChineseAlias(String mediaType, String tmdbId) {
        JSONArray titles = fetchAlternativeTitles(mediaType, tmdbId);
        if (titles == null) {
            return null;
        }
        String best = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < titles.size(); i++) {
            JSONObject t = titles.getJSONObject(i);
            String title = aliasTitle(t);
            if (!containsChinese(title)) {
                continue;
            }
            int rank = CHINESE_REGIONS.indexOf(t.getString("iso_3166_1"));
            if (rank < 0 || rank >= bestRank) {
                continue;
            }
            best = title;
            bestRank = rank;
            if (rank == 0) {
                // CN 已是最高优先级，无需再看后面的条目
                break;
            }
        }
        return best;
    }

    /**
     * 取 /alternative_titles 的别名数组（电影是 titles，剧集是 results）。
     * 网络异常/无 key/响应非法等情况静默返回 null——别名查询是可选增强，失败只降级不报错。
     */
    private JSONArray fetchAlternativeTitles(String mediaType, String tmdbId) {
        try {
            String raw = tmDbApiService.getAlternativeTitles(
                    openlistConfig.getTmdbApiKey(), tmdbType(mediaType), Integer.parseInt(tmdbId));
            JSONObject root = readObject(raw);
            if (root == null) {
                return null;
            }
            return TYPE_MOVIE.equalsIgnoreCase(mediaType) ? root.getJSONArray("titles") : root.getJSONArray("results");
        } catch (Exception e) {
            log.warn("获取 TMDb 别名标题异常（tmdbId={}）：{}", tmdbId, e.getMessage());
            return null;
        }
    }

    /** 别名条目的标题字段：电影接口给 title，剧集接口给 title 或 name，两个都试。 */
    private String aliasTitle(JSONObject alias) {
        String title = alias.getString("title");
        return StringUtils.isNotBlank(title) ? title : alias.getString("name");
    }

    /** 是否含 CJK 汉字（简繁都在该区段内）。 */
    private boolean containsChinese(String text) {
        return text != null && text.codePoints().anyMatch(c -> c >= 0x4E00 && c <= 0x9FFF);
    }

    /**
     * 查询影片的原始语言代码（如 "zh"、"en"、"ja"、"ko" 等）。
     * <p>
     * 利用 TMDb API /movie/{id} 或 /tv/{id} 的详情响应中的 {@code original_language} 字段。
     * API 响应已有进程内缓存(L1,10min)与数据库缓存(L2,24h)，同一影片的频繁查询不会触发重复网络请求。
     * </p>
     *
     * @param mediaType TV / MOVIE
     * @param tmdbId    TMDb ID
     * @return 原始语言代码；API 不可用或无 key 时返回 null（调用方应视作"未知"，跳过语言相关过滤）
     */
    public String getOriginalLanguage(String mediaType, String tmdbId) {
        if (StringUtils.isBlank(tmdbId) || StringUtils.isBlank(mediaType)) {
            return null;
        }
        String apiKey = openlistConfig.getTmdbApiKey();
        if (StringUtils.isBlank(apiKey)) {
            log.debug("TMDb API Key 未配置，无法查询 originalLanguage");
            return null;
        }
        try {
            JSONObject detail = readObject(tmDbApiService.getDetails(
                    apiKey, tmdbType(mediaType), Integer.parseInt(tmdbId)));
            if (detail == null) {
                log.warn("TMDb 未返回 tmdbId={} 的详情，无法获取 originalLanguage", tmdbId);
                return null;
            }
            return detail.getString("original_language");
        } catch (NumberFormatException e) {
            log.warn("tmdbId 格式非法：{}", tmdbId);
            return null;
        } catch (Exception e) {
            log.warn("查询 TMDb originalLanguage 异常（tmdbId={}）：{}", tmdbId, e.getMessage());
            return null;
        }
    }

    /**
     * 电影详情接口（/movie/{id}）本身带 imdb_id，直接取，不产生额外请求；
     * 剧集详情接口（/tv/{id}）没有该字段，需要多查一次 external_ids。
     * 两种情况都取不到时返回 null——imdbId 为空是允许的降级路径（走标题搜索）。
     */
    private String resolveImdbId(String mediaType, String tmdbId, JSONObject detail) {
        if (TYPE_MOVIE.equalsIgnoreCase(mediaType)) {
            return detail.getString("imdb_id");
        }
        JSONObject externalIds = readObject(
                tmDbApiService.getExternalIds(openlistConfig.getTmdbApiKey(), "tv", Integer.parseInt(tmdbId)));
        return externalIds == null ? null : externalIds.getString("imdb_id");
    }

    /**
     * 取剧集指定季的总集数。
     * <p>
     * 注意季号 0 是**特别篇**（TMDb 约定），不是电影——电影不该走这个方法。
     * </p>
     *
     * @throws IllegalArgumentException 响应无 seasons，或该季不存在
     */
    public int getSeasonEpisodeCount(String tmdbId, int season) {
        String raw = tmDbApiService.getDetails(openlistConfig.getTmdbApiKey(), "tv", Integer.parseInt(tmdbId));
        JSONArray seasons = readArray(raw, "seasons");
        if (seasons == null) {
            throw new IllegalArgumentException("TMDb 未返回剧集 " + tmdbId + " 的季信息");
        }
        for (int i = 0; i < seasons.size(); i++) {
            JSONObject item = seasons.getJSONObject(i);
            Integer number = item.getInteger("season_number");
            if (number != null && number == season) {
                Integer count = item.getInteger("episode_count");
                if (count == null || count <= 0) {
                    throw new IllegalArgumentException("TMDb 中剧集 " + tmdbId + " 第 " + season + " 季的集数无效");
                }
                return count;
            }
        }
        throw new IllegalArgumentException("TMDb 中剧集 " + tmdbId + " 不存在第 " + season + " 季");
    }

    /**
     * 取剧集当前最新一季的季号，供自动订阅热门剧集时决定订哪一季用。
     * <p>
     * 用 TMDb 详情里的 number_of_seasons 做启发式判断（假定季号从 1 连续编到该值，
     * 不含特别篇的第 0 季）。取不到时兜底返回第 1 季。
     * </p>
     */
    public int getLatestSeasonNumber(String tmdbId) {
        JSONObject detail = readObject(tmDbApiService.getDetails(openlistConfig.getTmdbApiKey(), "tv", Integer.parseInt(tmdbId)));
        if (detail == null) {
            return 1;
        }
        Integer number = detail.getInteger("number_of_seasons");
        return (number == null || number < 1) ? 1 : number;
    }

    private TmdbSearchItem toItem(JSONObject json, String mediaType) {
        boolean tv = !TYPE_MOVIE.equalsIgnoreCase(mediaType);
        TmdbSearchItem item = new TmdbSearchItem();
        item.setTmdbId(json.getString("id"));
        item.setMediaType(tv ? TYPE_TV : TYPE_MOVIE);
        item.setTitle(json.getString(tv ? "name" : "title"));
        item.setOriginalTitle(json.getString(tv ? "original_name" : "original_title"));
        item.setYear(extractYear(json.getString(tv ? "first_air_date" : "release_date")));
        item.setPosterPath(json.getString("poster_path"));
        item.setOverview(json.getString("overview"));
        return item;
    }

    /**
     * 从 yyyy-MM-dd 取年份。TMDb 对未定档作品会给空串或非常规值，此时返回 null。
     */
    private String extractYear(String date) {
        if (StringUtils.isBlank(date) || date.length() < 4) {
            return null;
        }
        String year = date.substring(0, 4);
        return year.chars().allMatch(Character::isDigit) ? year : null;
    }

    private String tmdbType(String mediaType) {
        return TYPE_MOVIE.equalsIgnoreCase(mediaType) ? "movie" : "tv";
    }

    private JSONObject readObject(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return JSONObject.parseObject(raw);
        } catch (Exception e) {
            log.warn("TMDb 响应不是合法 JSON：{}", e.getMessage());
            return null;
        }
    }

    private JSONArray readArray(String raw, String key) {
        JSONObject json = readObject(raw);
        return json == null ? null : json.getJSONArray(key);
    }
}
