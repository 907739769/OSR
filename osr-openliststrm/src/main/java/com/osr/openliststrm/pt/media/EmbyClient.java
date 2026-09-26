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
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emby / Jellyfin 客户端。两者 API 同源，以下用到的接口完全兼容，故共用一个实现。
 *
 * @author Jack
 */
@Slf4j
@Component
public class EmbyClient implements IMediaServerClient {
    private static final String TYPE = "EMBY";

    private final OkHttpClient httpClient;

    public EmbyClient(OkHttpClient sharedOkHttpClient) {
        this.httpClient = sharedOkHttpClient;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public MediaServerProbe testConnection(PtMediaServerPlus config) {
        try {
            JSONObject info = parseJsonObject(get(config, "/System/Info", Map.of()));
            String detail = describeServer(info);
            log.info("媒体服务器[{}]连通，{}", config.getName(), detail);
            return MediaServerProbe.success(detail);
        } catch (Exception e) {
            String reason = describeFailure(e);
            log.warn("媒体服务器[{}]连通性测试失败：{}", config.getName(), reason);
            return MediaServerProbe.failure(reason);
        }
    }

    /**
     * 把 /System/Info 的响应压成一行给用户看的描述。
     * <p>
     * 字段缺失时整段不写而不是写「未知」——一句「版本：未知」不帮用户做任何判断，
     * 只把真正有用的几段挤下去（同 {@code PtNotifyText#torrentProfile} 的取向）。
     * </p>
     */
    private static String describeServer(JSONObject info) {
        List<String> parts = new ArrayList<>();
        // Jellyfin 与 Emby 在这两个字段上同名，这也正是两者共用一个实现的前提
        String product = info.getString("ProductName");
        String version = info.getString("Version");
        String serverName = info.getString("ServerName");
        if (StringUtils.isNotBlank(product)) {
            parts.add(StringUtils.isNotBlank(version) ? product + " " + version : product);
        } else if (StringUtils.isNotBlank(version)) {
            parts.add("版本 " + version);
        }
        if (StringUtils.isNotBlank(serverName)) {
            parts.add(serverName);
        }
        return parts.isEmpty() ? "已连通" : "已连通：" + String.join(" · ", parts);
    }

    /**
     * 把异常翻成能指导处置的中文。
     * <p>
     * 这一步是本次连通性测试的全部价值所在：401、连不上、返回的根本不是 JSON，
     * 三者要用户去改的东西完全不同（API Key / 地址与网络 / 反向代理配置），
     * 而旧实现把它们压成了同一句话。
     * </p>
     */
    private static String describeFailure(Exception e) {
        if (e instanceof MediaServerHttpException http) {
            return switch (http.statusCode()) {
                case 401, 403 -> "API Key 无效或权限不足（HTTP " + http.statusCode() + "）";
                // 404 最常见的成因是反向代理少配了前缀，或者地址其实指向了别的服务
                case 404 -> "服务器返回 404，地址可能不对（若用了反向代理，检查是否漏了路径前缀）";
                case 502, 503, 504 -> "媒体服务器或其前置代理不可用（HTTP " + http.statusCode() + "）";
                default -> "媒体服务器返回 HTTP " + http.statusCode();
            };
        }
        if (e instanceof UnknownHostException) {
            return "无法解析主机名，请检查地址中的域名或容器名是否正确";
        }
        if (e instanceof SocketTimeoutException) {
            return "连接超时，请检查地址、端口与网络是否可达";
        }
        if (e instanceof ConnectException) {
            return "无法建立连接（对方拒绝），请检查地址、端口以及媒体服务器是否已启动";
        }
        String message = e.getMessage();
        return StringUtils.isBlank(message) ? e.getClass().getSimpleName() : message;
    }

    @Override
    public List<MediaServerUser> listUsers(PtMediaServerPlus config) throws IOException {
        JSONArray items = JSONArray.parse(get(config, "/Users", Map.of()));
        List<MediaServerUser> users = new ArrayList<>();
        if (items == null) {
            return users;
        }
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            String id = item.getString("Id");
            if (StringUtils.isNotBlank(id)) {
                users.add(new MediaServerUser(id, item.getString("Name")));
            }
        }
        return users;
    }

    @Override
    public Set<Integer> listEpisodes(PtMediaServerPlus config, String tmdbId, int season) throws IOException {
        String seriesId = findItemId(config, "Series", tmdbId);
        if (seriesId == null) {
            // 这里刻意<b>不</b>记日志。「查不到」是会持续成立的状态，需要节流；而本类手里只有
            // tmdbId，打出来是一屏「未找到 tmdbId=281281」，一部剧都认不出来。节流与输出都已
            // 上移到 SubscriptionService#reportLibraryCoverage——那里拿得到订阅标题，
            // 判据（这条订阅要的集一个都没入库）也比剧条目在不在更贴近用户关心的事。
            return new HashSet<>();
        }
        return fetchEpisodeNumbers(config, seriesId, season);
    }

    @Override
    public Set<Integer> listAllEpisodeNumbers(PtMediaServerPlus config, String tmdbId) throws IOException {
        String seriesId = findItemId(config, "Series", tmdbId);
        if (seriesId == null) {
            return new HashSet<>();
        }
        // 不带 season 参数即返回全剧条目。只取 IndexNumber，季号在这里刻意不看——
        // 这个方法存在的前提就是「库的分季方式和订阅对不上」
        return fetchEpisodeNumbers(config, seriesId, null);
    }

    /**
     * 拉某剧的集号集合；{@code season} 为 null 时不带季号参数，即取全剧。
     * <p>
     * 按季查与查全剧此前是两段逐字重复的代码，差别只有这一个查询参数。
     * </p>
     */
    private Set<Integer> fetchEpisodeNumbers(PtMediaServerPlus config, String seriesId, Integer season)
            throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        if (season != null) {
            query.put("season", String.valueOf(season));
        }
        if (StringUtils.isNotBlank(config.getUserId())) {
            query.put("userId", config.getUserId());
        }

        Set<Integer> result = new HashSet<>();
        JSONArray items = parseJsonObject(get(config, "/Shows/" + seriesId + "/Episodes", query))
                .getJSONArray("Items");
        if (items == null) {
            return result;
        }
        for (int i = 0; i < items.size(); i++) {
            Integer index = items.getJSONObject(i).getInteger("IndexNumber");
            // 特别篇等条目没有 IndexNumber，直接忽略
            if (index != null) {
                result.add(index);
            }
        }
        return result;
    }

    @Override
    public boolean hasMovie(PtMediaServerPlus config, String tmdbId) throws IOException {
        return findItemId(config, "Movie", tmdbId) != null;
    }

    /**
     * 观看记录是按用户存的：没配用户 ID 时读不到，返回 null（而不是「一集都没看」）。
     * 剧集按季取 Episodes 并带 {@code Fields=UserData}；电影直接看条目自己的 UserData。
     */
    @Override
    public WatchState watchState(PtMediaServerPlus config, String tmdbId, Integer season, boolean movie)
            throws IOException {
        if (StringUtils.isBlank(config.getUserId())) {
            return null;
        }
        JSONArray items;
        if (movie) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("IncludeItemTypes", "Movie");
            query.put("Recursive", "true");
            query.put("AnyProviderIdEquals", "tmdb." + tmdbId);
            query.put("Fields", "UserData");
            query.put("userId", config.getUserId());
            items = parseJsonObject(get(config, "/Items", query)).getJSONArray("Items");
        } else {
            String seriesId = findItemId(config, "Series", tmdbId);
            if (seriesId == null) {
                return WatchState.NONE;
            }
            Map<String, String> query = new LinkedHashMap<>();
            if (season != null) {
                query.put("season", String.valueOf(season));
            }
            query.put("Fields", "UserData");
            query.put("userId", config.getUserId());
            items = parseJsonObject(get(config, "/Shows/" + seriesId + "/Episodes", query)).getJSONArray("Items");
        }
        return toWatchState(items, movie);
    }

    static WatchState toWatchState(JSONArray items, boolean movie) {
        if (items == null || items.isEmpty()) {
            return WatchState.NONE;
        }
        Set<Integer> watched = new HashSet<>();
        Date last = null;
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            JSONObject userData = item.getJSONObject("UserData");
            if (userData == null || !userData.getBooleanValue("Played")) {
                continue;
            }
            Integer index = movie ? Integer.valueOf(0) : item.getInteger("IndexNumber");
            if (index != null) {
                watched.add(index);
            }
            Date played = parseIsoDate(userData.getString("LastPlayedDate"));
            if (played != null && (last == null || played.after(last))) {
                last = played;
            }
        }
        return new WatchState(watched, last);
    }

    /**
     * 带 {@code Fields=MediaStreams} 取条目的流信息。每个条目约 13 KB，所以只按一部作品取，不要全库拉。
     * 同一集多个版本（或一个文件跨多集，{@code IndexNumberEnd}）都合并到对应集号上。
     */
    @Override
    public Map<Integer, MediaStreamInfo> listStreamInfo(PtMediaServerPlus config, String tmdbId, Integer season,
                                                        boolean movie) throws IOException {
        JSONArray items;
        if (movie) {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("IncludeItemTypes", "Movie");
            query.put("Recursive", "true");
            query.put("AnyProviderIdEquals", "tmdb." + tmdbId);
            query.put("Fields", "MediaStreams");
            putUserId(config, query);
            items = parseJsonObject(get(config, "/Items", query)).getJSONArray("Items");
        } else {
            String seriesId = findItemId(config, "Series", tmdbId);
            if (seriesId == null) {
                return new HashMap<>();
            }
            Map<String, String> query = new LinkedHashMap<>();
            if (season != null) {
                query.put("season", String.valueOf(season));
            }
            query.put("Fields", "MediaStreams");
            putUserId(config, query);
            items = parseJsonObject(get(config, "/Shows/" + seriesId + "/Episodes", query)).getJSONArray("Items");
        }
        return toStreamInfoMap(items, movie);
    }

    static Map<Integer, MediaStreamInfo> toStreamInfoMap(JSONArray items, boolean movie) {
        Map<Integer, MediaStreamInfo> result = new HashMap<>();
        if (items == null) {
            return result;
        }
        for (int i = 0; i < items.size(); i++) {
            JSONObject item = items.getJSONObject(i);
            MediaStreamInfo info = toStreamInfo(item.getJSONArray("MediaStreams"));
            if (movie) {
                result.merge(0, info, MediaStreamInfo::merge);
                continue;
            }
            Integer start = item.getInteger("IndexNumber");
            if (start == null) {
                continue;
            }
            Integer end = item.getInteger("IndexNumberEnd");
            for (int no = start; no <= (end != null && end > start ? end : start); no++) {
                result.merge(no, info, MediaStreamInfo::merge);
            }
        }
        return result;
    }

    /** 有视频或音频流才算解析过：没解析过的 STRM 流列表是空的，这时说「没字幕」是错的 */
    static MediaStreamInfo toStreamInfo(JSONArray streams) {
        if (streams == null || streams.isEmpty()) {
            return MediaStreamInfo.NOT_PROBED;
        }
        boolean probed = false;
        List<String> audio = new ArrayList<>();
        List<MediaStreamInfo.SubtitleTrack> subtitles = new ArrayList<>();
        for (int i = 0; i < streams.size(); i++) {
            JSONObject stream = streams.getJSONObject(i);
            String type = stream.getString("Type");
            if ("Video".equals(type)) {
                probed = true;
            } else if ("Audio".equals(type)) {
                probed = true;
                audio.add(stream.getString("Language"));
            } else if ("Subtitle".equals(type)) {
                String title = String.join(" ", StringUtils.defaultString(stream.getString("Title")),
                        StringUtils.defaultString(stream.getString("DisplayTitle"))).trim();
                subtitles.add(new MediaStreamInfo.SubtitleTrack(stream.getString("Language"), title,
                        stream.getBooleanValue("IsExternal")));
            }
        }
        // 只有外挂字幕、视频没解析的情况也算解析过：字幕本身是实打实的
        return new MediaStreamInfo(probed || !subtitles.isEmpty(), audio, subtitles);
    }

    private static void putUserId(PtMediaServerPlus config, Map<String, String> query) {
        if (StringUtils.isNotBlank(config.getUserId())) {
            query.put("userId", config.getUserId());
        }
    }

    /** Emby 的时间是 ISO-8601（带或不带小数秒、以 Z 结尾），解析不了当作没有 */
    private static Date parseIsoDate(String text) {
        if (StringUtils.isBlank(text)) {
            return null;
        }
        try {
            return Date.from(OffsetDateTime.parse(text).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 按 TMDb ID 查找条目，返回其在媒体服务器中的 Id；未找到返回 null。
     */
    private String findItemId(PtMediaServerPlus config, String itemType, String tmdbId) throws IOException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("IncludeItemTypes", itemType);
        query.put("Recursive", "true");
        query.put("AnyProviderIdEquals", "tmdb." + tmdbId);
        if (StringUtils.isNotBlank(config.getUserId())) {
            query.put("userId", config.getUserId());
        }

        String body = get(config, "/Items", query);
        JSONArray items = parseJsonObject(body).getJSONArray("Items");
        if (items == null || items.isEmpty()) {
            return null;
        }
        return items.getJSONObject(0).getString("Id");
    }

    private String get(PtMediaServerPlus config, String path, Map<String, String> query) throws IOException {
        HttpUrl.Builder builder = parseUrl(config.baseUrl() + path);
        query.forEach(builder::addQueryParameter);

        Request request = new Request.Builder()
                .url(builder.build())
                .header("X-Emby-Token", config.getApiKey())
                .header("Accept", "application/json")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                // 带上状态码，让连通性测试分得出「Key 错」「地址错」「对端挂了」
                throw new MediaServerHttpException(response.code());
            }
            ResponseBody body = response.body();
            return body == null ? "{}" : body.string();
        }
    }

    /**
     * 解析 URL；host/端口等配置非法时 {@link HttpUrl#parse} 返回 null，
     * 紧接着调用 newBuilder() 会 NPE（未受检异常）。这里统一转成 IOException，
     * 与"网络异常 → IOException → 调用方本轮跳过、下轮重来"的契约保持一致。
     */
    private HttpUrl.Builder parseUrl(String url) throws IOException {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            throw new IOException("无法解析媒体服务器地址：" + url);
        }
        return parsed.newBuilder();
    }

    /**
     * 解析 JSON 对象；反向代理故障等场景下响应体不是合法 JSON，
     * FastJSON2 会抛出未受检的 JSONException。这里转成 IOException，避免调度线程
     * 收到一个 catch (IOException) 捕不到的异常类型。
     */
    private JSONObject parseJsonObject(String body) throws IOException {
        try {
            return JSONObject.parse(body);
        } catch (JSONException e) {
            throw new IOException("返回的响应不是合法 JSON，该地址可能并非 Emby/Jellyfin，"
                    + "或反向代理把请求转给了别的服务：" + truncate(body), e);
        }
    }

    /** 异常消息里只截取响应体前 200 字符，避免把整个 HTML 错误页塞进异常消息 */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(截断)";
    }
}
