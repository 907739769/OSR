package com.osr.openliststrm.pt.downloader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrentFile;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transmission RPC 客户端。
 * <p>
 * 会话机制与 qBittorrent 不同：Transmission 用 {@code X-Transmission-Session-Id} 头做 CSRF 防护，
 * 首次请求（或 session id 过期）会收到 HTTP 409，响应头带新的 session id，
 * 缓存后携带该头重试一次即可，无需像 qB 那样走用户名密码登录。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class TransmissionClient implements IDownloaderClient {

    private static final String TYPE = "TRANSMISSION";
    private static final String RPC_PATH = "/transmission/rpc";
    private static final String SESSION_HEADER = "X-Transmission-Session-Id";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    /** downloaderId -> session id */
    private final Map<Integer, String> sessionIdCache = new ConcurrentHashMap<>();

    public TransmissionClient(OkHttpClient sharedOkHttpClient) {
        this.httpClient = sharedOkHttpClient;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean testConnection(PtDownloaderPlus config) {
        try {
            call(config, "session-get", null);
            log.info("下载器[{}]连通", config.getName());
            return true;
        } catch (Exception e) {
            log.warn("下载器[{}]连通性测试失败：{}", config.getName(), e.getMessage());
            return false;
        }
    }

    @Override
    public void addTorrent(PtDownloaderPlus config, String downloadUrl, String savePath, String tag, boolean paused)
            throws IOException {
        JSONObject args = new JSONObject();
        args.put("filename", downloadUrl);
        args.put("download-dir", savePath);
        // Transmission 的 paused 语义与 qB 一致，且没有跨版本改名的问题
        args.put("paused", paused);
        JSONObject result = call(config, "torrent-add", args);
        log.info("已推送种子到下载器[{}]{}：{}", config.getName(),
                paused ? "（暂停态，待选完目标集文件再启动）" : "", maskUrl(downloadUrl));

        // 打标签是独立的第二步：labels 是 Transmission 3.0+ 才支持的字段，若目标版本较旧，
        // 这一步失败不该连累种子已经添加成功的事实，只记 warn。
        Integer torrentId = extractTorrentId(result);
        if (torrentId == null) {
            return;
        }
        try {
            JSONObject setArgs = new JSONObject();
            setArgs.put("ids", List.of(torrentId));
            setArgs.put("labels", List.of(tag));
            call(config, "torrent-set", setArgs);
        } catch (Exception e) {
            log.warn("下载器[{}]打标签失败（种子已添加成功，不影响下载）：{}", config.getName(), e.getMessage());
        }
    }

    private Integer extractTorrentId(JSONObject result) {
        JSONObject arguments = result.getJSONObject("arguments");
        if (arguments == null) {
            return null;
        }
        JSONObject added = arguments.getJSONObject("torrent-added");
        if (added == null) {
            added = arguments.getJSONObject("torrent-duplicate");
        }
        return added == null ? null : added.getInteger("id");
    }

    /**
     * 去掉 URL 的查询串再记日志，避免索引器下载链接里的凭据（如 Prowlarr 的 apikey）明文进日志。
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
        return listTorrents(config, tag);
    }

    @Override
    public List<DownloaderTorrent> listAll(PtDownloaderPlus config) throws IOException {
        return listTorrents(config, null);
    }

    /**
     * {@code torrent-get} 的公共映射。
     *
     * @param tag 只保留带该 label 的种子；传 null 表示不过滤（Transmission RPC 没有服务端
     *            按 label 过滤的参数，两种口径都是拉全量后在本地筛）
     */
    private List<DownloaderTorrent> listTorrents(PtDownloaderPlus config, String tag) throws IOException {
        JSONObject args = new JSONObject();
        args.put("fields", List.of("id", "name", "percentDone", "status", "downloadDir", "labels", "hashString",
                "uploadRatio", "secondsSeeding", "uploadedEver", "sizeWhenDone"));
        JSONObject result = call(config, "torrent-get", args);

        List<DownloaderTorrent> list = new ArrayList<>();
        JSONObject arguments = result.getJSONObject("arguments");
        JSONArray torrents = arguments == null ? null : arguments.getJSONArray("torrents");
        if (torrents == null) {
            return list;
        }
        for (int i = 0; i < torrents.size(); i++) {
            JSONObject item = torrents.getJSONObject(i);
            JSONArray labels = item.getJSONArray("labels");
            if (tag != null && (labels == null || !containsLabel(labels, tag))) {
                continue;
            }
            DownloaderTorrent torrent = new DownloaderTorrent();
            String hash = item.getString("hashString");
            torrent.setHash(hash == null ? null : hash.toLowerCase());
            torrent.setName(item.getString("name"));
            torrent.setProgress(item.getDoubleValue("percentDone"));
            torrent.setRawState(String.valueOf(item.getIntValue("status")));
            torrent.setSavePath(item.getString("downloadDir"));
            torrent.setTags(labels == null ? "" : joinLabels(labels));
            // H&R 保种考核用：Transmission 无法计算分享率时返回 -1，必须归一到 0，
            // 否则"分享率 >= 阈值"在阈值为 0 时会被 -1 意外满足
            torrent.setRatio(Math.max(0.0, item.getDoubleValue("uploadRatio")));
            torrent.setSeedingSeconds(Math.max(0L, item.getLongValue("secondsSeeding")));
            torrent.setUploaded(Math.max(0L, item.getLongValue("uploadedEver")));
            // sizeWhenDone = 已选中文件下完后的体积，语义与 qB 的 size 一致（都排除了未选中的文件）
            torrent.setSize(Math.max(0L, item.getLongValue("sizeWhenDone")));
            // Transmission 没有 content_path 字段，交给 DownloaderTorrent#contentKey
            // 用 downloadDir + name 退化推导——对辅种而言这两项同样是一致的
            list.add(torrent);
        }
        return list;
    }

    private boolean containsLabel(JSONArray labels, String tag) {
        for (int i = 0; i < labels.size(); i++) {
            if (tag.equals(labels.getString(i))) {
                return true;
            }
        }
        return false;
    }

    private String joinLabels(JSONArray labels) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            values.add(labels.getString(i));
        }
        return String.join(",", values);
    }

    @Override
    public List<DownloaderTorrentFile> listFiles(PtDownloaderPlus config, String hash) throws IOException {
        JSONObject args = new JSONObject();
        args.put("ids", List.of(hash));
        args.put("fields", List.of("files"));
        JSONObject result = call(config, "torrent-get", args);

        List<DownloaderTorrentFile> files = new ArrayList<>();
        JSONObject arguments = result.getJSONObject("arguments");
        JSONArray torrents = arguments == null ? null : arguments.getJSONArray("torrents");
        if (torrents == null || torrents.isEmpty()) {
            // 元数据尚未解析完成、或种子不存在时返回空列表，交由调用方判断下一轮重试
            return files;
        }
        JSONArray rawFiles = torrents.getJSONObject(0).getJSONArray("files");
        if (rawFiles == null) {
            return files;
        }
        for (int i = 0; i < rawFiles.size(); i++) {
            JSONObject item = rawFiles.getJSONObject(i);
            DownloaderTorrentFile file = new DownloaderTorrentFile();
            file.setIndex(i);
            file.setName(item.getString("name"));
            file.setSize(item.getLongValue("length"));
            files.add(file);
        }
        return files;
    }

    @Override
    public void excludeFiles(PtDownloaderPlus config, String hash, Set<Integer> fileIndexes) throws IOException {
        if (fileIndexes == null || fileIndexes.isEmpty()) {
            return;
        }
        JSONObject args = new JSONObject();
        args.put("ids", List.of(hash));
        args.put("files-unwanted", List.copyOf(fileIndexes));
        call(config, "torrent-set", args);
        log.info("下载器[{}] 种子[{}] 已排除 {} 个文件（非目标集数）", config.getName(), hash, fileIndexes.size());
    }

    @Override
    public void resumeTorrent(PtDownloaderPlus config, String hash) throws IOException {
        JSONObject args = new JSONObject();
        args.put("ids", List.of(hash));
        call(config, "torrent-start", args);
        log.info("下载器[{}] 已启动种子[{}]", config.getName(), hash);
    }

    @Override
    public void deleteTorrent(PtDownloaderPlus config, String hash, boolean deleteFiles) throws IOException {
        JSONObject args = new JSONObject();
        args.put("ids", List.of(hash));
        args.put("delete-local-data", deleteFiles);
        call(config, "torrent-remove", args);
        log.info("下载器[{}] 已移除种子[{}]{}", config.getName(), hash, deleteFiles ? "（含已下载文件）" : "");
    }

    /**
     * Transmission 的分享限额只能表达"分享率"这一维。
     * <p>
     * RPC 里与做种时长沾边的只有 {@code seedIdleLimit}，它的语义是"空闲多久后停止做种"，
     * 而不是 H&R 要求的"至少做满多久"——一个热门种子持续有上传就永远不会空闲，
     * 拿它当最短做种时长用是错的。因此 {@code seedingTimeMinutes} 在这里被<b>刻意忽略</b>，
     * 只记一条 debug 说明。Transmission 用户的时长维度只能靠 OSR 侧的追踪与告警兜住，
     * 拿不到下载器层面的主动防护。
     * </p>
     * <p>
     * {@code seedRatioMode=1} 表示"用种子自己的 seedRatioLimit"，{@code 2} 表示"永不按分享率停止"。
     * 不用 {@code 0}（跟随全局）——跟随全局恰恰是种子在 H&R 达标前被自动清掉的那条路径。
     * </p>
     */
    @Override
    public void setShareLimits(PtDownloaderPlus config, String hash, double ratioLimit, long seedingTimeMinutes)
            throws IOException {
        JSONObject args = new JSONObject();
        args.put("ids", List.of(hash));
        if (ratioLimit > 0) {
            args.put("seedRatioLimit", ratioLimit);
            args.put("seedRatioMode", 1);
        } else {
            args.put("seedRatioMode", 2);
        }
        call(config, "torrent-set", args);
        if (seedingTimeMinutes > 0) {
            log.debug("下载器[{}] 种子[{}] 的最短做种时长 {} 分钟无法下发：Transmission RPC 没有对应字段，"
                    + "该维度仅由 OSR 侧追踪告警兜底", config.getName(), hash, seedingTimeMinutes);
        }
        log.info("下载器[{}] 种子[{}] 已按 H&R 规则设限：分享率 {}",
                config.getName(), hash, ratioLimit > 0 ? ratioLimit : "不限");
    }

    // ---------- 内部：JSON-RPC 调用 + 会话管理 ----------

    /**
     * 执行一次 RPC 调用并校验 {@code result == "success"}。
     *
     * @throws IOException 网络异常、下载器拒绝、或响应不是合法 JSON
     */
    private JSONObject call(PtDownloaderPlus config, String method, JSONObject arguments) throws IOException {
        JSONObject body = new JSONObject();
        body.put("method", method);
        body.put("arguments", arguments == null ? new JSONObject() : arguments);

        String raw = executeWithSession(config, sid -> buildRequest(config, body, sid));
        JSONObject json = parseJsonObject(raw);
        String result = json.getString("result");
        if (!"success".equalsIgnoreCase(result)) {
            throw new IOException("Transmission 返回失败：" + result);
        }
        return json;
    }

    private Request buildRequest(PtDownloaderPlus config, JSONObject body, String sessionId) throws IOException {
        HttpUrl url = HttpUrl.parse(config.baseUrl() + RPC_PATH);
        if (url == null) {
            throw new IOException("无法解析下载器地址：" + config.baseUrl());
        }
        RequestBody reqBody = RequestBody.create(body.toJSONString(), JSON);
        Request.Builder builder = new Request.Builder().url(url).post(reqBody);
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        String basicAuth = basicAuthHeader(config);
        if (basicAuth != null) {
            builder.header("Authorization", basicAuth);
        }
        return builder.build();
    }

    private String basicAuthHeader(PtDownloaderPlus config) {
        if (StringUtils.isBlank(config.getUsername())) {
            return null;
        }
        String credentials = config.getUsername() + ":" + StringUtils.defaultString(config.getPassword(), "");
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 使用缓存 session id 执行请求；遇 409（session 缺失或过期）取响应头里的新 id，重试一次。
     */
    private String executeWithSession(PtDownloaderPlus config, RequestFactory factory) throws IOException {
        // id 为 null 表示这是"新增下载器时还没保存就点测试连接"传进来的临时配置。
        // ConcurrentHashMap 不接受 null 键，get/put 都会抛 NPE，被 testConnection 的
        // catch (Exception) 吞成"连接失败"。这类配置不落缓存，靠 409 重试拿 session id 即可
        Integer downloaderId = config.getId();
        String sid = downloaderId == null ? null : sessionIdCache.get(downloaderId);

        try (Response response = httpClient.newCall(factory.build(sid)).execute()) {
            if (response.code() != 409) {
                return readSuccessful(response);
            }
            sid = response.header(SESSION_HEADER);
            if (sid == null) {
                throw new IOException("Transmission 未返回 " + SESSION_HEADER);
            }
            if (downloaderId != null) {
                sessionIdCache.put(downloaderId, sid);
            }
        }

        try (Response retry = httpClient.newCall(factory.build(sid)).execute()) {
            return readSuccessful(retry);
        }
    }

    private String readSuccessful(Response response) throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException("Transmission 返回 HTTP " + response.code());
        }
        ResponseBody body = response.body();
        return body == null ? "" : body.string();
    }

    /**
     * 解析 JSON 对象；反向代理故障等场景响应体不是合法 JSON 时，
     * FastJSON2 会抛出未受检的 JSONException，这里转成 IOException，
     * 与"网络异常 → IOException → 调用方本轮跳过、下轮重来"的契约保持一致。
     */
    private JSONObject parseJsonObject(String json) throws IOException {
        try {
            return JSONObject.parseObject(json);
        } catch (JSONException e) {
            throw new IOException("Transmission 返回的响应不是合法 JSON：" + truncate(json), e);
        }
    }

    /** 异常消息里只截取响应体前 200 字符，避免把整个 HTML 错误页塞进异常消息 */
    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...(截断)";
    }

    @FunctionalInterface
    private interface RequestFactory {
        Request build(String sessionId) throws IOException;
    }
}
