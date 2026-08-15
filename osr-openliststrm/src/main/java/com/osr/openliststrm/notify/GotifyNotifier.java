package com.osr.openliststrm.notify;

import com.alibaba.fastjson2.JSONObject;
import com.osr.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Gotify（自建推送服务）通知渠道：POST {@code /message?token=xxx}，body 是
 * {@code {"title": ..., "message": ...}}。
 * <p>
 * 服务地址与应用 token 都配了才发送——只配一个的话请求必然 401，
 * 与其每次发通知都打一次必失败的请求，不如直接静默跳过。
 * </p>
 * <p>
 * Gotify 的 token 对应一个「应用」而不是某个用户，无法按人投递。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class GotifyNotifier implements INotifier {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
    private static final String TITLE_PREFIX = "OSR";

    /** 失败类的推送优先级。Gotify 客户端在 >=8 时才会响铃/弹窗，例行通知用默认档静默入列 */
    private static final int PRIORITY_URGENT = 8;
    private static final int PRIORITY_NORMAL = 4;

    private final OpenlistConfig config;
    private final OkHttpClient httpClient;

    public GotifyNotifier(OpenlistConfig config, OkHttpClient sharedOkHttpClient) {
        this.config = config;
        this.httpClient = sharedOkHttpClient;
    }

    @Override
    public String channelKey() {
        return "GOTIFY";
    }

    @Override
    public String displayName() {
        return "Gotify";
    }

    @Override
    public boolean isConfigured() {
        return !StringUtils.isAnyBlank(config.getNotifyGotifyUrl(), config.getNotifyGotifyToken());
    }

    @Override
    public void send(NotificationType type, String message) {
        String base = config.getNotifyGotifyUrl();
        String token = config.getNotifyGotifyToken();
        if (StringUtils.isAnyBlank(base, token) || StringUtils.isBlank(message)) {
            return;
        }
        HttpUrl parsed = HttpUrl.parse(StringUtils.removeEnd(base.trim(), "/"));
        if (parsed == null) {
            log.warn("Gotify 服务地址不是合法 URL，已跳过：{}", base);
            return;
        }
        HttpUrl url = parsed.newBuilder()
                .addPathSegment("message")
                .addQueryParameter("token", token.trim())
                .build();

        // 标题带上类型名（"OSR · 下载失败"），通知列表里不展开正文就能分辨是哪类事。
        // priority 只对失败类抬高：Gotify 客户端 >=8 才响铃弹窗，若每条都抬高，
        // 用户为了不被日更剧的入库通知吵醒，只能整个渠道静音，连真告警一起丢掉。
        JSONObject body = new JSONObject();
        body.put("title", TITLE_PREFIX + " · " + type.getLabel());
        body.put("message", WeComNotifier.toPlainText(message));
        body.put("priority", type.urgent() ? PRIORITY_URGENT : PRIORITY_NORMAL);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON_MEDIA_TYPE, body.toJSONString()))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Gotify 通知发送失败，HTTP {}", response.code());
            }
        } catch (IOException e) {
            log.warn("Gotify 通知发送异常：{}", e.getMessage());
        }
    }
}
