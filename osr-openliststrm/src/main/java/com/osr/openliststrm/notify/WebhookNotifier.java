package com.osr.openliststrm.notify;

import com.alibaba.fastjson2.JSONObject;
import com.osr.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 通用 Webhook 通知渠道：POST 一个最简单的 {"text": message} JSON 到配置的地址，
 * 用来验证"新增渠道只需新增一个 {@code @Component implements INotifier}"这套抽象是否可扩展。
 * 不做企业微信/飞书/Bark 等特定服务商的私有协议适配（见设计文档第 8 节"不做的事情"）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class WebhookNotifier implements INotifier {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final OpenlistConfig config;
    private final OkHttpClient httpClient;

    public WebhookNotifier(OpenlistConfig config, OkHttpClient sharedOkHttpClient) {
        this.config = config;
        this.httpClient = sharedOkHttpClient;
    }

    @Override
    public String channelKey() {
        return "WEBHOOK";
    }

    @Override
    public String displayName() {
        return "Webhook";
    }

    @Override
    public boolean isConfigured() {
        return StringUtils.isNotBlank(config.getNotifyWebhookUrl());
    }

    @Override
    public void send(NotificationType type, String message) {
        String url = config.getNotifyWebhookUrl();
        if (StringUtils.isBlank(url)) {
            return;
        }
        // text 是纯文本：文案本身是按 Telegram 的 HTML parse_mode 写的，直接透传的话
        // 下游收到的是带标签、且 & 已被转成 &amp; 的串（见 WeComNotifier#toPlainText）。
        // type 是机器可读的枚举名、typeLabel 是给人看的中文名：下游自动化要按类型分流
        // （只把失败转到告警群）时，靠正则去猜文案里的 emoji 是唯一的办法，两个字段成本为零。
        JSONObject body = new JSONObject();
        body.put("text", WeComNotifier.toPlainText(message));
        body.put("type", type.name());
        body.put("typeLabel", type.getLabel());
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON_MEDIA_TYPE, body.toJSONString()))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Webhook 通知发送失败，HTTP {}", response.code());
            }
        } catch (IOException e) {
            log.warn("Webhook 通知发送异常：{}", e.getMessage());
        }
    }

}
