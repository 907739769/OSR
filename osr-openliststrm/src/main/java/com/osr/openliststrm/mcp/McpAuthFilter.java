package com.osr.openliststrm.mcp;

import com.osr.common.core.domain.entity.SysUser;
import com.osr.framework.security.ModuleAuthenticationFilter;
import com.osr.openliststrm.mybatisplus.domain.McpAccessTokenPlus;
import com.osr.openliststrm.mybatisplus.service.IMcpAccessTokenPlusService;
import com.osr.system.service.ISysUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * MCP 端点的令牌认证。
 * <p>
 * 只处理 {@value #MCP_PATH} 及其子路径，其余请求原样放行给
 * {@code JwtAuthenticationFilter}。认证方式是 {@code Authorization: Bearer osr_mcp_xxx}。
 * </p>
 * <p>
 * <b>{@code /mcp} 刻意<u>没有</u>放进 permitAll 白名单</b>：它照常受
 * {@code anyRequest().authenticated()} 约束。于是「令牌不对」这件事由 Spring Security
 * 统一拦成 401，本过滤器<b>只负责认证成功的那条路径</b>——不需要自己拼未认证响应，
 * 也就不存在「某个分支忘了 return，请求带着空身份一路走下去」这类静默漏洞。
 * 这也是 {@code JwtAuthenticationFilter} 的写法。
 * </p>
 * <p>
 * 认证成功时做两件事：往 SecurityContext 塞一个 Authentication（放行用），
 * 以及把 {@link McpPrincipal} 挂到 request attribute 上——传输层的 contextExtractor
 * 会把它取走塞进 {@code McpTransportContext}，那才是工具处理函数真正拿得到身份的途径
 * （处理函数不在 servlet 线程上，读不到 SecurityContextHolder）。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class McpAuthFilter extends OncePerRequestFilter implements ModuleAuthenticationFilter {

    /** MCP 端点路径，与 {@code McpServerConfig} 里注册的 servlet 路径必须一致 */
    public static final String MCP_PATH = "/mcp";

    /** 认证成功后挂 {@link McpPrincipal} 的 request attribute 名 */
    public static final String PRINCIPAL_ATTRIBUTE = "mcpPrincipal";

    private static final String BEARER = "Bearer ";

    @Autowired
    private IMcpAccessTokenPlusService tokenService;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private UserDetailsService userDetailsService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !(path.equals(MCP_PATH) || path.startsWith(MCP_PATH + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            McpPrincipal principal = authenticate(header.substring(BEARER.length()).trim());
            if (principal != null) {
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        principal.userDetails(), null, principal.userDetails().getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
                request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            }
        } catch (Exception e) {
            // 认证失败一律当作"没有身份"往下走，由 Security 拦成 401。
            // 这里用 warn 而不是 error：拿着过期/停用令牌来敲门是可预期的运行状态，
            // 不是服务端故障，而 sys-error.log 的价值在于噪音为零
            log.warn("MCP 令牌认证失败：{}", e.getMessage());
        }
        chain.doFilter(request, response);
    }

    /**
     * 校验令牌并组装身份。
     *
     * @return 校验通过的身份；令牌无效、归属用户不存在或已停用时返回 {@code null}
     */
    private McpPrincipal authenticate(String plaintext) {
        McpAccessTokenPlus token = tokenService.verify(plaintext);
        if (token == null) {
            return null;
        }
        SysUser user = userService.selectUserById(token.getOwnerUserId());
        if (user == null) {
            log.warn("MCP 令牌[#{}] 的归属用户 {} 已不存在，拒绝认证", token.getId(), token.getOwnerUserId());
            return null;
        }
        // 走 UserDetailsService 而不是自己拼权限：它顺带做了「用户是否已删除/停用」的判定
        // （查不到或状态异常时抛异常），也把 currentUser 写进 request attribute。
        // 角色权限是 isAdmin() 第二条判据的来源，自己拼必然与那边漂移
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getLoginName());
        tokenService.touch(token);
        return new McpPrincipal(user, userDetails, McpScope.parse(token.getScope()),
                token.getId(), token.getName());
    }
}
