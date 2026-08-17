package com.osr.openliststrm.pt.downloader;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.pt.downloader.model.AddTorrentOutcome;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.osr.openliststrm.pt.downloader.model.DownloaderTorrentFile;
import lombok.extern.slf4j.Slf4j;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
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
        return listInfo(config, Map.of("tag", tag));
    }

    @Override
    public List<DownloaderTorrent> listAll(PtDownloaderPlus config) throws IOException {
        return listInfo(config, Map.of());
    }

    /** {@code /torrents/info} 的公共映射：两个查询口径唯一的差别就是带不带 tag 过滤参数 */
    private List<DownloaderTorrent> listInfo(PtDownloaderPlus config, Map<String, String> query) throws IOException {
        String json = get(config, "/api/v2/torrents/info", query);
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
            String state = item.getString("state");
            torrent.setRawState(state);
            // qB 的校验态有 checkingUP/checkingDL/checkingResumeData/queuedForChecking 四种，
            // 一律按子串匹配——转移做种只关心"是不是还在校验"，逐个枚举会在 qB 加新状态时漏掉
            torrent.setChecking(state != null && state.toLowerCase().contains("checking"));
            torrent.setSavePath(item.getString("save_path"));
            torrent.setTags(item.getString("tags"));
            // H&R 保种考核用：qB 在种子未产生上传时 ratio 可能是 -1，归一到 0，
            // 否则"分享率 >= 阈值"在阈值为 0 时会被负数意外满足
            torrent.setRatio(Math.max(0.0, item.getDoubleValue("ratio")));
            torrent.setSeedingSeconds(Math.max(0L, item.getLongValue("seeding_time")));
            torrent.setUploaded(Math.max(0L, item.getLongValue("uploaded")));
            // size 是"已选中文件的体积"，total_size 才是种子声明的总体积。清理关心的是
            // 删掉它能腾出多少空间，而 OSR 会给多集包排除非目标集文件，因此必须用 size
            torrent.setSize(Math.max(0L, item.getLongValue("size")));
            torrent.setContentPath(item.getString("content_path"));
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
            // priority 0 = 不下载。字段缺失时 getIntValue 返回 0，那会把整个种子判成一个都不下载，
            // 因此显式判断字段在不在，缺失时保持默认的 wanted=true
            if (item.containsKey("priority")) {
                file.setWanted(item.getIntValue("priority") != 0);
            }
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

    @Override
    public DownloaderTorrent getTorrent(PtDownloaderPlus config, String hash) throws IOException {
        List<DownloaderTorrent> list = listInfo(config, Map.of("hashes", hash));
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * qBittorrent 的种子导出端点，4.4.0（API 2.8.9）起提供。更早的版本没有任何 HTTP 途径
     * 能取到 .torrent 本体（只有服务端 BT_backup 目录里的文件，容器化部署下读不到），
     * 因此这里不做降级，直接把版本要求报给用户。
     */
    private static final String EXPORT_ENDPOINT = "/api/v2/torrents/export";

    @Override
    public byte[] exportTorrent(PtDownloaderPlus config, String hash) throws IOException {
        byte[] data;
        try {
            data = getBytes(config, EXPORT_ENDPOINT, Map.of("hash", hash));
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                throw new IOException("该 qBittorrent 不支持导出种子文件（需要 4.4.0 及以上版本）", e);
            }
            throw e;
        }
        if (data.length == 0) {
            // 端点存在但返回空体：种子已不在下载器里，或 qB 找不到对应的 .torrent。
            // 空字节流加到目标端只会得到一个报错的任务，不如在这里断掉
            throw new IOException("qBittorrent 导出的种子文件为空，种子[" + hash + "]可能已被移除");
        }
        return data;
    }

    /** .torrent 的标准 MIME 类型 */
    private static final MediaType TORRENT_MEDIA = MediaType.parse("application/x-bittorrent");

    @Override
    public AddTorrentOutcome addTorrentFile(PtDownloaderPlus config, byte[] metainfo, String savePath,
                                            String tag, boolean paused) throws IOException {
        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("torrents", "transfer.torrent",
                        RequestBody.create(metainfo, TORRENT_MEDIA))
                .addFormDataPart("savepath", savePath)
                .addFormDataPart("tags", StringUtils.defaultString(tag, ""))
                // 自动种子管理（Auto TMM）开着时 qB 会按分类推导保存路径、直接忽略 savepath，
                // 转移做种一旦落到别的目录，校验必然失败。显式关掉它是这里唯一能保证路径生效的办法
                .addFormDataPart("autoTMM", "false")
                // 不跳过校验：数据在不在、路径对不对，全靠这一次校验证明。
                // 跳过校验加进来的种子会以"100% 完成"的假象开始做种，实际一个块都没有，
                // 站点那边看到的就是一个从不上传的种子
                .addFormDataPart("skip_checking", "false");
        if (paused) {
            builder.addFormDataPart("paused", "true").addFormDataPart("stopped", "true");
        }
        String response = post(config, "/api/v2/torrents/add", builder.build());
        if (!OK.equalsIgnoreCase(response.trim())) {
            throw new IOException("qBittorrent 拒绝添加种子文件，响应：" + response);
        }
        log.info("已用种子文件把种子加入下载器[{}]，保存路径：{}", config.getName(), savePath);
        // qB 对"已存在的种子"同样返回 Ok.，无从分辨，一律报 ADDED。
        // 真正的去重防线在调用方：加种之前先 getTorrent 查一次
        return AddTorrentOutcome.ADDED;
    }

    @Override
    public void recheckTorrent(PtDownloaderPlus config, String hash) throws IOException {
        post(config, "/api/v2/torrents/recheck", hashesBody(hash));
        log.info("下载器[{}] 已触发种子[{}]的本地数据校验", config.getName(), hash);
    }

    // ---------- 内部：带会话管理的请求执行 ----------

    private String get(PtDownloaderPlus config, String path, Map<String, String> query) throws IOException {
        return executeWithSession(config, getRequest(config, path, query), TEXT_READER);
    }

    /** 与 {@link #get} 同一个请求，只是响应体按字节读——.torrent 是二进制，走 String 会被编码破坏 */
    private byte[] getBytes(PtDownloaderPlus config, String path, Map<String, String> query) throws IOException {
        return executeWithSession(config, getRequest(config, path, query), BYTES_READER);
    }

    private RequestFactory getRequest(PtDownloaderPlus config, String path, Map<String, String> query) {
        return sid -> {
            HttpUrl.Builder builder = parseUrl(config.baseUrl() + path);
            query.forEach(builder::addQueryParameter);
            return new Request.Builder()
                    .url(builder.build())
                    .header("Cookie", "SID=" + sid)
                    .header("Referer", config.baseUrl())
                    .get()
                    .build();
        };
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
                .build(), TEXT_READER);
    }

    /**
     * 使用缓存 SID 执行请求；遇 403 视为会话过期，重新登录后重试一次。
     */
    private <T> T executeWithSession(PtDownloaderPlus config, RequestFactory factory,
                                     ResponseReader<T> reader) throws IOException {
        // id 为 null 表示这是"新增下载器时还没保存就点测试连接"传进来的临时配置。
        // ConcurrentHashMap 不接受 null 键，get/put 都会抛 NPE，被 testConnection 的
        // catch (Exception) 吞成"连接失败"，用户看到的是一条与真实原因无关的提示。
        // 这类配置不落缓存，每次重新登录即可
        Integer downloaderId = config.getId();
        String sid = downloaderId == null ? null : sidCache.get(downloaderId);
        if (sid == null) {
            sid = login(config);
        }

        try (Response response = httpClient.newCall(factory.build(sid)).execute()) {
            if (response.code() != 403) {
                return readSuccessful(response, reader);
            }
        }

        // 403：会话过期，重新登录后重试一次
        if (downloaderId != null) {
            sidCache.remove(downloaderId);
        }
        String freshSid = login(config);
        try (Response retry = httpClient.newCall(factory.build(freshSid)).execute()) {
            return readSuccessful(retry, reader);
        }
    }

    private <T> T readSuccessful(Response response, ResponseReader<T> reader) throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException("qBittorrent 返回 HTTP " + response.code());
        }
        return reader.read(response.body());
    }

    /** 文本响应：绝大多数 qB 端点都是纯文本或 JSON */
    private static final ResponseReader<String> TEXT_READER = body -> body == null ? "" : body.string();

    /** 二进制响应：只有导出 .torrent 用得到 */
    private static final ResponseReader<byte[]> BYTES_READER = body -> body == null ? new byte[0] : body.bytes();

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
            String text = readSuccessful(response, TEXT_READER);
            // 注意：登录失败时 qBittorrent 同样返回 200，响应体为 Fails.
            if (!OK.equalsIgnoreCase(text.trim())) {
                throw new IOException("qBittorrent 登录失败，请检查用户名密码");
            }
            String sid = extractSid(response);
            if (sid == null) {
                throw new IOException("qBittorrent 登录成功但未返回 SID");
            }
            // 未保存的临时配置（id 为 null）不入缓存，理由见 executeWithSession
            if (config.getId() != null) {
                sidCache.put(config.getId(), sid);
            }
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

    /**
     * 响应体读取器。存在的唯一理由是导出 .torrent 需要读字节，而其余端点全是文本——
     * 会话管理（403 重登重试）那段逻辑不该为此复制一份。
     */
    @FunctionalInterface
    private interface ResponseReader<T> {
        T read(ResponseBody body) throws IOException;
    }
}
