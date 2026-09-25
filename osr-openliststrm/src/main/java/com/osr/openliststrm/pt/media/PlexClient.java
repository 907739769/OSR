package com.osr.openliststrm.pt.media;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plex 客户端。
 * <p>
 * <b>Plex 没有「按 TMDb ID 查条目」的接口</b>（Emby 有 {@code AnyProviderIdEquals}）：外部 ID 只挂在每个条目的
 * {@code Guid} 数组里（新版 agent 形如 {@code tmdb://1399}），旧版 agent 则写在 {@code guid} 字段
 * （{@code com.plexapp.agents.themoviedb://1399?lang=zh}）。于是只能把剧集库 / 电影库整个列一遍、
 * 自己建「TMDb ID → ratingKey」的索引。对账每 10 分钟一轮、每条订阅查一次，所以索引按服务器 + 类型
 * 缓存 {@link #INDEX_TTL_MILLIS}：一轮对账只拉一次全库，而不是每条订阅各拉一次。
 * <p>
 * 用户维度不适用：Plex 的托管用户要走 plex.tv 换 token，与这里「用服务器 token 查库」不是一回事，
 * {@code userId} 对 Plex 忽略，{@link #listUsers} 用接口默认的空表（配置页退回手填，填了也不用）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class PlexClient implements IMediaServerClient {

    static final String TYPE = "PLEX";

    /** 库索引的缓存时长，与对账周期同量级：刚入库的剧最多晚一轮被看见 */
    static final long INDEX_TTL_MILLIS = 10 * 60_000L;

    /** Plex 的条目类型：1 电影，2 剧集 */
    private static final int TYPE_MOVIE = 1;
    private static final int TYPE_SHOW = 2;

    /** 新版 agent 的外部 ID：tmdb://1399 */
    private static final Pattern NEW_AGENT_TMDB = Pattern.compile("^tmdb://(\\d+)$");
    /** 旧版 agent 的主 guid：com.plexapp.agents.themoviedb://1399?lang=zh */
    private static final Pattern LEGACY_AGENT_TMDB = Pattern.compile("themoviedb://(\\d+)");

    private final OkHttpClient httpClient;

    /** key = 服务器 id + 类型 */
    private final Map<String, CachedIndex> indexCache = new ConcurrentHashMap<>();

    private record CachedIndex(Map<String, List<String>> byTmdbId, long expiresAt) {
    }

    public PlexClient(OkHttpClient sharedOkHttpClient) {
        this.httpClient = sharedOkHttpClient;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public MediaServerProbe testConnection(PtMediaServerPlus config) {
        try {
            JSONObject root = container(get(config, "/", Map.of()));
            String detail = describeServer(root);
            log.info("媒体服务器[{}]连通，{}", config.getName(), detail);
            return MediaServerProbe.success(detail);
        } catch (Exception e) {
            String reason = describeFailure(e);
            log.warn("媒体服务器[{}]连通性测试失败：{}", config.getName(), reason);
            return MediaServerProbe.failure(reason);
        }
    }

    private static String describeServer(JSONObject root) {
        List<String> parts = new ArrayList<>();
        String version = root.getString("version");
        String name = root.getString("friendlyName");
        parts.add(StringUtils.isNotBlank(version) ? "Plex " + version : "Plex");
        if (StringUtils.isNotBlank(name)) {
            parts.add(name);
        }
        return "已连通：" + String.join(" · ", parts);
    }

    /** 口径同 {@code EmbyClient#describeFailure}，只把 401 的提示换成 Plex 的叫法 */
    private static String describeFailure(Exception e) {
        if (e instanceof MediaServerHttpException http) {
            return switch (http.statusCode()) {
                case 401, 403 -> "X-Plex-Token 无效或权限不足（HTTP " + http.statusCode() + "），请在 API Key 一栏填服务器的 Plex Token";
                case 404 -> "服务器返回 404，地址可能不对（若用了反向代理，检查是否漏了路径前缀）";
                case 502, 503, 504 -> "Plex 或其前置代理不可用（HTTP " + http.statusCode() + "）";
                default -> "Plex 返回 HTTP " + http.statusCode();
            };
        }
        if (e instanceof UnknownHostException) {
            return "无法解析主机名，请检查地址中的域名或容器名是否正确";
        }
        if (e instanceof SocketTimeoutException) {
            return "连接超时，请检查地址、端口（Plex 默认 32400）与网络是否可达";
        }
        if (e instanceof ConnectException) {
            return "无法建立连接（对方拒绝），请检查地址、端口以及 Plex 是否已启动";
        }
        String message = e.getMessage();
        return StringUtils.isBlank(message) ? e.getClass().getSimpleName() : message;
    }

    @Override
    public Set<Integer> listEpisodes(PtMediaServerPlus config, String tmdbId, int season) throws IOException {
        return episodeNumbers(config, tmdbId, season);
    }

    @Override
    public Set<Integer> listAllEpisodeNumbers(PtMediaServerPlus config, String tmdbId) throws IOException {
        return episodeNumbers(config, tmdbId, null);
    }

    @Override
    public boolean hasMovie(PtMediaServerPlus config, String tmdbId) throws IOException {
        return !index(config, TYPE_MOVIE).getOrDefault(tmdbId, List.of()).isEmpty();
    }

    /**
     * 观看状态取 token 所属账号的：{@code viewCount > 0} 即看过，{@code lastViewedAt} 是秒级时间戳。
     * 剧集沿用集号那条 allLeaves；电影要单独取一次条目详情（库索引里只存了 ratingKey）。
     */
    @Override
    public WatchState watchState(PtMediaServerPlus config, String tmdbId, Integer season, boolean movie)
            throws IOException {
        Set<Integer> watched = new HashSet<>();
        long lastSeconds = 0;
        List<String> ratingKeys = index(config, movie ? TYPE_MOVIE : TYPE_SHOW).getOrDefault(tmdbId, List.of());
        for (String ratingKey : ratingKeys) {
            String path = movie ? "/library/metadata/" + ratingKey : "/library/metadata/" + ratingKey + "/allLeaves";
            JSONArray items = container(get(config, path, Map.of())).getJSONArray("Metadata");
            if (items == null) {
                continue;
            }
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item.getIntValue("viewCount") <= 0) {
                    continue;
                }
                if (movie) {
                    watched.add(0);
                } else if (item.getInteger("index") != null
                        && (season == null || season.equals(item.getInteger("parentIndex")))) {
                    watched.add(item.getInteger("index"));
                } else {
                    continue;
                }
                lastSeconds = Math.max(lastSeconds, item.getLongValue("lastViewedAt"));
            }
        }
        return new WatchState(watched, lastSeconds > 0 ? new Date(lastSeconds * 1000) : null);
    }

    /**
     * 同一部剧可能出现在多个库里（「电视剧」「动画」各有一份），全部合并。
     *
     * @param season null 表示不看季号（全剧）
     */
    private Set<Integer> episodeNumbers(PtMediaServerPlus config, String tmdbId, Integer season) throws IOException {
        Set<Integer> result = new HashSet<>();
        for (String ratingKey : index(config, TYPE_SHOW).getOrDefault(tmdbId, List.of())) {
            JSONArray leaves = container(get(config, "/library/metadata/" + ratingKey + "/allLeaves", Map.of()))
                    .getJSONArray("Metadata");
            if (leaves == null) {
                continue;
            }
            for (int i = 0; i < leaves.size(); i++) {
                JSONObject leaf = leaves.getJSONObject(i);
                Integer index = leaf.getInteger("index");
                if (index == null) {
                    continue;
                }
                if (season == null || season.equals(leaf.getInteger("parentIndex"))) {
                    result.add(index);
                }
            }
        }
        return result;
    }

    /** 某类条目的「TMDb ID → ratingKey 列表」索引，带缓存 */
    private Map<String, List<String>> index(PtMediaServerPlus config, int itemType) throws IOException {
        String key = config.getId() + "|" + itemType;
        long now = System.currentTimeMillis();
        CachedIndex cached = indexCache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.byTmdbId();
        }
        Map<String, List<String>> built = buildIndex(config, itemType);
        indexCache.put(key, new CachedIndex(built, now + INDEX_TTL_MILLIS));
        return built;
    }

    private Map<String, List<String>> buildIndex(PtMediaServerPlus config, int itemType) throws IOException {
        String sectionType = itemType == TYPE_SHOW ? "show" : "movie";
        Map<String, List<String>> result = new HashMap<>();
        JSONArray sections = container(get(config, "/library/sections", Map.of())).getJSONArray("Directory");
        if (sections == null) {
            return result;
        }
        for (int s = 0; s < sections.size(); s++) {
            JSONObject section = sections.getJSONObject(s);
            if (!sectionType.equals(section.getString("type"))) {
                continue;
            }
            Map<String, String> query = new LinkedHashMap<>();
            query.put("type", String.valueOf(itemType));
            query.put("includeGuids", "1");
            JSONArray items = container(get(config, "/library/sections/" + section.getString("key") + "/all", query))
                    .getJSONArray("Metadata");
            if (items == null) {
                continue;
            }
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                String tmdbId = tmdbIdOf(item);
                if (tmdbId != null) {
                    result.computeIfAbsent(tmdbId, k -> new ArrayList<>()).add(item.getString("ratingKey"));
                }
            }
        }
        return result;
    }

    /** 先看新版 agent 的 Guid 数组，再看旧版 agent 的主 guid */
    static String tmdbIdOf(JSONObject item) {
        JSONArray guids = item.getJSONArray("Guid");
        if (guids != null) {
            for (int i = 0; i < guids.size(); i++) {
                Matcher m = NEW_AGENT_TMDB.matcher(StringUtils.defaultString(guids.getJSONObject(i).getString("id")));
                if (m.matches()) {
                    return m.group(1);
                }
            }
        }
        Matcher legacy = LEGACY_AGENT_TMDB.matcher(StringUtils.defaultString(item.getString("guid")));
        return legacy.find() ? legacy.group(1) : null;
    }

    private String get(PtMediaServerPlus config, String path, Map<String, String> query) throws IOException {
        HttpUrl parsed = HttpUrl.parse(config.baseUrl() + path);
        if (parsed == null) {
            // 同 EmbyClient#parseUrl：配置非法时 HttpUrl.parse 返回 null，统一转 IOException
            throw new IOException("无法解析媒体服务器地址：" + config.baseUrl() + path);
        }
        HttpUrl.Builder builder = parsed.newBuilder();
        query.forEach(builder::addQueryParameter);
        Request request = new Request.Builder()
                .url(builder.build())
                .header("X-Plex-Token", StringUtils.defaultString(config.getApiKey()))
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new MediaServerHttpException(response.code());
            }
            ResponseBody body = response.body();
            return body == null ? "{}" : body.string();
        }
    }

    /** Plex 的 JSON 都包在 MediaContainer 里；响应不是 JSON（反代转错了服务）时转成 IOException */
    private static JSONObject container(String body) throws IOException {
        try {
            JSONObject root = JSONObject.parse(body);
            JSONObject container = root == null ? null : root.getJSONObject("MediaContainer");
            if (container == null) {
                throw new IOException("响应里没有 MediaContainer，该地址可能并非 Plex");
            }
            return container;
        } catch (JSONException e) {
            throw new IOException("返回的响应不是合法 JSON，该地址可能并非 Plex，或反向代理把请求转给了别的服务", e);
        }
    }
}
