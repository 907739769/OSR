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
        String contentString = fetchChatText(apiKey, endpoint, model, prompt, 300);
        if (contentString == null) {
            return null;
        }
        try {
            return mapper.readTree(stripCodeFence(contentString));
        } catch (Exception e) {
            log.warn("OpenAI 返回内容不是有效的 JSON: {}", contentString);
            return null;
        }
    }

    /**
     * 调用 Chat Completions，返回第一条回复的原始文本；请求失败或回复为空时返回 null。
     *
     * @param maxTokens 回复长度上限。标题解析 300 足够，周报、批量解析要给得更宽
     */
    public String fetchChatText(String apiKey, String endpoint, String model, String prompt, int maxTokens) throws IOException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("messages", new Object[]{
                new HashMap<String, Object>() {{
                    put("role", "user");
                    put("content", prompt);
                }}
        });
        payload.put("temperature", 0.0);
        payload.put("max_tokens", maxTokens);

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
                    return contentString.trim();
                }
            }
            return null;
        }
    }

    /** 不少模型即使被要求「只返回 JSON」也会包一层 ```json 代码块，解析前剥掉 */
    public static String stripCodeFence(String content) {
        String t = content.trim();
        if (!t.startsWith("```")) {
            return t;
        }
        int firstNewline = t.indexOf('\n');
        int lastFence = t.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            return t;
        }
        return t.substring(firstNewline + 1, lastFence).trim();
    }
}