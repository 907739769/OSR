package com.osr.openliststrm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI API 服务，提供缓存支持
 */
@Slf4j
@Service
public class OpenAIApiService {

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(90, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .connectionPool(new ConnectionPool(5, 5, TimeUnit.SECONDS))
            .build();

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 调用 OpenAI Chat Completions 接口
     */
    public JsonNode fetchChatCompletion(String apiKey, String endpoint, String model, String prompt) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", new Object[]{
                new HashMap<String, Object>() {{
                    put("role", "user");
                    put("content", prompt);
                }}
        });
        payload.put("temperature", 0.0);
        payload.put("max_tokens", 300);

        RequestBody body = RequestBody.create(
                MediaType.parse("application/json; charset=utf-8"),
                mapper.writeValueAsBytes(payload)
        );

        Request req = new Request.Builder()
                .url(endpoint + "/v1/chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .post(body)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                log.warn("OpenAI 请求失败: Code={} Body={}", resp.code(), resp.body() != null ? resp.body().string() : "null");
                return null;
            }

            // 解析响应，提取 content 字段
            JsonNode root = mapper.readTree(resp.body().byteStream());
            JsonNode choices = root.path("choices");
            if (choices.isArray() && choices.size() > 0) {
                String contentString = choices.get(0).path("message").path("content").asText();
                if (contentString != null && !contentString.trim().isEmpty()) {
                    // 这里我们假设 OpenAI 总是按 Prompt 要求返回 JSON 字符串
                    // 为了缓存方便，我们在这里再次解析它为 JsonNode 返回，或者直接返回 String 由客户端解析
                    // 参考 TMDb 实现，我们返回 JsonNode（这里是内容本身的 JSON 结构）
                    try {
                        return mapper.readTree(contentString.trim());
                    } catch (Exception e) {
                        log.warn("OpenAI 返回内容不是有效的 JSON: {}", contentString);
                        return null;
                    }
                }
            }
            return null;
        }
    }
}