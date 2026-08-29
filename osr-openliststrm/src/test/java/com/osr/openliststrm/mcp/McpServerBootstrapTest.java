package com.osr.openliststrm.mcp;

import com.osr.openliststrm.mcp.tool.DownloadTools;
import com.osr.openliststrm.mcp.tool.OpsTools;
import com.osr.openliststrm.mcp.tool.SubscriptionTools;
import com.osr.openliststrm.mcp.tool.TaskTools;
import com.osr.openliststrm.mcp.tool.TrackingTools;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用<b>真实的</b> SDK 把全部工具装配一遍。
 * <p>
 * 这个测试要挡的是一类只在启动时才暴露的失败：MCP SDK 在 {@code build()} 里会校验每个工具的
 * inputSchema（{@code validateSyncToolSchemas}），schema 写坏了不是「那个工具不好用」，
 * 而是<b>整个应用起不来</b>。而 schema 是用 Map 手工拼的，编译器一个字都检查不了。
 * </p>
 * <p>
 * 这不能替代容器里的启动验证（Spring 的 bean 装配、`@Lazy` 循环依赖那些只有真起容器才验得到），
 * 但它把「schema 拼错」这条最可能踩、又最容易在本地漏掉的路径拦在了单测里。
 * </p>
 * <p>
 * 工具组用 {@code new} 而不是从 Spring 拿：{@code tools()} 只是<b>声明</b>，
 * 处理函数是 lambda，注入的 Controller 要到真正调用时才解引用。
 * </p>
 *
 * @author Jack
 */
class McpServerBootstrapTest {

    private McpToolRegistry realRegistry() {
        return new McpToolRegistry(List.of(
                new SubscriptionTools(), new TrackingTools(), new DownloadTools(),
                new TaskTools(), new OpsTools()));
    }

    @Test
    void 全部工具能被SDK装配起来() {
        McpToolRegistry registry = realRegistry();
        HttpServletStreamableServerTransportProvider transport =
                HttpServletStreamableServerTransportProvider.builder()
                        .mcpEndpoint(McpAuthFilter.MCP_PATH)
                        .contextExtractor(request -> McpTransportContext.EMPTY)
                        .build();

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("osr-mcp-test", "0.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .build();

        // 逐个添加，与 McpServerConfig 一致：一次一个，schema 有问题时报错能定位到具体工具
        for (var specification : registry.specifications()) {
            server.addTool(specification);
        }

        assertNotNull(server);
        server.close();
    }

    @Test
    void 每个工具的inputSchema结构完整() {
        for (McpToolSpec spec : realRegistry().specs()) {
            Map<String, Object> schema = spec.inputSchema();
            assertEquals("object", schema.get("type"), spec.name() + " 的 schema 顶层必须是 object");
            assertTrue(schema.get("properties") instanceof Map, spec.name() + " 缺少 properties");
            assertTrue(schema.get("required") instanceof List, spec.name() + " 缺少 required");

            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            for (String name : required) {
                assertTrue(properties.containsKey(name),
                        spec.name() + " 把 " + name + " 列进了 required，但 properties 里没有它——"
                                + "客户端会认为这个工具永远没法被合法调用");
            }
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> property = (Map<String, Object>) entry.getValue();
                assertNotNull(property.get("type"),
                        spec.name() + " 的参数 " + entry.getKey() + " 没写 type");
                assertNotNull(property.get("description"),
                        spec.name() + " 的参数 " + entry.getKey() + " 没写 description，"
                                + "模型只能靠猜这个参数是干什么的");
            }
        }
    }

    @Test
    void 列表类工具都带分页参数() {
        // 不带分页的列表工具会把整张表的第一页按后端默认值返回，而模型无从翻页——
        // 它只会以为"就这么多"
        List<String> listTools = realRegistry().specs().stream()
                .map(McpToolSpec::name)
                .filter(name -> name.startsWith("list_"))
                .toList();
        assertFalse(listTools.isEmpty(), "一个 list_ 开头的工具都没有，这个断言就形同虚设");

        for (String name : listTools) {
            McpToolSpec spec = realRegistry().specs().stream()
                    .filter(t -> t.name().equals(name)).findFirst().orElseThrow();
            @SuppressWarnings("unchecked")
            Map<String, Object> properties = (Map<String, Object>) spec.inputSchema().get("properties");
            assertTrue(properties.containsKey(McpArgs.PAGE) && properties.containsKey(McpArgs.PAGE_SIZE),
                    "列表工具 " + name + " 没有声明分页参数");
        }
    }
}
