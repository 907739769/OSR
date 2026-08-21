package com.osr.common.utils;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.osr.common.config.JwtConfigProperties;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import io.jsonwebtoken.Jws;

/**
 * JWT工具类
 * 
 * @author osr
 */
@Component
public class JwtTokenUtil
{
    @Autowired
    private JwtConfigProperties jwtConfig;

 /**
     * 生成令牌
     *
     * @param loginName 登录名称（作为 subject）
     * @param userId 用户 ID
     * @param claims 额外声明
     * @return 令牌
     */
    public String generateToken(String loginName, Long userId, Map<String, Object> claims)
    {
        return generateToken(loginName, userId, claims, jwtConfig.getExpiration());
    }

    /**
     * 生成令牌（指定过期时间）
     *
     * @param loginName 登录名称（作为 subject）
     * @param userId 用户 ID
     * @param claims 额外声明
     * @param expirationMillis 过期时间（毫秒）
     * @return 令牌
     */
    public String generateToken(String loginName, Long userId, Map<String, Object> claims, long expirationMillis)
    {
        Date now = new Date();
        Date expirationDate = new Date(now.getTime() + expirationMillis);

        return Jwts.builder()
                .subject(loginName)
                .claim("userId", userId)
                .claims(claims)
                .issuedAt(now)
                .expiration(expirationDate)
                .signWith(generateSecretKey())
                .compact();
    }

    /**
     * 生成刷新令牌
     *
     * @param loginName 登录名称
     * @param userId 用户 ID
     * @return 刷新令牌
     */
    public String generateRefreshToken(String loginName, Long userId)
    {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", "refresh");
        return generateToken(loginName, userId, claims, jwtConfig.getRefreshExpiration());
    }

    /**
     * 解析并验证令牌
     *
     * @param token 令牌
     * @return 解析后的Claims
     */
    public Jws<Claims> parseToken(String token)
    {
        return Jwts.parser()
                .verifyWith(generateSecretKey())
                .build()
                .parseSignedClaims(token);
    }

    /**
     * 从令牌中获取用户名
     *
     * @param token 令牌
     * @return 用户名
     */
    public String getUsernameFromToken(String token)
    {
        return getClaimsFromToken(token).getSubject();
    }

    /**
     * 从令牌中获取用户ID
     *
     * @param token 令牌
     * @return 用户ID
     */
    public Long getUserIdFromToken(String token)
    {
        Claims claims = getClaimsFromToken(token);
        Object userId = claims.get("userId");
        if (userId instanceof Number)
        {
            return ((Number) userId).longValue();
        }
        return Long.parseLong(userId.toString());
    }

    /**
     * 判断令牌是否过期
     *
     * @param token 令牌
     * @return 是否过期
     */
    public boolean isTokenExpired(String token)
    {
        try
        {
            Date expiration = getExpirationFromToken(token);
            return expiration.before(new Date());
        }
        catch (ExpiredJwtException e)
        {
            return true;
        }
    }

    /**
     * 验证令牌是否有效
     *
     * @param token 令牌
     * @param loginName 登录名称
     * @return 是否有效
     */
    public boolean isTokenValid(String token, String loginName)
    {
        try
        {
            String username = getUsernameFromToken(token);
            return username.equals(loginName) && !isTokenExpired(token);
        }
        catch (JwtException | IllegalArgumentException e)
        {
            return false;
        }
    }

    /**
     * 从令牌中获取过期时间
     *
     * @param token 令牌
     * @return 过期时间
     */
    public Date getExpirationFromToken(String token)
    {
        return getClaimsFromToken(token).getExpiration();
    }

    /**
     * 从令牌中获取签发时间（{@code iat}）。
     *
     * @param token 令牌
     * @return 签发时间；令牌没有该声明时返回 null
     */
    public Date getIssuedAtFromToken(String token)
    {
        return getClaimsFromToken(token).getIssuedAt();
    }

    /**
     * 这个令牌是不是在用户最后一次改密码<b>之前</b>签发的——是的话它已经失效。
     * <p>
     * 无状态 JWT 没有"注销"这一说：签出去的令牌在过期前一直有效，改了密码也一样。
     * 于是"我密码好像泄露了，赶紧改一个"这个动作<b>什么也挡不住</b>，拿着旧令牌的人
     * 在令牌自然过期前照常访问——而这恰恰是用户改密码时唯一想达成的效果。
     * 拿 {@code sys_user.pwd_update_date} 当水位线，比维护一张令牌黑名单便宜得多，
     * 也不需要引入 Redis。
     * </p>
     * <p>
     * <b>比较前把水位线向下取整到秒</b>：JWT 的 {@code iat} 只有秒精度（签发时是向下取整的），
     * 而 {@code pwd_update_date} 带毫秒。不取整的话，同一秒内先签发、后改密码的令牌会被算成
     * "早于水位线"而误杀——正常流程走不到，但登录接口在改密之后立刻签发新令牌的场景会踩上，
     * 表现为"刚改完密码、刚登录成功，下一个请求就 401"，且重试一次又好了，极难复现。
     * 代价只是同一秒内的令牌会被放过一次，可以忽略。
     * </p>
     *
     * @param token          待检查的令牌
     * @param pwdUpdateDate  用户最后一次改密时间；为 null（该用户从没改过密码）时一律不失效
     * @return true 表示令牌应当被拒绝
     */
    public boolean isInvalidatedByPasswordChange(String token, Date pwdUpdateDate)
    {
        if (pwdUpdateDate == null)
        {
            return false;
        }
        try
        {
            Date issuedAt = getIssuedAtFromToken(token);
            // 取不到 iat 的令牌不做判断：本项目签发的令牌一定带 iat（见 generateToken），
            // 走到这里说明是别处签的，交给签名校验去否决，不在这里额外加一道
            if (issuedAt == null)
            {
                return false;
            }
            long watermark = pwdUpdateDate.getTime() / 1000L * 1000L;
            return issuedAt.getTime() < watermark;
        }
        catch (JwtException | IllegalArgumentException e)
        {
            // 解析不出来的令牌本来就无效，交给调用方的其余校验否决即可
            return false;
        }
    }

    /**
     * 从令牌中获取声明
     *
     * @param token 令牌
     * @return 声明
     */
    private Claims getClaimsFromToken(String token)
    {
        return parseToken(token).getPayload();
    }

    /**
     * 生成HS256密钥
     *
     * @return SecretKey
     */
    public SecretKey generateSecretKey()
    {
        return new SecretKeySpec(jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
