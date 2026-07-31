package com.osr.openliststrm.rename;

import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.openai.OpenAIClient;
import com.osr.openliststrm.tmdb.TMDbClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 客户端维护者
 *
 * @author: Jack
 * @creat: 2026/1/13 11:05
 */
@Slf4j
@Component
public class RenameClientProvider {

    private volatile TMDbClient tmdbClient;
    private volatile OpenAIClient openAIClient;

    private volatile String tmdbKey;
    private volatile String openAiKey;
    private volatile String openAiEndpoint;
    private volatile String openAiModel;

    public synchronized void refresh(OpenlistConfig config) {
        refreshTmdb(config);
        refreshOpenAi(config);
    }

    private void refreshTmdb(OpenlistConfig config) {
        String newKey = config.getTmdbApiKey();
        if (StringUtils.isBlank(newKey)) {
            if (tmdbClient != null) {
                log.warn("TMDb key removed, disabling TMDb client");
            }
            tmdbClient = null;
            tmdbKey = null;
            return;
        }
        if (!StringUtils.equals(newKey, tmdbKey)) {
            tmdbClient = new TMDbClient(newKey);
            tmdbKey = newKey;
            log.info("TMDb client initialized / refreshed");
        }
    }


    private void refreshOpenAi(OpenlistConfig config) {
        String key = config.getOpenAiApiKey();
        String endpoint = config.getOpenAiEndpoint();
        String model = config.getOpenAiModel();


        if (StringUtils.isBlank(key)) {
            openAIClient = null;
            openAiKey = null;
            openAiEndpoint = null;
            openAiModel = null;
            return;
        }


        boolean changed = !StringUtils.equals(key, openAiKey)
                || !StringUtils.equals(endpoint, openAiEndpoint)
                || !StringUtils.equals(model, openAiModel);


        if (changed || openAIClient == null) {
            openAIClient = new OpenAIClient(key, endpoint, StringUtils.isBlank(model) ? null : model);
            openAiKey = key;
            openAiEndpoint = endpoint;
            openAiModel = model;
            log.info("OpenAI client initialized / refreshed");
        }
    }


    public boolean available() {
        return tmdbClient != null;
    }


    public TMDbClient tmdb() {
        return tmdbClient;
    }


    public OpenAIClient openAI() {
        return openAIClient;
    }
}
