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

    /**
     * 空闲连接上限。
     * <p>
     * <b>这是个跨全部下游主机的全局值，不是每主机值</b>——这一个池同时服务 OpenList
     * （STRM 遍历按 {@code openlist.traversal.concurrency} 并发列目录）、全部 Torznab 索引器、
     * qB/TR、Emby、五个通知渠道和刮削图片下载。原值 10 是 OkHttp 的默认值，对"一个客户端
     * 打一个后端"的常规用法够用，对这里不够：一轮遍历或一次多索引器检索的并发量就远超 10，
     * 突发过去之后只有 10 条连接被留下，其余全部关闭，下一轮再从 TCP 握手加 TLS 握手重来。
     * </p>
     * <p>
     * 抬高的代价只是空闲期多占几十个文件描述符和对端几十个连接槽位，而 keepAlive 到点
     * （下面的 5 分钟）照样回收，不会长期驻留。取 64 是按"索引器数量 × 单索引器并发步数
     * + 遍历并发"的量级估的，够覆盖实际峰值又不至于离谱。
     * </p>
     */
    private static final int MAX_IDLE_CONNECTIONS = 64;

    @Bean
    public OkHttpClient sharedOkHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(MAX_IDLE_CONNECTIONS, 5, TimeUnit.MINUTES))
                .build();
    }
}
