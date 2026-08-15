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

    /** 推送标题的前缀。Bark 的通知栏会把标题加粗显示在正文上方 */
    private static final String TITLE_PREFIX = "OSR";

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
        //
        // 标题带上类型名（"OSR · 下载失败"），锁屏上不展开正文就能分辨是哪类事；
        // group 让 Bark 把同类通知折叠成一组，日更剧一天几条命中不会把通知中心刷屏；
        // level 只对失败类抬到 timeSensitive（会响、会亮屏），例行的命中/入库保持默认，
        // 否则「每条都紧急」等于「每条都不紧急」。
        HttpUrl.Builder builder = parsed.newBuilder()
                .addPathSegment(TITLE_PREFIX + " · " + type.getLabel())
                .addPathSegment(toPlainText(message))
                .addQueryParameter("group", type.getLabel());
        if (type.urgent()) {
            builder.addQueryParameter("level", "timeSensitive");
        }
        HttpUrl url = builder.build();
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
     * Bark 不解析 HTML：既要去掉文案里的标签，也要把 {@code &amp;} 一类实体还原回去。
     * 复用企微那份实现，两处保持同一套清洗口径。
     */
    static String toPlainText(String message) {
        return WeComNotifier.toPlainText(message);
    }
}
