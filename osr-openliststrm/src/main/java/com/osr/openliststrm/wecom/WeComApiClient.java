package com.osr.openliststrm.wecom;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.osr.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 企业微信自建应用 API 客户端：换 access_token、发应用消息。
 * <p>
 * access_token 按 (corpid, secret) 缓存。企微对 gettoken 有频率限制，且新 token 下发后
 * 旧 token 会在短时间内一起失效，所以这里既做过期时间缓存，也在收到「token 失效」错误码
 * 时清缓存重试一次——只靠时间判断的话，后台换了 Secret 后会一直用着已作废的 token 静默失败。
 *
 * @author Jack
 */
@Slf4j
@Component
public class WeComApiClient {

    /** 企微官方地址。代理未配置或配置非法时一律回退到它 */
    static final String DEFAULT_API_BASE = "https://qyapi.weixin.qq.com/cgi-bin/";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    /** 企微「access_token 无效/过期/缺失」错误码，收到即清缓存重取 */
    private static final Set<Integer> TOKEN_INVALID_CODES = Set.of(40014, 42001, 41001);

    /** 企微 text 消息体上限 2048 字节，超出会整条被拒（errcode 40058），发送前先截断 */
    private static final int TEXT_CONTENT_MAX_BYTES = 2048;

    /** 提前 5 分钟视为过期，避免临界点用一个下一秒就失效的 token */
    private static final long TOKEN_EARLY_REFRESH_MILLIS = 5 * 60 * 1000L;

    private final OpenlistConfig config;
    private final OkHttpClient httpClient;

    /** 缓存的 token 及其来源凭据；凭据变化或过期即重取。三个字段只在 synchronized 块内成对写入 */
    private volatile String cachedToken;
    private volatile long cachedTokenExpireAt;
    private volatile String cachedCredential;

    public WeComApiClient(OpenlistConfig config, OkHttpClient sharedOkHttpClient) {
        this.config = config;
        this.httpClient = sharedOkHttpClient;
    }

    /**
     * 企微是否已配置到「能发消息」的程度。corpid/secret/agentid 缺任意一个都发不出去，
     * 调用方据此静默跳过，不必各自拼这三个判断。
     */
    public boolean isConfigured() {
        return StringUtils.isNoneBlank(config.getWeComCorpId(), config.getWeComSecret(), config.getWeComAgentId());
    }

    /** 回调链路是否可用（除发消息所需配置外，还要有 Token 与 AESKey） */
    public boolean isCallbackConfigured() {
        return isConfigured() && StringUtils.isNoneBlank(config.getWeComToken(), config.getWeComAesKey());
    }

    /**
     * 把配置的代理地址规范成可直接拼接口路径的 base（以 {@code /cgi-bin/} 结尾）。
     * <p>
     * 2022-06-20 之后创建的自建应用要求登记「企业可信IP」，动态 IP 的部署只能反代
     * qyapi.weixin.qq.com。用户填进来的东西五花八门，这里统一收口：
     * <ul>
     *   <li>空 / 缺 scheme / 其它非法值 → 回退官方地址。<b>绝不能返回非法串</b>：
     *       {@code HttpUrl.parse} 对非法 URL 返回 null，一路带到调用处就是 NPE，
     *       而这条链路是异步跑的，异常只会安静地写进日志。</li>
     *   <li>带或不带尾部斜杠都接受</li>
     *   <li>已经写到 {@code /cgi-bin} 的不再重复拼——反代配置里带上它很常见</li>
     * </ul>
     */
    static String resolveApiBase(String proxyUrl) {
        if (StringUtils.isBlank(proxyUrl)) {
            return DEFAULT_API_BASE;
        }
        String base = proxyUrl.trim();
        // 只认 http/https：企微 API 就这两种，其它协议 OkHttp 也发不出去
        if (!StringUtils.startsWithIgnoreCase(base, "http://") && !StringUtils.startsWithIgnoreCase(base, "https://")) {
            return DEFAULT_API_BASE;
        }
        base = StringUtils.stripEnd(base, "/");
        if (base.isEmpty()) {
            return DEFAULT_API_BASE;
        }
        if (!StringUtils.endsWithIgnoreCase(base, "/cgi-bin")) {
            base = base + "/cgi-bin";
        }
        return base + "/";
    }

    /**
     * 拼出接口 URL 的 builder。地址非法时返回 null，调用方静默跳过——
     * 通知失败不该把异常抛回业务主流程。
     */
    private HttpUrl.Builder urlBuilder(String path) {
        String base = resolveApiBase(config.getWeComProxyUrl());
        HttpUrl url = HttpUrl.parse(base + path);
        if (url == null) {
            log.warn("企业微信接口地址非法，请检查「API代理地址」配置：{}", base);
            return null;
        }
        return url.newBuilder();
    }

    /**
     * 给指定成员发文本消息。
     *
     * @param toUser  接收人，多个用 | 分隔；{@code @all} 表示应用可见范围内全部成员
     * @param content 消息正文，纯文本
     * @return 是否发送成功（失败只记日志，不抛异常——通知失败不该影响业务主流程）
     */
    public boolean sendText(String toUser, String content) {
        if (!isConfigured() || StringUtils.isAnyBlank(toUser, content)) {
            return false;
        }
        JSONObject body = new JSONObject();
        body.put("touser", toUser);
        body.put("msgtype", "text");
        body.put("agentid", config.getWeComAgentId());
        JSONObject text = new JSONObject();
        text.put("content", truncateToBytes(content, TEXT_CONTENT_MAX_BYTES));
        body.put("text", text);
        // safe=0：普通消息。保密消息无法转发，但也无法在会话里被引用回复，指令交互体验更差
        body.put("safe", 0);
        return sendMessage(body);
    }

    /**
     * 查企微成员的姓名，供自动开号时给影子账号取一个人能看懂的昵称。
     * <p>
     * 取不到就返回 null（调用方回退用 UserId）：这个接口要通讯录读取权限，
     * 而且企微对部分企业的成员姓名做了隐藏/加密处理——拿不到姓名不该阻断开号。
     */
    public String getMemberName(String wecomUserId) {
        if (!isConfigured() || StringUtils.isBlank(wecomUserId)) {
            return null;
        }
        String token = getAccessToken();
        if (token == null) {
            return null;
        }
        HttpUrl.Builder builder = urlBuilder("user/get");
        if (builder == null) {
            return null;
        }
        HttpUrl url = builder
                .addQueryParameter("access_token", token)
                .addQueryParameter("userid", wecomUserId)
                .build();
        JSONObject result = execute(new Request.Builder().url(url).get().build());
        if (result == null || result.getIntValue("errcode", -1) != 0) {
            log.debug("查询企微成员[{}]姓名失败（不影响开号）：{}", wecomUserId,
                    result == null ? "请求失败" : result.getString("errmsg"));
            return null;
        }
        String name = result.getString("name");
        return StringUtils.isBlank(name) ? null : name.trim();
    }

    /**
     * 覆盖写入应用的自定义菜单（先删后建）。
     * <p>
     * 企微的 menu/create 是整体覆盖语义，但已有菜单时直接 create 会报 60020 之外的冲突，
     * 所以先 delete 再 create；delete 在「本来就没有菜单」时会返回非 0 错误码，
     * 那是预期内的，不当失败处理。
     *
     * @return 失败原因；成功返回 null
     */
    public String syncMenu(JSONObject menuBody) {
        if (!isConfigured()) {
            return "企业微信未配置完整（需要 corpid、Secret、AgentId）";
        }
        String agentId = config.getWeComAgentId();
        // 删除失败大多是「当前没有菜单」，继续建即可
        JSONObject deleted = getWithToken("menu/delete", "agentid", agentId);
        if (deleted != null && deleted.getIntValue("errcode", -1) != 0) {
            log.debug("删除企微旧菜单返回 errcode={}（通常表示本来就没有菜单，可忽略）",
                    deleted.getIntValue("errcode", -1));
        }
        JSONObject result = postWithToken("menu/create", menuBody, true, "agentid", agentId);
        if (result == null) {
            return "调用企业微信接口失败，请检查网络与「API代理地址」配置";
        }
        int errcode = result.getIntValue("errcode", -1);
        if (errcode != 0) {
            String errmsg = result.getString("errmsg");
            log.warn("同步企微应用菜单失败，errcode={} errmsg={}", errcode, errmsg);
            return "企业微信返回错误 " + errcode + "：" + errmsg;
        }
        log.info("企微应用菜单同步成功");
        return null;
    }

    /** 带 access_token 的 GET，附加一组查询参数 */
    private JSONObject getWithToken(String path, String... queryParams) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }
        HttpUrl.Builder builder = urlBuilder(path);
        if (builder == null) {
            return null;
        }
        builder.addQueryParameter("access_token", token);
        for (int i = 0; i + 1 < queryParams.length; i += 2) {
            builder.addQueryParameter(queryParams[i], queryParams[i + 1]);
        }
        return execute(new Request.Builder().url(builder.build()).get().build());
    }

    /**
     * POST /message/send，带一次 token 失效重试。
     */
    private boolean sendMessage(JSONObject body) {
        JSONObject result = postWithToken("message/send", body, true);
        if (result == null) {
            return false;
        }
        int errcode = result.getIntValue("errcode", -1);
        if (errcode != 0) {
            log.warn("企业微信消息发送失败，errcode={} errmsg={}", errcode, result.getString("errmsg"));
            return false;
        }
        // 部分接收人不在应用可见范围时企微仍返回 errcode=0，只在这些字段里给出名单，
        // 不打出来的话表现为「接口成功但对方没收到」，无从排查
        String invalidUser = result.getString("invaliduser");
        if (StringUtils.isNotBlank(invalidUser)) {
            log.warn("企业微信消息部分接收人无效（不在应用可见范围内）：{}", invalidUser);
        }
        return true;
    }

    /**
     * 带 access_token 的 POST。{@code retryOnTokenInvalid} 为 true 时，
     * 命中 token 失效错误码会清缓存并重试一次。
     */
    private JSONObject postWithToken(String path, JSONObject body, boolean retryOnTokenInvalid,
                                     String... queryParams) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }
        HttpUrl.Builder builder = urlBuilder(path);
        if (builder == null) {
            return null;
        }
        builder.addQueryParameter("access_token", token);
        for (int i = 0; i + 1 < queryParams.length; i += 2) {
            builder.addQueryParameter(queryParams[i], queryParams[i + 1]);
        }
        Request request = new Request.Builder()
                .url(builder.build())
                .post(RequestBody.create(JSON_MEDIA_TYPE, body.toJSONString()))
                .build();
        JSONObject result = execute(request);
        if (result == null) {
            return null;
        }
        if (retryOnTokenInvalid && TOKEN_INVALID_CODES.contains(result.getIntValue("errcode", -1))) {
            log.info("企业微信 access_token 已失效，清缓存后重试一次");
            invalidateToken(token);
            return postWithToken(path, body, false, queryParams);
        }
        return result;
    }

    /**
     * 取 access_token：凭据未变且未到刷新窗口就复用缓存，否则重新换取。
     * 换取失败返回 null（调用方静默跳过），不抛异常。
     */
    private String getAccessToken() {
        String corpId = config.getWeComCorpId();
        String secret = config.getWeComSecret();
        if (StringUtils.isAnyBlank(corpId, secret)) {
            return null;
        }
        String credential = corpId + ' ' + secret;
        String token = cachedToken;
        if (token != null && credential.equals(cachedCredential)
                && System.currentTimeMillis() < cachedTokenExpireAt - TOKEN_EARLY_REFRESH_MILLIS) {
            return token;
        }
        synchronized (this) {
            // 双检：并发进来的线程在这里会直接命中前一个线程刚写入的 token，避免重复换取触发企微频率限制
            if (cachedToken != null && credential.equals(cachedCredential)
                    && System.currentTimeMillis() < cachedTokenExpireAt - TOKEN_EARLY_REFRESH_MILLIS) {
                return cachedToken;
            }
            return fetchAccessToken(corpId, secret, credential);
        }
    }

    /** 必须在 synchronized(this) 内调用 */
    private String fetchAccessToken(String corpId, String secret, String credential) {
        HttpUrl.Builder builder = urlBuilder("gettoken");
        if (builder == null) {
            return null;
        }
        HttpUrl url = builder
                .addQueryParameter("corpid", corpId)
                .addQueryParameter("corpsecret", secret)
                .build();
        JSONObject result = execute(new Request.Builder().url(url).get().build());
        if (result == null) {
            return null;
        }
        int errcode = result.getIntValue("errcode", -1);
        String token = result.getString("access_token");
        if (errcode != 0 || StringUtils.isBlank(token)) {
            log.warn("企业微信换取 access_token 失败，errcode={} errmsg={}", errcode, result.getString("errmsg"));
            return null;
        }
        long expiresIn = result.getLongValue("expires_in", 7200L);
        cachedToken = token;
        cachedCredential = credential;
        cachedTokenExpireAt = System.currentTimeMillis() + expiresIn * 1000L;
        return token;
    }

    /**
     * 清除失效的 token。只在缓存里仍是这个 token 时才清，避免把并发线程刚换到的新 token 误清掉
     * （否则两个线程会互相作废对方的 token，陷入换取风暴）。
     */
    private synchronized void invalidateToken(String staleToken) {
        if (staleToken != null && staleToken.equals(cachedToken)) {
            cachedToken = null;
            cachedTokenExpireAt = 0L;
        }
    }

    /** 执行请求并解析 JSON；网络异常/非 2xx/响应非 JSON 一律记 warn 返回 null */
    private JSONObject execute(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? null : body.string();
            if (!response.isSuccessful()) {
                log.warn("企业微信接口 HTTP {}：{}", response.code(), StringUtils.abbreviate(text, 200));
                return null;
            }
            return JSON.parseObject(text);
        } catch (Exception e) {
            log.warn("企业微信接口调用异常：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 按 UTF-8 字节数截断。企微的长度上限是字节而非字符，中文消息按字符数判断会漏判，
     * 且不能在多字节字符中间切断（会切出乱码），所以按字符逐个累加字节长度。
     */
    static String truncateToBytes(String content, int maxBytes) {
        byte[] raw = content.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) {
            return content;
        }
        String suffix = "…(已截断)";
        int budget = maxBytes - suffix.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < content.length(); ) {
            int codePoint = content.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > budget) {
                break;
            }
            sb.appendCodePoint(codePoint);
            used += size;
            i += charCount;
        }
        return sb.append(suffix).toString();
    }
}
