package com.osr.openliststrm.pt.indexer;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.pt.model.TorrentInfo;
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
    private static final String USER_AGENT = "OSR/1.0 (+PT-Subscription)";

    private final OkHttpClient httpClient;
    private final IndexerRateLimiter rateLimiter;

    public TorznabClient(OkHttpClient sharedOkHttpClient, IndexerRateLimiter rateLimiter) {
        // retryOnConnectionFailure 必须保持开启，不要"为了不重复打站点"再关掉它。
        // 它只覆盖<b>连接建立/复用阶段</b>的失败——那时请求还没被服务端处理过，换一条连接重发
        // 不会产生重复搜索；对任何已经收到 HTTP 响应的请求（含 429/503）它一概不重试，
        // 因此上层按状态码做的限流退避不受影响，两者管的不是同一件事。
        // 关掉它的代价是 keep-alive 竞态直接变成可见失败：共享池保活 5 分钟
        // （见 HttpClientConfig），而 Prowlarr(Kestrel) 默认 KeepAliveTimeout 只有 130 秒，
        // 中间若有 nginx 反代更短。空闲落在这段窗口里的连接服务端已经关闭、本地还当它可用，
        // 请求写进死 socket 读到 EOF，报 "unexpected end of stream on http://host:port/..."。
        // 这种失败在索引器端查不到任何记录——请求压根没进对方的应用层——却会累加 fail_count
        // 触发退避，多几次就把一个健康的索引器自动停用了。
        // 读超时放到 60s——索引器代理到后端站点本就慢，30s 容易把正常响应误判成失败。
        this.httpClient = sharedOkHttpClient.newBuilder()
                .retryOnConnectionFailure(true)
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
        // 这里刻意不记条数：唯一的调用方 RssPollService#pollOne 会在 4 毫秒后打
        // 「索引器[x]拉取到 N 条种子」，同一个数字由传输层和调用方各说一遍，
        // 与当初 ApiInterceptor 和 RequestLogFilter 对同一个请求各打两行是同一个毛病。
        // 下面 search / searchByExternalId 的两行保留——那两条路径的调用方
        // （SearchSupplementService）不会逐索引器再记一次计数，删了就真没有了。
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
     * 任何异常（网络失败、限流冷却、响应非法）均<b>返回 null</b>，不向上抛——
     * 与 {@link #testConnection} 同样的容错哲学，能力探测失败不该阻断后续的标题搜索兜底。
     * <p>
     * <b>失败必须返回 null 而不是 {@link IndexerCapability#NONE}</b>：NONE 是一个合法的探测结果
     * （站点确实不支持任何 ID 搜索），把失败也塌成 NONE，调用方就再也分不清「探明了不支持」与
     * 「压根没探明」。{@link IndexerCapabilityCache} 正是靠这个区分决定缓存策略——
     * 成功的结果永久缓存，失败的只短期缓存并择机重探，否则一次网络抖动会让该索引器
     * 在整个进程生命周期内永远走不到 ID 精确搜索，且没有任何地方说得出为什么。
     * </p>
     */
    public IndexerCapability getCaps(PtIndexerPlus indexer) {
        try {
            String body = execute(indexer, buildUrl(indexer, "caps"));
            return TorznabCapsParser.parse(body);
        } catch (Exception e) {
            log.warn("索引器[{}]能力探测失败：{}", indexer.getName(), e.getMessage());
            return null;
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
            // season 判空不是多余的防御：它是 Integer，String.valueOf(Integer) 解析到
            // String.valueOf(Object)，为 null 时会得到字面量 "null" 并原样拼进 URL
            // （&season=null），索引器多半直接 400。订阅的 season 理论上非空，
            // 但 SubscriptionMatcher 明确把 sub.getSeason()==null 当作可能状态处理，
            // 这里不能比它更乐观。判不出季号就不带该参数，退化为按 ID 全量搜索。
            if (season != null) {
                builder.addQueryParameter("season", String.valueOf(season));
            }
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
     * @throws IndexerHttpException         响应非 2xx，异常携带状态码与 Retry-After 供调用方决定退避策略
     * @throws IndexerBackpressureException 该索引器正处于限流冷却期，或等许可超时（快速失败，不挂起线程）——
     *                                     请求没发出去，调用方不应计入失败次数
     * @throws IOException                  网络异常
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
