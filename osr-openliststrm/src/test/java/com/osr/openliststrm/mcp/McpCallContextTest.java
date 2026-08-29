package com.osr.openliststrm.mcp;

import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.core.page.PageContext;
import com.osr.common.utils.CurrentUserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 身份绑定的守卫。
 * <p>
 * 这里钉住的是 MCP 层唯一一个「出错了不会报错」的地方：绑定漏了，工具以匿名身份执行
 * （表现是"一条订阅都查不到"）；恢复漏了，上一个令牌的身份留在线程上被下一次调用捡走
 * （表现是偶发的越权，而线程池必然复用线程，所以这不是理论风险）。
 * </p>
 *
 * @author Jack
 */
class McpCallContextTest {

    @AfterEach
    void 清理线程状态() {
        CurrentUserContext.clearCurrentUser();
        SecurityContextHolder.clearContext();
        PageContext.clear();
    }

    private McpPrincipal principal(long userId, String loginName) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setLoginName(loginName);
        return new McpPrincipal(user, null, McpScope.READ, 1, "测试令牌");
    }

    @Test
    void 绑定后当前用户就是令牌归属人() {
        try (McpCallContext.Binding ignored = McpCallContext.bind(principal(7L, "alice"))) {
            assertEquals(7L, CurrentUserContext.getUserId());
            assertEquals("alice", CurrentUserContext.getLoginName());
        }
    }

    @Test
    void 关闭后必须恢复成绑定前的状态() {
        assertNull(CurrentUserContext.getCurrentUser(), "前置条件：线程上本来没有用户");
        try (McpCallContext.Binding ignored = McpCallContext.bind(principal(7L, "alice"))) {
            assertEquals(7L, CurrentUserContext.getUserId());
        }
        assertNull(CurrentUserContext.getCurrentUser(),
                "绑定前没有用户，关闭后就必须仍然没有——留着的话下一次调用会以 alice 的身份执行");
        assertNull(McpCallContext.currentPrincipal());
    }

    @Test
    void 处理函数抛异常时同样要恢复() {
        assertThrows(IllegalStateException.class, () -> {
            try (McpCallContext.Binding ignored = McpCallContext.bind(principal(7L, "alice"))) {
                throw new IllegalStateException("工具炸了");
            }
        });
        assertNull(CurrentUserContext.getCurrentUser(),
                "异常路径是最容易漏掉恢复的一条，而它恰恰是身份泄漏最可能发生的地方");
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void 嵌套绑定按栈恢复() {
        try (McpCallContext.Binding outer = McpCallContext.bind(principal(7L, "alice"))) {
            try (McpCallContext.Binding inner = McpCallContext.bind(principal(9L, "bob"))) {
                assertEquals(9L, CurrentUserContext.getUserId());
                assertEquals(9L, McpCallContext.currentPrincipal().user().getUserId());
            }
            assertEquals(7L, CurrentUserContext.getUserId(),
                    "内层结束后必须回到外层的身份，而不是被清空");
            assertEquals(7L, McpCallContext.currentPrincipal().user().getUserId());
        }
    }

    @Test
    void 关闭时会清掉分页覆盖() {
        try (McpCallContext.Binding ignored = McpCallContext.bind(principal(7L, "alice"))) {
            PageContext.set(3, 50, "id", "desc");
        }
        assertNull(PageContext.get(),
                "分页覆盖留在线程上的话，下一个走同一线程的普通 HTTP 请求会拿到别人的分页参数");
    }

    @Test
    void 取不到身份时requirePrincipal要明确报错() {
        assertThrows(McpToolException.class, McpCallContext::requirePrincipal);
    }
}
