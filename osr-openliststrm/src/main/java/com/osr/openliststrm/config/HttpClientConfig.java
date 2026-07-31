package com.osr.openliststrm.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 共享 OkHttpClient 配置，所有 HTTP 客户端复用同一连接池。
 * 各使用方可通过 client.newBuilder() 在此基础上调整超时等参数。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient sharedOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(10, 5, TimeUnit.MINUTES))
                .build();
    }
}
