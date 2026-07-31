package com.osr.common.core.domain;

import java.util.Map;

/**
 * JWT令牌响应DTO
 * 
 * @author osr
 */
public class JwtTokenDto
{
    /** 令牌 */
    private String token;

    /** 用户ID */
    private Long userId;

    /** 登录名称 */
    private String loginName;

    /** 用户名称 */
    private String userName;

    /** 权限列表 */
    private Map<String, Object> permissions;

    /** 过期时间 */
    private Long expireTime;

    private String refreshToken;

    private Long refreshExpireTime;

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getLoginName() { return loginName; }
    public void setLoginName(String loginName) { this.loginName = loginName; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public Map<String, Object> getPermissions() { return permissions; }
    public void setPermissions(Map<String, Object> permissions) { this.permissions = permissions; }
    public Long getExpireTime() { return expireTime; }
    public void setExpireTime(Long expireTime) { this.expireTime = expireTime; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public Long getRefreshExpireTime() { return refreshExpireTime; }
    public void setRefreshExpireTime(Long refreshExpireTime) { this.refreshExpireTime = refreshExpireTime; }
}
