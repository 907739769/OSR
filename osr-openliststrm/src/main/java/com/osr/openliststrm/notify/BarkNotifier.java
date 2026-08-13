package com.osr.openliststrm.notify;

import com.osr.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Bark（iOS 推送）通知渠道。
 * <p>
 * 用户配的是形如 {@code https://api.day.app/你的Key} 的推送地址，本类在其后追加
 * 标题与正文两段路径。走 GET 而不是 POST：Bark 的 GET 形式对自建版本兼容性最好，
 * 且消息本身就是 URL 路径的一部分，不需要构造 body。
 * </p>
 * <p>
 * 只有一个推送 Key，无法按人投递，因此不覆写 {@link #supportsDirectDelivery()}。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class BarkNotifier implements INotifier {

    /** 推送标题。Bark 的通知栏会把它加粗显示在正文上方 */
    private static final String TITLE = "OSR";

    private final OpenlistConfig config;
    private final OkHttpClient httpClient;

    public BarkNotifier(OpenlistConfig config, OkHttpClient sharedOkHttpClient) {
        this.config = config;
        this.httpClient = sharedOkHttpClient;
    }

    @Override
    public String channelKey() {
        return "BARK";
    }

    @Override
    public String displayName() {
        return "Bark";
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.isNotBlank(config.getNotifyBarkUrl());
    }

    @Override
    public void send(NotificationType type, String message) {
        String base = config.getNotifyBarkUrl();
        if (StringUtils.isBlank(base) || StringUtils.isBlank(message)) {
            return;
        }
        HttpUrl parsed = HttpUrl.parse(StringUtils.removeEnd(base.trim(), "/"));
        if (parsed == null) {
            log.warn("Bark 推送地址不是合法 URL，已跳过：{}", base);
            return;
        }
        // 用 addPathSegment 而不是字符串拼接：消息里的 / # ? 会被正确转义，
        // 否则一条带路径的通知（"复制失败 /电影/xxx"）会把 URL 结构撑断
        HttpUrl url = parsed.newBuilder()
                .addPathSegment(TITLE)
                .addPathSegment(stripHtmlTags(message))
                .build();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Bark 通知发送失败，HTTP {}", response.code());
            }
        } catch (IOException e) {
            log.warn("Bark 通知发送异常：{}", e.getMessage());
        }
    }

    /**
     * 历史通知文案带 Telegram 的 HTML 标签，Bark 不解析 HTML，原样发过去是一堆尖括号。
     * 复用企微那份实现，两处保持同一套清洗口径。
     */
    static String stripHtmlTags(String message) {
        return WeComNotifier.stripHtmlTags(message);
    }
}
