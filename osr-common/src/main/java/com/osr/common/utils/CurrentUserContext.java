package com.osr.common.utils;

import com.osr.common.core.domain.entity.SysUser;

/**
 * 当前用户上下文（替代 Shiro SecurityUtils）
 * 由 JwtAuthFilter 在请求开始时从 RequestAttribute 加载到 ThreadLocal
 * 
 * @author osr
 */
public final class CurrentUserContext
{
    private static final ThreadLocal<SysUser> CURRENT_USER = new ThreadLocal<>();

    private CurrentUserContext() {}

    public static SysUser getCurrentUser()
    {
        return CURRENT_USER.get();
    }

    public static void setCurrentUser(SysUser user)
    {
        CURRENT_USER.set(user);
    }

    public static void clearCurrentUser()
    {
        CURRENT_USER.remove();
    }

    public static Long getUserId()
    {
        SysUser user = CURRENT_USER.get();
        return user != null ? user.getUserId() : null;
    }

    public static String getLoginName()
    {
        SysUser user = CURRENT_USER.get();
        return user != null ? user.getLoginName() : null;
    }
}
