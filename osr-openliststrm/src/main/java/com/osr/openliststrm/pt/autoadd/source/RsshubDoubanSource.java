package com.osr.openliststrm.pt.autoadd.source;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;
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
 * RSSHub 豆瓣榜单数据源。TMDb 的热门榜以欧美内容为主，华语与日韩内容的「热」在豆瓣上才反映得出来。
 * <p>
 * <b>地址必须可自定义</b>：官方 RSSHub 实例常年限流/不可用，本功能实际可用的前提是用户自建实例。
 * 因此有两层配置——全局 {@code openlist.rsshub.base-url} 管「实例在哪」（换实例只改一处），
 * 规则上的 {@code source_url} 管「拉哪个榜单」。{@code source_url} 填成完整 http(s) URL 时
 * 直接使用、忽略 base，于是这个源顺带也能指向任意 RSS 地址。
 * </p>
 * <p>
 * <b>拉回来的条目没有 tmdbId</b>（RSSHub 的豆瓣路由不返回 IMDb/TMDb ID），补全交给
 * {@code PopularItemResolver}，见 {@link PopularSource#fetch} 的契约。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class RsshubDoubanSource implements PopularSource {

    public static final String SOURCE_RSSHUB_DOUBAN = "RSSHUB_DOUBAN";

    /** 与 {@code TorznabClient} 同款的可识别 UA：出问题时对方能认出是谁在打 */
    private static final String USER_AGENT = "OSR/1.0 (+PT-Subscription)";

    /** 自建 RSSHub 冷启动时首个请求可能要跑一遍无头浏览器，20 秒偏紧 */
    private static final int READ_TIMEOUT_SECONDS = 45;

    private final OkHttpClient httpClient;
    private final OpenlistConfig openlistConfig;

    public RsshubDoubanSource(OkHttpClient sharedOkHttpClient, OpenlistConfig openlistConfig) {
        this.httpClient = sharedOkHttpClient.newBuilder()
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
        this.openlistConfig = openlistConfig;
    }

    @Override
    public boolean supports(String source) {
        return SOURCE_RSSHUB_DOUBAN.equals(source);
    }

    @Override
    public List<PopularItem> fetch(PtAutoAddRulePlus rule) {
        HttpUrl url = resolveUrl(rule.getSourceUrl(), openlistConfig.getRsshubBaseUrl(), rule.getId());
        if (url == null) {
            return List.of();
        }
        String body;
        try {
            body = execute(url);
        } catch (IOException e) {
            log.warn("热门自动订阅规则[{}]拉取 RSS 失败 url={}：{}", rule.getId(), safe(url), e.getMessage(), e);
            return List.of();
        }
        List<PopularItem> items;
        try {
            items = DoubanRssParser.parse(body);
        } catch (IllegalArgumentException e) {
            log.warn("热门自动订阅规则[{}]的 RSS 响应不是合法 XML url={}：{}", rule.getId(), safe(url), e.getMessage(), e);
            return List.of();
        }
        // 豆瓣榜单里电影剧集常混在一起，而 RSSHub 给的信息不足以判断条目属于哪一类，
        // 一律按规则选定的类型处理：类型不符的条目会在 TMDb 搜索那步匹配不上而被跳过。
        for (PopularItem item : items) {
            item.setMediaType(rule.getMediaType());
        }
        log.info("热门自动订阅规则[{}]从 RSS 拉取到 {} 个候选 url={}", rule.getId(), items.size(), safe(url));
        return items;
    }

    /**
     * 组装最终地址：完整 URL 直接用，路由路径与 base 拼接。
     *
     * @return 地址不可用时返回 null（已 warn），调用方按「本轮没有候选」处理
     */
    static HttpUrl resolveUrl(String sourceUrl, String baseUrl, Integer ruleId) {
        String path = StringUtils.trimToNull(sourceUrl);
        if (path == null) {
            log.warn("热门自动订阅规则[{}]未配置 RSS 地址，跳过", ruleId);
            return null;
        }
        if (path.regionMatches(true, 0, "http://", 0, 7) || path.regionMatches(true, 0, "https://", 0, 8)) {
            HttpUrl url = HttpUrl.parse(path);
            if (url == null) {
                log.warn("热门自动订阅规则[{}]的 RSS 地址不是合法 URL：{}", ruleId, path);
            }
            return url;
        }
        String base = StringUtils.trimToNull(baseUrl);
        if (base == null) {
            log.warn("热门自动订阅规则[{}]配的是路由路径[{}]，但全局 RSSHub 服务地址未配置，跳过。"
                    + "请在参数设置里填 openlist.rsshub.base-url，或把这里改成完整 URL", ruleId, path);
            return null;
        }
        HttpUrl parsedBase = HttpUrl.parse(base);
        if (parsedBase == null) {
            log.warn("全局 RSSHub 服务地址不是合法 URL：{}", base);
            return null;
        }
        // 用 HttpUrl 拼而不是字符串拼接：base 带访问码（?key=xxx）时 query 要保留，
        // 而路径要接在 base 已有 path 之后（RSSHub 挂在子路径下是常见部署方式）
        return parsedBase.newBuilder()
                .addPathSegments(path.replaceFirst("^/+", ""))
                .build();
    }

    /**
     * 日志用地址：<b>去掉 query 保留 path</b>。RSSHub 实例的访问码通常就挂在 query 上
     * （{@code ?key=xxx}），打完整 URL 等于把它写进保留 7 天的日志文件；而 path
     * （{@code /douban/movie/weekly/...}）本身已经足够认出这是哪个榜单。
     * 与 TMDb 回源日志只打 encodedPath 是同一条理由。
     */
    private static String safe(HttpUrl url) {
        return url.newBuilder().query(null).build().toString();
    }

    private String execute(HttpUrl url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? null : body.string();
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            return text;
        }
    }
}
