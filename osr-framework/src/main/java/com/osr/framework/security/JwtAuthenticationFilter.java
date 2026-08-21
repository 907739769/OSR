package com.osr.framework.security;

import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.utils.JwtTokenUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    @Autowired
    private JwtTokenUtil jwtTokenUtil;
    @Autowired
    private UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = authHeader.substring(7);
        try {
            // 先检查 token 是否过期，避免对过期 token 抛出不必要的异常
            if (jwtTokenUtil.isTokenExpired(token)) {
                filterChain.doFilter(request, response);
                return;
            }
            String username = jwtTokenUtil.getUsernameFromToken(token);
            if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                if (jwtTokenUtil.isTokenValid(token, username) && !invalidatedByPasswordChange(request, token)) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                    WebAuthenticationDetailsSource source = new WebAuthenticationDetailsSource();
                    authToken.setDetails(source.buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                }
            }
        } catch (Exception e) {
            // Token malformed — proceed as unauthenticated
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 令牌是不是在该用户最后一次改密码之前签发的（是则拒绝，见
     * {@link JwtTokenUtil#isInvalidatedByPasswordChange}）。
     * <p>
     * <b>刻意复用 {@code loadUserByUsername} 已经写进 request attribute 的那个 SysUser，
     * 不再查一次库</b>：这是每个业务请求都要走的路径，多一次
     * {@code select * from sys_user} 就是给整站所有接口加一次数据库往返。
     * {@code SecurityUserDetailsService} 在返回前会写入 {@code currentUser}，
     * 而本方法的唯一调用点排在它之后。
     * </p>
     * <p>
     * 取不到那个属性时<b>放行</b>（返回 false）：它是可选的加固，不该因为上游没写进来
     * 就把所有人挡在门外——那会是一次彻底的服务不可用，而它要防的是一个窗口期问题。
     * </p>
     */
    private boolean invalidatedByPasswordChange(HttpServletRequest request, String token) {
        Object attr = request.getAttribute("currentUser");
        if (!(attr instanceof SysUser user)) {
            return false;
        }
        return jwtTokenUtil.isInvalidatedByPasswordChange(token, user.getPwdUpdateDate());
    }
}
