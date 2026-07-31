package com.osr.framework.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

/**
 * WebSocket 配置
 */
@Configuration
public class WebSocketConfig {
    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        log.info(">>> ServerEndpointExporter bean created");
        return new ServerEndpointExporter();
    }
}
