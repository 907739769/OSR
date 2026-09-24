package com.osr.openliststrm.openai;

import com.alibaba.fastjson2.JSON;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PT 侧 AI 能力的统一入口：读「参数设置 → OpenAI 配置」，每次调用按当前配置现建客户端。
 * <p>
 * 不复用 {@code RenameClientProvider} 缓存的那个客户端：它只在重命名任务执行时才 refresh，
 * 用户改了 key 之后 PT 这边要等下一次重命名才生效，而现建一个 {@link OpenAIClient} 只是几个字段赋值。
 * </p>
 * 调用失败一律返回 null 并记 warn，不抛异常——AI 在这里只是锦上添花，调用方都有不依赖它的退路。
 *
 * @author Jack
 */
@Slf4j
@Service
public class AiChatService {

    private final OpenlistConfig openlistConfig;

    public AiChatService(OpenlistConfig openlistConfig) {
        this.openlistConfig = openlistConfig;
    }

    /** 是否配了 OpenAI Key */
    public boolean available() {
        return StringUtils.isNotBlank(openlistConfig.getOpenAiApiKey());
    }

    /** 返回回复原文；未配置或调用失败时为 null */
    public String chat(String purpose, String prompt, int maxTokens) {
        if (!available()) {
            return null;
        }
        OpenAIClient client = new OpenAIClient(openlistConfig.getOpenAiApiKey(), openlistConfig.getOpenAiEndpoint(),
                StringUtils.isBlank(openlistConfig.getOpenAiModel()) ? null : openlistConfig.getOpenAiModel());
        try {
            return client.chat(prompt, maxTokens);
        } catch (Exception e) {
            log.warn("AI 调用失败（{}）：{}", purpose, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 要求回复是 JSON，解析成 FastJSON 的对象/数组（{@code JSONObject} / {@code JSONArray}）。
     * 回复不是合法 JSON 时返回 null。
     */
    public Object chatJson(String purpose, String prompt, int maxTokens) {
        String raw = chat(purpose, prompt, maxTokens);
        if (raw == null) {
            return null;
        }
        try {
            return JSON.parse(OpenAIApiService.stripCodeFence(raw));
        } catch (Exception e) {
            log.warn("AI 回复不是合法 JSON（{}）：{}", purpose, StringUtils.substring(raw, 0, 300));
            return null;
        }
    }
}
