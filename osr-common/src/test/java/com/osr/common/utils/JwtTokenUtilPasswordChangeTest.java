package com.osr.common.utils;

import com.osr.common.config.JwtConfigProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改密码之后，此前签发的令牌必须失效。
 * <p>
 * 无状态 JWT 本身没有"注销"，签出去的令牌在过期前一直有效——于是"密码好像泄露了，
 * 赶紧改一个"这个动作在加这道判定之前<b>什么也挡不住</b>。判据是拿
 * {@code sys_user.pwd_update_date} 当水位线，比维护令牌黑名单便宜得多。
 * </p>
 */
class JwtTokenUtilPasswordChangeTest {

    private JwtTokenUtil jwtTokenUtil;

    @BeforeEach
    void setUp() throws Exception {
        jwtTokenUtil = new JwtTokenUtil();
        JwtConfigProperties config = new JwtConfigProperties();
        // HS256 要求密钥不短于 256 位
        config.setSecret("test-secret-key-must-be-at-least-32-bytes-long!!");
        config.setExpiration(3_600_000L);
        config.setRefreshExpiration(7_200_000L);
        inject(jwtTokenUtil, "jwtConfig", config);
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private String freshToken() {
        return jwtTokenUtil.generateToken("admin", 1L, Map.of());
    }

    @Test
    void 改密时间晚于签发时间_令牌失效() {
        String token = freshToken();
        Date changedLater = new Date(System.currentTimeMillis() + 10_000L);

        assertTrue(jwtTokenUtil.isInvalidatedByPasswordChange(token, changedLater));
    }

    @Test
    void 改密时间早于签发时间_令牌仍有效() {
        String token = freshToken();
        Date changedEarlier = new Date(System.currentTimeMillis() - 60_000L);

        assertFalse(jwtTokenUtil.isInvalidatedByPasswordChange(token, changedEarlier));
    }

    /**
     * 从没改过密码的用户（{@code pwd_update_date} 为 NULL）一律不失效。
     * 判成失效的话，升级上来的存量库里凡是没改过密码的人会在部署那一刻集体被登出。
     */
    @Test
    void 从没改过密码_不失效() {
        assertFalse(jwtTokenUtil.isInvalidatedByPasswordChange(freshToken(), null));
    }

    /**
     * 这条钉的是秒精度那个坑：JWT 的 {@code iat} 只有秒（签发时向下取整），
     * 而 {@code pwd_update_date} 带毫秒。不把水位线也向下取整到秒的话，
     * 「同一秒内先签发、后改密码」的令牌会被误判成"早于水位线"。
     * <p>
     * 现象会是：刚改完密码、刚登录成功，下一个请求就 401，重试一次又好了——
     * 一个几乎无法复现、也无法从日志看出成因的间歇性登出。
     * </p>
     */
    @Test
    void 同一秒内签发与改密_不误杀() {
        long now = System.currentTimeMillis();
        // 构造一个签发于本秒整点、而改密发生在本秒 900 毫秒处的场景
        long secondStart = now / 1000L * 1000L;
        String token = freshToken();
        Date changedSameSecondLater = new Date(secondStart + 900L);

        assertFalse(jwtTokenUtil.isInvalidatedByPasswordChange(token, changedSameSecondLater));
    }

    /** 下一秒改的密码就该生效了——上一条的宽容仅限同一秒内，不能顺手把判定整个放宽 */
    @Test
    void 下一秒改密_令牌失效() {
        String token = freshToken();
        long nextSecond = (System.currentTimeMillis() / 1000L + 1L) * 1000L;

        assertTrue(jwtTokenUtil.isInvalidatedByPasswordChange(token, new Date(nextSecond)));
    }

    /** 解析不出来的令牌交给签名校验去否决，不在这里额外抛异常 */
    @Test
    void 令牌无法解析_不抛异常且判为未失效() {
        assertFalse(jwtTokenUtil.isInvalidatedByPasswordChange("not-a-jwt", new Date()));
    }
}
