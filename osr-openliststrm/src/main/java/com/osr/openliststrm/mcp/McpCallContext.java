package com.osr.openliststrm.mcp;

import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.core.page.PageContext;
import com.osr.common.utils.CurrentUserContext;
import com.osr.common.utils.ThreadTraceIdUtil;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 把一次 MCP 工具调用绑定成「某个用户在操作」。
 * <p>
 * 这是整个 MCP 层最要紧的一块。工具的实现是<b>直接调用现有 Controller bean</b> 的方法——
 * 不走 HTTP、也不复制一份业务判断——而那些 Controller 判权限时读的是两个线程级的东西：
 * {@code CurrentUserContext}（{@code getUserId()} / {@code isAdmin()} 的第一条判据）
 * 与 {@code SecurityContextHolder}（{@code isAdmin()} 的第二条判据）。
 * 本类负责在调用前把它们填上、调用后原样恢复，于是
 * {@code denyIfInaccessible} / {@code adminOnlyWrite} / 订阅归属过滤<b>一行都不用改</b>
 * 就对 MCP 生效。
 * </p>
 * <p>
 * <b>为什么不能省掉这一步、指望「反正跑在 servlet 线程上」</b>：MCP SDK 的同步服务端默认把
 * 工具处理函数包成 {@code Mono.fromCallable(...).subscribeOn(boundedElastic)}，
 * 它<b>不在</b> servlet 线程上执行；SSE 那条路径更是走异步 servlet。线程一换，
 * 上面两个 ThreadLocal 全是空的——表现不是报错，而是「所有订阅都看不见」
 * （当前用户为 null 时列表只放行无归属的公共订阅）。
 * </p>
 * <p>
 * <b>为什么是「恢复」而不是「清空」</b>：这两个 ThreadLocal 未必属于我们。若某个执行路径
 * 恰好复用了正在处理别的请求的线程，直接 {@code clearContext()} 会把那个请求的身份抹掉。
 * 保存-恢复的成本只是两个引用，而清空的错误方向是让别人失去身份。
 * </p>
 *
 * @author Jack
 */
public final class McpCallContext
{
    /** {@code McpTransportContext} 里存放 {@link McpPrincipal} 的键 */
    public static final String PRINCIPAL_KEY = "osr.mcp.principal";

    /**
     * 当前线程正在执行的那次 MCP 调用的发起方。
     * <p>
     * 工具的处理函数签名里只有入参（{@code McpArgs}），刻意不带身份——绝大多数工具不该关心
     * 「谁在调」，那是 Controller 的判定。少数确实需要的（提交后台作业时要把身份传给作业线程）
     * 从这里取。
     * </p>
     */
    private static final ThreadLocal<McpPrincipal> CURRENT_PRINCIPAL = new ThreadLocal<>();

    /** 当前调用的发起方；不在 MCP 调用中时返回 null */
    public static McpPrincipal currentPrincipal()
    {
        return CURRENT_PRINCIPAL.get();
    }

    /** 当前调用的发起方，取不到就抛——供必须有身份才能继续的路径使用 */
    public static McpPrincipal requirePrincipal()
    {
        McpPrincipal principal = CURRENT_PRINCIPAL.get();
        if (principal == null)
        {
            throw new McpToolException("当前调用没有绑定身份，无法继续");
        }
        return principal;
    }

    private McpCallContext()
    {
    }

    /**
     * 绑定身份，返回一个必须关闭的句柄。
     * <p>
     * 用 try-with-resources 调用——<b>漏掉恢复就是把上一个令牌的身份留给下一次调用</b>，
     * 而线程池会复用线程，所以这不是理论风险。做成 {@link AutoCloseable}
     * 而不是 bind/clear 一对静态方法，就是为了让「忘了收尾」在语法上更难写出来。
     * </p>
     */
    public static Binding bind(McpPrincipal principal)
    {
        return new Binding(principal);
    }

    /** 绑定句柄，{@link #close()} 时恢复线程原有的上下文 */
    public static final class Binding implements AutoCloseable
    {
        private final SysUser previousUser;
        private final SecurityContext previousSecurityContext;
        private final String previousTraceId;
        private final McpPrincipal previousPrincipal;

        private Binding(McpPrincipal principal)
        {
            this.previousUser = CurrentUserContext.getCurrentUser();
            this.previousSecurityContext = SecurityContextHolder.getContext();
            this.previousTraceId = MDC.get(ThreadTraceIdUtil.TRACE_ID_KEY);
            this.previousPrincipal = CURRENT_PRINCIPAL.get();

            CURRENT_PRINCIPAL.set(principal);
            CurrentUserContext.setCurrentUser(principal.user());

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            if (principal.userDetails() != null)
            {
                context.setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal.userDetails(), null, principal.userDetails().getAuthorities()));
            }
            SecurityContextHolder.setContext(context);

            // 工具处理函数多半不在 servlet 线程上跑，MDC 不会自动带过来。
            // 没有 traceId 的话，一次工具调用产生的所有日志在实时日志页里串不成一条链路，
            // 而那正是排查「助理到底做了什么」时唯一好用的过滤手段
            ThreadTraceIdUtil.initTraceId();
        }

        @Override
        public void close()
        {
            PageContext.clear();
            if (previousPrincipal != null)
            {
                CURRENT_PRINCIPAL.set(previousPrincipal);
            }
            else
            {
                CURRENT_PRINCIPAL.remove();
            }
            if (previousUser != null)
            {
                CurrentUserContext.setCurrentUser(previousUser);
            }
            else
            {
                CurrentUserContext.clearCurrentUser();
            }
            SecurityContextHolder.setContext(previousSecurityContext);
            if (previousTraceId != null)
            {
                MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, previousTraceId);
            }
            else
            {
                MDC.remove(ThreadTraceIdUtil.TRACE_ID_KEY);
            }
        }
    }
}
