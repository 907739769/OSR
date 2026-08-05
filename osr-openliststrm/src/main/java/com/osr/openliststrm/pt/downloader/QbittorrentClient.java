package com.osr.openliststrm.pt.downloader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrentFile;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * qBittorrent Web API v2 客户端。
 * <p>
 * SID 按下载器 ID 缓存在内存中，遇到 403（会话过期）时自动重新登录并重试一次。
 * 进程重启后缓存丢失，首次调用重新登录即可，无需持久化。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class QbittorrentClient implements IDownloaderClient {

    private static final String TYPE = "QBITTORRENT";
    private static final String OK = "Ok.";

    private final OkHttpClient httpClient;

    /** downloaderId -> SID */
    private final Map<Integer, String> sidCache = new ConcurrentHashMap<>();

    public QbittorrentClient(OkHttpClient sharedOkHttpClient) {
        this.httpClient = sharedOkHttpClient;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean testConnection(PtDownloaderPlus config) {
        try {
            String version = get(config, "/api/v2/app/version", Map.of());
            log.info("下载器[{}]连通，版本：{}", config.getName(), version);
            return true;
        } catch (Exception e) {
            log.warn("下载器[{}]连通性测试失败：{}", config.getName(), e.getMessage());
            return false;
        }
    }

    @Override
    public void addTorrent(PtDownloaderPlus config, String downloadUrl, String savePath, String tag, boolean paused)
            throws IOException {
        FormBody.Builder builder = new FormBody.Builder()
                .add("urls", downloadUrl)
                .add("savepath", savePath)
                .add("tags", tag);
        if (paused) {
            // qB 4.x 认 paused、5.x 改叫 stopped。两个都发：qB 会忽略自己不认识的字段，
            // 省掉一次版本探测，也不用在两个大版本之间二选一
            builder.add("paused", "true").add("stopped", "true");
        }
        String response = post(config, "/api/v2/torrents/add", builder.build());
        if (!OK.equalsIgnoreCase(response.trim())) {
            throw new IOException("qBittorrent 拒绝添加种子，响应：" + response);
        }
        log.info("已推送种子到下载器[{}]{}：{}", config.getName(),
                paused ? "（暂停态，待选完目标集文件再启动）" : "", maskUrl(downloadUrl));
    }

    /** qB 4.x 的启动端点。5.0 起改名为 {@link #START_ENDPOINT_MODERN} 并移除了本端点 */
    private static final String START_ENDPOINT_LEGACY = "/api/v2/torrents/resume";

    /** qB 5.x 的启动端点 */
    private static final String START_ENDPOINT_MODERN = "/api/v2/torrents/start";

    /** downloaderId -> 探测出来的可用启动端点，首次成功后缓存，避免之后每次都先撞一个 404 */
    private final Map<Integer, String> startEndpointCache = new ConcurrentHashMap<>();

    @Override
    public void resumeTorrent(PtDownloaderPlus config, String hash) throws IOException {
        String cached = startEndpointCache.get(config.getId());
        if (cached != null) {
            post(config, cached, hashesBody(hash));
            log.info("下载器[{}] 已启动种子[{}]", config.getName(), hash);
            return;
        }
        // 先试旧端点（存量用户多在 4.x），404 再试新的，成功的那个记下来。
        // 两个都失败时把首次异常挂成 suppressed，免得"5.x 端点不存在"盖掉真正的网络故障
        try {
            post(config, START_ENDPOINT_LEGACY, hashesBody(hash));
            startEndpointCache.put(config.getId(), START_ENDPOINT_LEGACY);
        } catch (IOException legacyFailed) {
            try {
                post(config, START_ENDPOINT_MODERN, hashesBody(hash));
                startEndpointCache.put(config.getId(), START_ENDPOINT_MODERN);
            } catch (IOException modernFailed) {
                modernFailed.addSuppressed(legacyFailed);
                throw modernFailed;
            }
        }
        log.info("下载器[{}] 已启动种子[{}]", config.getName(), hash);
    }

    @Override
    public void deleteTorrent(PtDownloaderPlus config, String hash, boolean deleteFiles) throws IOException {
        FormBody body = new FormBody.Builder()
                .add("hashes", hash)
                .add("deleteFiles", String.valueOf(deleteFiles))
                .build();
        post(config, "/api/v2/torrents/delete", body);
        log.info("下载器[{}] 已移除种子[{}]{}", config.getName(), hash, deleteFiles ? "（含已下载文件）" : "");
    }

    /** FormBody 不可变，但每次请求都要一个新实例（重试时会被复用写入两次），统一在这里造 */
    private FormBody hashesBody(String hash) {
        return new FormBody.Builder().add("hashes", hash).build();
    }

    /**
     * 去掉 URL 的查询串再记日志。
     * <p>
     * 索引器给出的下载链接常把凭据放在查询参数里（例如 Prowlarr 的
     * {@code /download?apikey=xxx&link=yyy}），原样打进日志等于把 API Key
     * 明文写进挂载出来的日志文件。
     * </p>
     */
    private String maskUrl(String url) {
        if (StringUtils.isBlank(url)) {
            return url;
        }
        int idx = url.indexOf('?');
        return idx < 0 ? url : url.substring(0, idx) + "?<已省略参数>";
    }

    @Override
    public List<DownloaderTorrent> listByTag(PtDownloaderPlus config, String tag) throws IOException {
        String json = get(config, "/api/v2/torrents/info", Map.of("tag", tag));
        List<DownloaderTorrent> result = new ArrayList<>();
        if (StringUtils.isBlank(json)) {
            return result;
        }
        JSONArray array = parseJsonArray(json);
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            DownloaderTorrent torrent = new DownloaderTorrent();
            String hash = item.getString("hash");
            torrent.setHash(hash == null ? null : hash.toLowerCase());
            torrent.setName(item.getString("name"));
            torrent.setProgress(item.getDoubleValue("progress"));
            torrent.setRawState(item.getString("state"));
            torrent.setSavePath(item.getString("save_path"));
            torrent.setTags(item.getString("tags"));
            // H&R 保种考核用：qB 在种子未产生上传时 ratio 可能是 -1，归一到 0，
            // 否则"分享率 >= 阈值"在阈值为 0 时会被负数意外满足
            torrent.setRatio(Math.max(0.0, item.getDoubleValue("ratio")));
            torrent.setSeedingSeconds(Math.max(0L, item.getLongValue("seeding_time")));
            torrent.setUploaded(Math.max(0L, item.getLongValue("uploaded")));
            result.add(torrent);
        }
        return result;
    }

    @Override
    public List<DownloaderTorrentFile> listFiles(PtDownloaderPlus config, String hash) throws IOException {
        String json = get(config, "/api/v2/torrents/files", Map.of("hash", hash));
        List<DownloaderTorrentFile> result = new ArrayList<>();
        if (StringUtils.isBlank(json)) {
            // 元数据尚未解析完成时 qB 返回空数组，交由调用方判断下一轮重试
            return result;
        }
        JSONArray array = parseJsonArray(json);
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            DownloaderTorrentFile file = new DownloaderTorrentFile();
            file.setIndex(item.getIntValue("index"));
            file.setName(item.getString("name"));
            file.setSize(item.getLongValue("size"));
            result.add(file);
        }
        return result;
    }

    @Override
    public void excludeFiles(PtDownloaderPlus config, String hash, Set<Integer> fileIndexes) throws IOException {
        if (fileIndexes == null || fileIndexes.isEmpty()) {
            return;
        }
        String ids = fileIndexes.stream().map(String::valueOf).collect(Collectors.joining("|"));
        FormBody body = new FormBody.Builder()
                .add("hash", hash)
                .add("id", ids)
                .add("priority", "0")
                .build();
        post(config, "/api/v2/torrents/filePrio", body);
        log.info("下载器[{}] 种子[{}] 已排除 {} 个文件（非目标集数）", config.getName(), hash, fileIndexes.size());
    }

    /**
     * qBittorrent 的分享限额约定：{@code -1} = 不限，{@code -2} = 跟随全局设置。
     * 这里对"该维度不考核"一律用 -1 而不是 -2——站点没有这一项要求，不代表用户的全局限额
     * 就该接管；跟随全局恰恰是种子在 H&R 达标前被自动清掉的那条路径。
     */
    private static final String QB_NO_LIMIT = "-1";

    @Override
    public void setShareLimits(PtDownloaderPlus config, String hash, double ratioLimit, long seedingTimeMinutes)
            throws IOException {
        FormBody body = new FormBody.Builder()
                .add("hashes", hash)
                .add("ratioLimit", ratioLimit > 0 ? String.valueOf(ratioLimit) : QB_NO_LIMIT)
                .add("seedingTimeLimit", seedingTimeMinutes > 0 ? String.valueOf(seedingTimeMinutes) : QB_NO_LIMIT)
                // qB 5.x 新增的"空闲做种时长上限"。不传的话该版本会按缺省值处理，
                // 种子长时间没有上传就被判定到期，等于绕开上面两个限额把种子清掉
                .add("inactiveSeedingTimeLimit", QB_NO_LIMIT)
                .build();
        post(config, "/api/v2/torrents/setShareLimits", body);
        log.info("下载器[{}] 种子[{}] 已按 H&R 规则设限：分享率 {}，做种 {} 分钟",
                config.getName(), hash, ratioLimit > 0 ? ratioLimit : "不限",
                seedingTimeMinutes > 0 ? seedingTimeMinutes : "不限");
    }

    // ---------- 内部：带会话管理的请求执行 ----------

    private String get(PtDownloaderPlus config, String path, Map<String, String> query) throws IOException {
        return executeWithSession(config, sid -> {
            HttpUrl.Builder builder = parseUrl(config.baseUrl() + path);
            query.forEach(builder::addQueryParameter);
            return new Request.Builder()
                    .url(builder.build())
                    .header("Cookie", "SID=" + sid)
                    .header("Referer", config.baseUrl())
                    .get()
                    .build();
        });
    }

    /**
     * 解析 URL；host/端口等配置非法时 {@link HttpUrl#parse} 返回 null，
     * 紧接着调用 newBuilder() 会 NPE（未受检异常）。这里统一转成 IOException，
     * 与"网络异常 → IOException → 调用方本轮跳过、下轮重来"的契约保持一致。
     */
    private HttpUrl.Builder parseUrl(String url) throws IOException {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            throw new IOException("无法解析下载器地址：" + url);
        }
        return parsed.newBuilder();
    }

    /**
     * 解析 JSON 数组；反向代理故障、qB 返回纯文本等场景下响应体不是合法 JSON，
     * FastJSON2 会抛出未受检的 JSONException。这里转成 IOException，避免调度线程
     * 收到一个 catch (IOException) 捕不到的异常类型。
     */
    private JSONArray parseJsonArray(String json) throws IOException {
        try {
            return JSONArray.parse(json);
        } catch (JSONException e) {
            throw new IOException("qBittorrent 返回的响应不是合法 JSON：" + truncate(json), e);
        }
    }

    /** 异常消息里只截取响应体前 200 字符，避免把整个 HTML 错误页塞进异常消息 */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(截断)";
    }

    private String post(PtDownloaderPlus config, String path, RequestBody body) throws IOException {
        return executeWithSession(config, sid -> new Request.Builder()
                .url(config.baseUrl() + path)
                .header("Cookie", "SID=" + sid)
                .header("Referer", config.baseUrl())
                .post(body)
                .build());
    }

    /**
     * 使用缓存 SID 执行请求；遇 403 视为会话过期，重新登录后重试一次。
     */
    private String executeWithSession(PtDownloaderPlus config, RequestFactory factory) throws IOException {
        String sid = sidCache.get(config.getId());
        if (sid == null) {
            sid = login(config);
        }

        try (Response response = httpClient.newCall(factory.build(sid)).execute()) {
            if (response.code() != 403) {
                return readSuccessful(response);
            }
        }

        // 403：会话过期，重新登录后重试一次
        sidCache.remove(config.getId());
        String freshSid = login(config);
        try (Response retry = httpClient.newCall(factory.build(freshSid)).execute()) {
            return readSuccessful(retry);
        }
    }

    private String readSuccessful(Response response) throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException("qBittorrent 返回 HTTP " + response.code());
        }
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    /**
     * 登录并缓存 SID。
     *
     * @throws IOException 凭据错误（响应体非 Ok.）或未返回 SID Cookie
     */
    private String login(PtDownloaderPlus config) throws IOException {
        FormBody body = new FormBody.Builder()
                .add("username", StringUtils.defaultString(config.getUsername(), ""))
                .add("password", StringUtils.defaultString(config.getPassword(), ""))
                .build();
        Request request = new Request.Builder()
                .url(config.baseUrl() + "/api/v2/auth/login")
                .header("Referer", config.baseUrl())
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String text = readSuccessful(response);
            // 注意：登录失败时 qBittorrent 同样返回 200，响应体为 Fails.
            if (!OK.equalsIgnoreCase(text.trim())) {
                throw new IOException("qBittorrent 登录失败，请检查用户名密码");
            }
            String sid = extractSid(response);
            if (sid == null) {
                throw new IOException("qBittorrent 登录成功但未返回 SID");
            }
            sidCache.put(config.getId(), sid);
            return sid;
        }
    }

    private String extractSid(Response response) {
        for (String cookie : response.headers("Set-Cookie")) {
            if (cookie.startsWith("SID=")) {
                int end = cookie.indexOf(';');
                return end > 0 ? cookie.substring(4, end) : cookie.substring(4);
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface RequestFactory {
        Request build(String sid) throws IOException;
    }
}
