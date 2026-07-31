package com.osr.framework.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCacheNames(Arrays.asList(
                "sysCache", "sys-config", "sys-dict", "sys-authCache"
        ));
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10000)
                .expireAfterWrite(24, TimeUnit.HOURS)
                .expireAfterAccess(24, TimeUnit.HOURS)
        );
        // TMDb 原始响应的进程内短期缓存：与 tmdb_cache 数据库表使用同一套 key，
        // 仅用于摊薄同一批量刮削任务内的重复请求（同一部剧的多集），TTL 刻意设置得比 DB 缓存短很多，
        // 避免出现两套各自过期、互相不一致的缓存（历史上 @Cacheable 24h + DB 24h 双层缓存曾导致过这个问题）。
        cacheManager.registerCustomCache("tmdbRaw", Caffeine.newBuilder()
                .maximumSize(2000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .build());
        return cacheManager;
    }

    @Bean
    public KeyGenerator keyGenerator() {
        return (target, method, params) -> {
            StringBuilder sb = new StringBuilder();
            sb.append(target.getClass().getName()).append(".");
            sb.append(method.getName()).append("[");
            for (Object param : params) {
                sb.append(param.toString()).append(",");
            }
            sb.append("]");
            return sb.toString();
        };
    }
}
