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
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    public boolean testConnection(PtMediaServerPlus config) {
        try {
            String body = get(config, "/System/Info", Map.of());
            JSONObject info = parseJsonObject(body);
            log.info("媒体服务器[{}]连通，版本：{}", config.getName(), info.getString("Version"));
            return true;
        } catch (Exception e) {
            log.warn("媒体服务器[{}]连通性测试失败：{}", config.getName(), e.getMessage());
            return false;
        }
    }

    @Override
    public Set<Integer> listEpisodes(PtMediaServerPlus config, String tmdbId, int season) throws IOException {
        Set<Integer> result = new HashSet<>();

        String seriesId = findItemId(config, "Series", tmdbId);
        if (seriesId == null) {
            log.debug("媒体服务器中未找到 tmdbId={} 的剧集", tmdbId);
            return result;
        }

        Map<String, String> query = new LinkedHashMap<>();
        query.put("season", String.valueOf(season));
        if (StringUtils.isNotBlank(config.getUserId())) {
            query.put("userId", config.getUserId());
        }

        String body = get(config, "/Shows/" + seriesId + "/Episodes", query);
        JSONArray items = parseJsonObject(body).getJSONArray("Items");
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
                throw new IOException("媒体服务器返回 HTTP " + response.code());
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
            throw new IOException("媒体服务器返回的响应不是合法 JSON：" + truncate(body), e);
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
