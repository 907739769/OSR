package com.osr.openliststrm.mcp;

import com.osr.common.core.domain.Result;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.utils.CurrentUserContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具调用的公共包装：权限档校验、{@code Result} 拆包、异常转换。
 * <p>
 * 这三件事之所以收在 {@link McpToolRegistry} 一处，就是因为漏掉任何一件都<b>不会报错</b>——
 * 漏权限校验表现为只读令牌能删东西，漏拆包表现为模型把 {@code code=500} 的失败读成成功。
 * </p>
 *
 * @author Jack
 */
class McpToolRegistryTest {

    @AfterEach
    void 清理线程状态() {
        CurrentUserContext.clearCurrentUser();
        SecurityContextHolder.clearContext();
    }

    private McpPrincipal principal(McpScope scope) {
        SysUser user = new SysUser();
        user.setUserId(7L);
        user.setLoginName("alice");
        return new McpPrincipal(user, null, scope, 1, "测试令牌");
    }

    private McpToolRegistry registryOf(McpToolSpec... specs) {
        return new McpToolRegistry(List.of(() -> List.of(specs)));
    }

    private String textOf(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    private McpSchema.CallToolRequest request(String name) {
        return new McpSchema.CallToolRequest(name, Map.of());
    }

    @Test
    void 只读令牌调用写工具会被拒绝且不执行处理函数() {
        boolean[] executed = {false};
        McpToolSpec spec = McpToolSpec.named("do_write").describe("一个写工具，用于测试").write()
                .handle(args -> {
                    executed[0] = true;
                    return "不该走到这里";
                });

        McpSchema.CallToolResult result = registryOf(spec)
                .invoke(spec, principal(McpScope.READ), request("do_write"));

        assertTrue(result.isError());
        assertTrue(textOf(result).contains("权限档"));
        assertFalse(executed[0], "权限校验必须排在处理函数之前——先执行再拒绝等于没有权限校验");
    }

    @Test
    void 写档令牌拿不到admin档的工具() {
        McpToolSpec spec = McpToolSpec.named("do_destroy").describe("一个破坏性工具，用于测试")
                .destructive().handle(args -> "ok");

        McpSchema.CallToolResult result = registryOf(spec)
                .invoke(spec, principal(McpScope.WRITE), request("do_destroy"));

        assertTrue(result.isError());
    }

    @Test
    void admin档覆盖全部档位() {
        McpToolSpec spec = McpToolSpec.named("do_destroy").describe("一个破坏性工具，用于测试")
                .destructive().handle(args -> "done");

        McpSchema.CallToolResult result = registryOf(spec)
                .invoke(spec, principal(McpScope.ADMIN), request("do_destroy"));

        assertFalse(Boolean.TRUE.equals(result.isError()));
        assertTrue(textOf(result).contains("done"));
    }

    @Test
    void 没有身份时一律拒绝() {
        McpToolSpec spec = McpToolSpec.named("read_something").describe("一个只读工具，用于测试")
                .handle(args -> "data");

        McpSchema.CallToolResult result = registryOf(spec).invoke(spec, null, request("read_something"));

        assertTrue(result.isError());
    }

    @Test
    void 失败的Result会变成错误结果而不是长得像成功的数据() {
        McpToolSpec spec = McpToolSpec.named("read_something").describe("一个只读工具，用于测试")
                .handle(args -> Result.error("订阅不存在或无权访问"));

        McpSchema.CallToolResult result = registryOf(spec)
                .invoke(spec, principal(McpScope.READ), request("read_something"));

        assertTrue(result.isError(), "code!=200 必须转成 isError，否则模型会把失败读成拿到了结果");
        assertEquals("订阅不存在或无权访问", textOf(result), "原始提示要原样回给模型，不要换成泛化文案");
    }

    @Test
    void 成功的Result只回data不回包装字段() {
        McpToolSpec spec = McpToolSpec.named("read_something").describe("一个只读工具，用于测试")
                .handle(args -> Result.success(Map.of("id", 42)));

        String text = textOf(registryOf(spec)
                .invoke(spec, principal(McpScope.READ), request("read_something")));

        assertTrue(text.contains("42"));
        assertFalse(text.contains("操作成功"), "Result 的包装字段每次调用都白烧一遍 token");
    }

    @Test
    void 业务异常的说明原样回给模型() {
        McpToolSpec spec = McpToolSpec.named("read_something").describe("一个只读工具，用于测试")
                .handle(args -> {
                    throw new McpToolException("缺少必填参数 id");
                });

        McpSchema.CallToolResult result = registryOf(spec)
                .invoke(spec, principal(McpScope.READ), request("read_something"));

        assertTrue(result.isError());
        assertEquals("缺少必填参数 id", textOf(result));
    }

    @Test
    void 未预期的异常不会把调用炸穿() {
        McpToolSpec spec = McpToolSpec.named("read_something").describe("一个只读工具，用于测试")
                .handle(args -> {
                    throw new IllegalStateException("数据库连不上");
                });

        McpSchema.CallToolResult result = registryOf(spec)
                .invoke(spec, principal(McpScope.READ), request("read_something"));

        assertTrue(result.isError(), "异常必须转成错误结果——抛出去会让整个 MCP 会话收到协议级错误");
    }

    @Test
    void 处理函数内能取到当前身份() {
        McpToolSpec spec = McpToolSpec.named("read_something").describe("一个只读工具，用于测试")
                .handle(args -> McpCallContext.requirePrincipal().user().getLoginName());

        String text = textOf(registryOf(spec)
                .invoke(spec, principal(McpScope.READ), request("read_something")));

        assertTrue(text.contains("alice"));
    }

    @Test
    void 重名工具在启动时就被拦下() {
        McpToolSpec first = McpToolSpec.named("same").describe("第一个同名工具").handle(args -> "a");
        McpToolSpec second = McpToolSpec.named("same").describe("第二个同名工具").handle(args -> "b");

        McpToolRegistry registry = registryOf(first, second);

        assertThrows(IllegalStateException.class, registry::specs,
                "重名工具会静默覆盖，等于某个工具凭空消失，必须在启动时就拦下");
    }

    @Test
    void 缺少说明的工具声明不出来() {
        assertThrows(IllegalStateException.class,
                () -> McpToolSpec.named("no_desc").handle(args -> "x"));
    }
}
