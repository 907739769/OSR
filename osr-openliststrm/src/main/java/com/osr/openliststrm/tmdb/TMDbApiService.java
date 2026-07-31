package com.osr.openliststrm.tmdb;

import com.osr.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TMDb 网络请求封装，含进程内短期缓存、数据库缓存、指数退避重试、速率限制。
 */
@Slf4j
@Service
public class TMDbApiService {
    private static final String BASE = "https://api.tmdb.org/3";

    /** 进程内短期缓存的 Spring Cache 名称，配置见 CacheConfig（10 分钟 TTL，摊薄同一批量任务内的重复请求） */
    private static final String L1_CACHE_NAME = "tmdbRaw";

    // --- 速率限制 ---
    /** 最多4个并发请求 */
    private static final Semaphore RATE_LIMITER = new Semaphore(4);
    /** 请求最小间隔 250ms（≈4 req/s，符合 TMDb 40req/10s 限制） */
    private static final long MIN_INTERVAL_MS = 250;
    private static final AtomicLong LAST_REQUEST_TIME = new AtomicLong(0);

    // --- 重试配置 ---
    private static final int MAX_RETRIES = 3;

    private final OkHttpClient http;

    @Autowired
    private TmdbCacheService tmdbCacheService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private OpenlistConfig config;

    public TMDbApiService(@Qualifier("sharedOkHttpClient") OkHttpClient sharedClient) {
        this.http = sharedClient.newBuilder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 搜索接口
     * 优化点: 去掉 apiKey 参与 key 生成；返回 String 防止引用污染
     */
    public String search(String apiKey, String type, String query, String year) {
        HttpUrl.Builder b = Objects.requireNonNull(HttpUrl.parse(BASE + "/search/" + type)).newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("query", query)
                .addQueryParameter("language", language());
        if (year != null && !year.isEmpty()) {
            String yearKey = "movie".equals(type) ? "primary_release_year" : "first_air_date_year";
            b.addQueryParameter(yearKey, year);
        }
        Request req = new Request.Builder().url(b.build()).get().build();
        return executeAndReturnString(req, "search");
    }

    /**
     * 获取详情
     */
    public String getDetails(String apiKey, String type, int id) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/" + type + "/" + id))
                .newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("language", language())
                .addQueryParameter("append_to_response", "credits,videos")
                .build();
        Request req = new Request.Builder().url(url).get().build();
        return executeAndReturnString(req, "getDetails");
    }

    /**
     * 获取备用标题
     */
    public String getAlternativeTitles(String apiKey, String type, int id) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/" + type + "/" + id + "/alternative_titles"))
                .newBuilder().addQueryParameter("api_key", apiKey).build();
        Request req = new Request.Builder().url(url).get().build();
        return executeAndReturnString(req, "getAlternativeTitles");
    }

    /**
     * 获取季集列表（TV 专用）
     * 接口: GET /tv/{id}/season/{season_number}
     */
    public String getSeasonEpisodes(String apiKey, int tvId, int seasonNumber) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/tv/" + tvId + "/season/" + seasonNumber))
                .newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("language", language())
                .build();
        Request req = new Request.Builder().url(url).get().build();
        return executeAndReturnString(req, "getSeasonEpisodes");
    }

    /**
     * 获取剧集图片（TV 专用）
     */
    public String getTvImages(String apiKey, int tvId) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/tv/" + tvId + "/images"))
                .newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("include_image_language", "zh,en,null")
                .build();
        Request req = new Request.Builder().url(url).get().build();
        return executeAndReturnString(req, "getTvImages");
    }

    /**
     * 获取电影图片（Movie 专用）
     */
    public String getMovieImages(String apiKey, int movieId) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/movie/" + movieId + "/images"))
                .newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("include_image_language", "zh,en,null")
                .build();
        Request req = new Request.Builder().url(url).get().build();
        return executeAndReturnString(req, "getMovieImages");
    }

    /**
     * 获取外部 ID（IMDb、TVDb 等）
     */
    public String getExternalIds(String apiKey, String type, int id) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/" + type + "/" + id + "/external_ids"))
                .newBuilder()
                .addQueryParameter("api_key", apiKey)
                .build();
        Request req = new Request.Builder().url(url).get().build();
        return executeAndReturnString(req, "getExternalIds");
    }

    /**
     * 获取电影上映/分级信息（Movie 专用）
     */
    public String getMovieReleaseDates(String apiKey, int movieId) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/movie/" + movieId + "/release_dates"))
                .newBuilder()
                .addQueryParameter("api_key", apiKey)
                .build();
        Request req = new Request.Builder().url(url).get().build();
        return executeAndReturnString(req, "getMovieReleaseDates");
    }

    /**
     * 获取季级别图片（TV 专用）
     */
    public String getTvSeasonImages(String apiKey, int tvId, int seasonNumber) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/tv/" + tvId + "/season/" + seasonNumber + "/images"))
                .newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("include_image_language", "zh,en,null")
                .build();
        Request req = new Request.Builder().url(url).get().build();
        return executeAndReturnString(req, "getTvSeasonImages");
    }

    /**
     * 获取剧集内容分级（TV 专用）
     */
    public String getTvContentRatings(String apiKey, int tvId) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/tv/" + tvId + "/content_ratings"))
                .newBuilder()
                .addQueryParameter("api_key", apiKey)
                .build();
        Request req = new Request.Builder().url(url).get().build();
        return executeAndReturnString(req, "getTvContentRatings");
    }

    /**
     * 榜单接口：热门/流行趋势
     * 接口: GET /trending/{movie|tv}/{day|week}
     */
    public String getTrending(String apiKey, String type, String timeWindow, int page) {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/trending/" + type + "/" + timeWindow))
                .newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("language", language())
                .addQueryParameter("page", String.valueOf(page))
                .build();
        Request req = new Request.Builder().url(url).get().build();
        return executeAndReturnString(req, "getTrending");
    }

    /**
     * 榜单接口：条件发现（评分/类型/地区等过滤）
     * 接口: GET /discover/{movie|tv}
     */
    public String getDiscover(String apiKey, String type, java.util.Map<String, String> params, int page) {
        HttpUrl.Builder b = Objects.requireNonNull(HttpUrl.parse(BASE + "/discover/" + type)).newBuilder()
                .addQueryParameter("api_key", apiKey)
                .addQueryParameter("language", language())
                .addQueryParameter("page", String.valueOf(page));
        if (params != null) {
            params.forEach(b::addQueryParameter);
        }
        Request req = new Request.Builder().url(b.build()).get().build();
        return executeAndReturnString(req, "getDiscover");
    }

    private String language() {
        return config.getTmdbMetadataLanguage();
    }

    // ======================== 核心执行逻辑 ========================

    /**
     * 带进程内短期缓存 + 数据库缓存 + 重试 + 速率限制的执行方法。
     * 两层缓存共用同一个 cacheKey（URL 去掉 api_key 后取 MD5），避免出现两套 key 生成方式不一致的问题：
     * - L1（进程内，10分钟 TTL）：摊薄同一批量刮削任务内对同一部剧/季的重复请求，减少 DB 查询次数
     * - L2（数据库，24小时 TTL，跨重启持久化）：唯一的权威缓存
     */
    private String executeAndReturnString(Request req, String methodName) {
        String cacheKey = buildCacheKey(req);
        String cacheType = methodName;
        Cache l1 = cacheManager.getCache(L1_CACHE_NAME);
        String l1Key = cacheType + ":" + cacheKey;

        // 1. 查进程内短期缓存
        if (l1 != null) {
            Cache.ValueWrapper wrapper = l1.get(l1Key);
            if (wrapper != null) {
                return (String) wrapper.get();
            }
        }

        // 2. 查数据库缓存
        try {
            String cached = tmdbCacheService.getCachedResponse(cacheKey, cacheType);
            if (cached != null) {
                if (l1 != null) l1.put(l1Key, cached);
                return cached;
            }
        } catch (Exception e) {
            log.debug("查询TMDb数据库缓存异常（忽略）: {}", e.getMessage());
        }

        // 3. 带重试的 HTTP 调用
        String result = executeWithRetry(req, methodName);

        // 4. 写入数据库缓存 + 进程内缓存
        if (result != null) {
            try {
                tmdbCacheService.cacheResponse(cacheKey, cacheType, result, 0);
            } catch (Exception e) {
                log.debug("写入TMDb数据库缓存异常（忽略）: {}", e.getMessage());
            }
            if (l1 != null) l1.put(l1Key, result);
        }
        return result;
    }

    /**
     * 带重试的执行：404/401/403 等客户端错误重试没有意义直接放弃；
     * 429（限流，尊重 Retry-After）与 5xx（服务端错误）、网络层异常才指数退避重试。
     */
    private String executeWithRetry(Request req, String methodName) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return doHttpCall(req);
            } catch (TMDbHttpException e) {
                if (!isRetryable(e.statusCode)) {
                    log.warn("TMDb API请求失败（HTTP {}，不重试）: {} error={}", e.statusCode, methodName, e.getMessage());
                    return null;
                }
                if (attempt == MAX_RETRIES) {
                    log.error("TMDb API请求失败，已重试{}次: {} error={}", MAX_RETRIES, methodName, e.getMessage());
                    return null;
                }
                long delay = e.retryAfterMillis > 0 ? e.retryAfterMillis : backoffMillis(attempt);
                if (!sleepQuietly(delay)) return null;
                log.warn("TMDb API请求失败，第{}次重试（{}ms后，HTTP {}）: {} error={}",
                        attempt + 1, delay, e.statusCode, methodName, e.getMessage());
            } catch (IOException e) {
                if (attempt == MAX_RETRIES) {
                    log.error("TMDb API请求失败，已重试{}次: {} error={}", MAX_RETRIES, methodName, e.getMessage());
                    return null;
                }
                long delay = backoffMillis(attempt);
                if (!sleepQuietly(delay)) return null;
                log.warn("TMDb API请求失败，第{}次重试（{}ms后）: {} error={}",
                        attempt + 1, delay, methodName, e.getMessage());
            }
        }
        return null;
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private long backoffMillis(int attempt) {
        return (long) Math.pow(2, attempt) * 1000L; // 1s, 2s, 4s
    }

    private boolean sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 执行单次 HTTP 调用（含速率限制）
     */
    private String doHttpCall(Request req) throws IOException {
        // 速率限制：等待可用许可
        try {
            RATE_LIMITER.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("速率限制等待被中断", e);
        }
        try {
            // 保证最小间隔：每个线程原子地为自己预约下一个可用时间槽（不早于"上一槽+间隔"，也不早于当前时刻），
            // 避免并发线程同时读到旧的 lastTime、各自睡眠后又同时发起请求，导致限流形同虚设
            long scheduledTime = LAST_REQUEST_TIME.updateAndGet(
                    prev -> Math.max(prev + MIN_INTERVAL_MS, System.currentTimeMillis()));
            long waitMillis = scheduledTime - System.currentTimeMillis();
            if (waitMillis > 0) {
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("请求间隔等待被中断", e);
                }
            }

            try (Response resp = http.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) {
                    log.warn("TMDb请求失败，HTTP {}: {}", resp.code(), req.url());
                    throw new TMDbHttpException(resp.code(), parseRetryAfterMillis(resp));
                }
                return resp.body().string();
            }
        } finally {
            RATE_LIMITER.release();
        }
    }

    private long parseRetryAfterMillis(Response resp) {
        String retryAfter = resp.header("Retry-After");
        if (retryAfter == null) return -1;
        try {
            return Long.parseLong(retryAfter.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 携带 HTTP 状态码及 Retry-After（如有）的异常，供重试逻辑按错误类型区分处理。
     */
    private static final class TMDbHttpException extends IOException {
        final int statusCode;
        final long retryAfterMillis;

        TMDbHttpException(int statusCode, long retryAfterMillis) {
            super("HTTP " + statusCode);
            this.statusCode = statusCode;
            this.retryAfterMillis = retryAfterMillis;
        }
    }

    /**
     * 生成缓存 key：对 URL 去掉 api_key 后取 MD5 摘要
     */
    private String buildCacheKey(Request req) {
        HttpUrl url = req.url();
        // 重建 URL 不含 api_key
        HttpUrl.Builder b = url.newBuilder().removeAllQueryParameters("api_key");
        String keyStr = b.build().toString();
        return md5(keyStr);
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 始终可用
            return String.valueOf(input.hashCode());
        }
    }
}
