package com.ruoyi.openliststrm.pt.indexer;

import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.ruoyi.openliststrm.pt.model.TorrentInfo;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Torznab 索引器客户端。负责 HTTP 拉取，解析委托 {@link TorznabParser}。
 * <p>
 * 所有出站请求都经由 {@link IndexerRateLimiter}——这里是打向 PT 站点的唯一收口，
 * 把节流放在这一层而不是各个调用方，能保证 RSS 轮询、关键词搜索、ID 精确搜索、caps 探测
 * 共用同一份并发上限与请求间隔，任何新增的调用路径都自动受约束。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class TorznabClient {

    /**
     * 固定 UA。OkHttp 默认发 {@code okhttp/x.y.z}，部分 PT 站与 CF 前置会对默认 UA
     * 做拦截或风控标记，用一个可识别的固定值更稳，出问题时对方也能认出是谁在打。
     */
    private static final String USER_AGENT = "OpenList-strm-RuoYi/1.0 (+PT-Subscription)";

    private final OkHttpClient httpClient;
    private final IndexerRateLimiter rateLimiter;

    public TorznabClient(OkHttpClient sharedOkHttpClient, IndexerRateLimiter rateLimiter) {
        // 关掉 OkHttp 默认开启的 retryOnConnectionFailure：它会在连接层失败时静默重发，
        // 对 PT 场景等于把每次请求悄悄变成两次，且上层的退避逻辑完全看不见这次重试。
        // 读超时收到 60s——索引器代理到后端站点本就慢，30s 容易把正常响应误判成失败进而触发重试。
        this.httpClient = sharedOkHttpClient.newBuilder()
                .retryOnConnectionFailure(false)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
        this.rateLimiter = rateLimiter;
    }

    /**
     * 拉取索引器的最新发布列表（t=search 不带 q，即 RSS 流）。
     *
     * @throws IOException              网络异常或 HTTP 非 2xx
     * @throws IllegalArgumentException 响应体不是合法 Torznab XML
     */
    public List<TorrentInfo> fetch(PtIndexerPlus indexer) throws IOException {
        HttpUrl url = buildUrl(indexer, "search");
        String body = execute(indexer, url);
        List<TorrentInfo> list = TorznabParser.parse(body);
        for (TorrentInfo info : list) {
            info.setIndexerId(indexer.getId());
        }
        log.debug("索引器[{}]返回{}条种子", indexer.getName(), list.size());
        return list;
    }

    /**
     * 按关键词搜索索引器（t=search 且带 q 参数），用于订阅缺集的主动补搜。
     *
     * @throws IOException              网络异常或 HTTP 非 2xx
     * @throws IllegalArgumentException 响应体不是合法 Torznab XML
     */
    public List<TorrentInfo> search(PtIndexerPlus indexer, String keyword) throws IOException {
        HttpUrl url = buildUrl(indexer, "search").newBuilder()
                .addQueryParameter("q", keyword)
                .build();
        String body = execute(indexer, url);
        List<TorrentInfo> list = TorznabParser.parse(body);
        for (TorrentInfo info : list) {
            info.setIndexerId(indexer.getId());
        }
        log.debug("索引器[{}]关键词搜索[{}]返回{}条种子", indexer.getName(), keyword, list.size());
        return list;
    }

    /**
     * 连通性测试：调用 t=caps 能力接口。任何异常均视为不连通，不向上抛。
     */
    public boolean testConnection(PtIndexerPlus indexer) {
        try {
            execute(indexer, buildUrl(indexer, "caps"));
            return true;
        } catch (Exception e) {
            log.warn("索引器[{}]连通性测试失败：{}", indexer.getName(), e.getMessage());
            return false;
        }
    }

    /**
     * 探测索引器 ID 搜索能力（t=caps），用于判断是否可以发起 imdbid/tmdbid 精确搜索。
     * 任何异常（网络失败、响应非法）均返回 {@link IndexerCapability#NONE}，不向上抛——
     * 与 {@link #testConnection} 同样的容错哲学，能力探测失败不该阻断后续的标题搜索兜底。
     */
    public IndexerCapability getCaps(PtIndexerPlus indexer) {
        try {
            String body = execute(indexer, buildUrl(indexer, "caps"));
            return TorznabCapsParser.parse(body);
        } catch (Exception e) {
            log.warn("索引器[{}]能力探测失败：{}", indexer.getName(), e.getMessage());
            return IndexerCapability.NONE;
        }
    }

    /**
     * 获取索引器支持的分类树（t=caps 响应中的 categories 节点），供前端分类下拉使用。
     * 与 {@link #getCaps} 不同，本方法不吞异常——前端需要区分"未获取"与"获取失败"并提示具体原因。
     *
     * @throws IOException              网络异常或 HTTP 非 2xx
     * @throws IllegalArgumentException 索引器地址非法
     */
    public List<CategoryOption> getCategories(PtIndexerPlus indexer) throws IOException {
        String body = execute(indexer, buildUrl(indexer, "caps"));
        return TorznabCapsParser.parseCategories(body);
    }

    /**
     * 按外部 ID（IMDb/TMDB）精确搜索，用于订阅搜索补集的第一优先级。
     *
     * @param movie      true=电影(t=movie)，false=剧集(t=tvsearch)
     * @param idParamName "imdbid" 或 "tmdbid"
     * @param idValue    对应的 ID 值
     * @param season     剧集季号，电影传 null
     * @param episode    剧集集号，季包搜索或电影传 null
     * @throws IOException              网络异常或 HTTP 非 2xx
     * @throws IllegalArgumentException 响应体不是合法 Torznab XML
     */
    public List<TorrentInfo> searchByExternalId(PtIndexerPlus indexer, boolean movie,
                                                 String idParamName, String idValue,
                                                 Integer season, Integer episode) throws IOException {
        HttpUrl.Builder builder = buildUrl(indexer, movie ? "movie" : "tvsearch").newBuilder()
                .addQueryParameter(idParamName, idValue);
        if (!movie) {
            builder.addQueryParameter("season", String.valueOf(season));
            if (episode != null) {
                builder.addQueryParameter("ep", String.valueOf(episode));
            }
        }
        String body = execute(indexer, builder.build());
        List<TorrentInfo> list = TorznabParser.parse(body);
        for (TorrentInfo info : list) {
            info.setIndexerId(indexer.getId());
        }
        log.debug("索引器[{}]按{}={}搜索返回{}条种子", indexer.getName(), idParamName, idValue, list.size());
        return list;
    }

    private HttpUrl buildUrl(PtIndexerPlus indexer, String type) {
        HttpUrl base = HttpUrl.parse(indexer.getUrl());
        if (base == null) {
            throw new IllegalArgumentException("索引器地址非法：" + indexer.getUrl());
        }
        HttpUrl.Builder builder = base.newBuilder()
                .addQueryParameter("apikey", indexer.getApiKey())
                .addQueryParameter("t", type);
        if (!"caps".equals(type) && StringUtils.isNotBlank(indexer.getCategories())) {
            builder.addQueryParameter("cat", indexer.getCategories());
        }
        return builder.build();
    }

    /**
     * 发起一次受限流保护的 GET，返回响应体文本。
     *
     * @throws IndexerHttpException 响应非 2xx，异常携带状态码与 Retry-After 供调用方决定退避策略
     * @throws IOException          网络异常，或该索引器正处于限流冷却期（快速失败，不挂起线程）
     */
    private String execute(PtIndexerPlus indexer, HttpUrl url) throws IOException {
        return rateLimiter.execute(indexer.getId(), () -> {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IndexerHttpException(response.code(), parseRetryAfter(response));
                }
                ResponseBody body = response.body();
                return body == null ? "" : body.string();
            }
        });
    }

    /**
     * 解析 {@code Retry-After} 响应头的 delta-seconds 形式；缺失或非数字返回 null。
     * HTTP-date 形式在索引器实现里几乎不出现，解析不了按"未给出"处理即可——
     * 调用方本就有自己的默认冷却时长兜底，这里不值得为罕见格式引入日期解析。
     */
    private Integer parseRetryAfter(Response response) {
        String raw = response.header("Retry-After");
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            int seconds = Integer.parseInt(raw.trim());
            return seconds > 0 ? seconds : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
