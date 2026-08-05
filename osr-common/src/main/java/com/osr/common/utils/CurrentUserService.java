package com.osr.common.utils;

import com.osr.common.core.domain.entity.SysUser;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class CurrentUserService {

    /**
     * 当前登录名，取不到时返回 {@code anonymous}。
     * <p>
     * <b>必须走 {@link #getUser()} 而不是直接读 {@link CurrentUserContext}</b>：
     * 那个 ThreadLocal 的类注释虽然写着「由 JwtAuthFilter 在请求开始时加载」，
     * 但实际上全项目没有任何过滤器填充它，直接读恒为 null。真正有值的是
     * {@code SecurityUserDetailsService} 写入的 request attribute，
     * {@link #getUser()} 已经覆盖了这条回退路径。
     */
    public String getLoginName() {
        SysUser user = getUser();
        return user != null ? user.getLoginName() : "anonymous";
    }

    /**
     * 当前登录用户ID，取不到时返回 null（而不是 0L —— 0 不是任何真实用户的ID，
     * 用它做兜底会让「未登录」被误判成「某个用户」）。
     * <p>
     * 与 {@link #getLoginName()} 同样的原因，必须走 {@link #getUser()}。
     * 曾经直接读 ThreadLocal 恒返回 null，导致订阅列表把管理员也当成未登录用户过滤，
     * 网页端看不到任何有归属的订阅。
     */
    public Long getUserId() {
        SysUser user = getUser();
        return user != null ? user.getUserId() : null;
    }

    public SysUser getUser() {
        try {
            SysUser user = CurrentUserContext.getCurrentUser();
            if (user != null) return user;
        } catch (Exception e) { /* */ }
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                Object user = request.getAttribute("currentUser");
                if (user instanceof SysUser sysUser) {
                    return sysUser;
                }
            }
        } catch (Exception e) { /* */ }
        return null;
    }
}
