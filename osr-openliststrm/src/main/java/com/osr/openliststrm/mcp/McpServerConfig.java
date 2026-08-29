package com.osr.openliststrm.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

/**
 * MCP 服务端的装配。
 * <p>
 * 端点是 {@value McpAuthFilter#MCP_PATH}，走 Streamable HTTP。传输实现取自 MCP Java SDK
 * <b>核心包</b>自带的 Jakarta Servlet 版本，因此这里不引入 Spring AI——理由见根 pom 里
 * 那条依赖的注释。
 * </p>
 *
 * <h2>部署时容易漏的一步</h2>
 * <p>
 * 生产环境的 nginx <b>必须为 {@code /mcp} 单开一条 location</b>，不能靠现有的
 * {@code location /api/} 兜住（端点也刻意没有放在 {@code /api} 前缀下，就是为了让这件事
 * 无法被忽略）：那条规则是 {@code proxy_read_timeout 120s} 且默认开着 buffering，
 * 而 MCP 的 SSE 长连接两样都受不了。症状很有迷惑性——初始化握手正常、工具列表也拿得到，
 * 只有跑得久一点的调用会莫名断线。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Configuration
public class McpServerConfig {

    @Value("${osr.name:OSR}")
    private String appName;

    @Value("${osr.version:0.0.0}")
    private String appVersion;

    /**
     * 传输层。
     * <p>
     * {@code contextExtractor} 是整条链路上唯一把「谁在调用」从 servlet 请求交到工具处理函数
     * 手里的通道：处理函数不在 servlet 线程上执行，读不到 SecurityContextHolder，也读不到
     * RequestContextHolder。这一行改坏了不会报错，表现是所有工具都以匿名身份执行
     * （{@code McpToolRegistry} 为此留了一道兜底判断）。
     * </p>
     */
    @Bean
    public HttpServletStreamableServerTransportProvider mcpTransportProvider() {
        return HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint(McpAuthFilter.MCP_PATH)
                .contextExtractor(request -> {
                    Object principal = request.getAttribute(McpAuthFilter.PRINCIPAL_ATTRIBUTE);
                    return principal == null
                            ? McpTransportContext.EMPTY
                            : McpTransportContext.create(Map.of(McpCallContext.PRINCIPAL_KEY, principal));
                })
                .build();
    }

    @Bean
    public ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServletRegistration(
            HttpServletStreamableServerTransportProvider transportProvider) {
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> registration =
                new ServletRegistrationBean<>(transportProvider, McpAuthFilter.MCP_PATH);
        registration.setName("mcpServlet");
        // SSE 那条路径走 request.startAsync()，不开异步支持的话 doGet 会直接抛
        registration.setAsyncSupported(true);
        return registration;
    }

    @Bean(destroyMethod = "close")
    public McpSyncServer mcpSyncServer(HttpServletStreamableServerTransportProvider transportProvider,
                                       McpToolRegistry toolRegistry) {
        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo(appName + "-mcp", appVersion)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .instructions("""
                        OSR (OpenList STRM Relay) 影视 STRM 管理系统。可以查询与管理 PT 订阅、\
                        追剧日历与缺集体检、下载记录，以及触发 STRM 生成、网盘同步、重命名任务。

                        几条会影响你怎么用这些工具的事实：
                        1. 涉及检索索引器的工具（search_missing_episodes、generate_strm_for_path）耗时可达数分钟，\
                        它们会立刻返回一个 jobId，请随后用 get_job_status 轮询，不要重复提交同一个请求。
                        2. 订阅按归属隔离，你只能看到并操作令牌归属用户自己的订阅与无归属的公共订阅。
                        3. 令牌有权限档（read/write/admin）。工具因权限被拒时会明确说明，那不是可以重试的错误。
                        4. 删除网盘文件、删种、修改索引器/下载器/参数配置等操作<不>提供工具，请引导用户去网页端操作。
                        """)
                // 默认 10 秒是给「服务端向客户端发起请求」用的；工具本身的耗时不受它约束，
                // 但放宽一点不吃亏，而卡在默认值上表现为莫名其妙的超时
                .requestTimeout(Duration.ofMinutes(5))
                .build();

        // 逐个添加而不是在 builder 上一次性 tools(...)：SDK 会在添加时校验 schema，
        // 一次一个才能在报错信息里定位到是哪个工具的 schema 写坏了
        for (var specification : toolRegistry.specifications()) {
            server.addTool(specification);
        }
        log.info("MCP 服务端已就绪，端点={} 工具数={}", McpAuthFilter.MCP_PATH,
                toolRegistry.specs().size());
        return server;
    }

    /**
     * 关掉 {@link McpAuthFilter} 作为<b>普通 servlet 过滤器</b>的自动注册。
     * <p>
     * Spring Boot 会把容器里每个 {@code Filter} bean 都注册进 servlet 过滤器链，而这个过滤器
     * 已经由 {@code SecurityConfig} 通过 {@code addFilterBefore} 插进 Security 链了。
     * 不关掉的话它对每个 {@code /mcp} 请求执行<b>两次</b>——多一次令牌查库、多一次用户加载，
     * 而且外层那次设置的 SecurityContext 随后会被 Security 链的
     * {@code SecurityContextHolderFilter} 覆盖掉，纯属白做。
     * </p>
     */
    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilterServletRegistration(McpAuthFilter filter) {
        FilterRegistrationBean<McpAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
