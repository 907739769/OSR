package com.osr.common.utils;

import com.osr.common.core.domain.entity.SysUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 当前登录用户的获取。
 * <p>
 * 这里守的是一个曾经静默失效的缺陷：{@link CurrentUserContext} 那个 ThreadLocal
 * 的类注释写着「由 JwtAuthFilter 在请求开始时加载」，但项目里没有任何过滤器填充它，
 * 于是 getUserId() 恒返回 null。真正有值的是 SecurityUserDetailsService 写进
 * request attribute 的 currentUser。
 * <p>
 * 后果是订阅列表把管理员也当成未登录用户过滤，网页端看不到任何有归属的订阅——
 * 而接口照常 200、日志照常干净，只有数据少了，极难定位。
 */
class CurrentUserServiceTest {

    private final CurrentUserService service = new CurrentUserService();

    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        CurrentUserContext.clearCurrentUser();
    }

    private static SysUser user(Long id, String loginName) {
        SysUser user = new SysUser();
        user.setUserId(id);
        user.setLoginName(loginName);
        return user;
    }

    @Test
    void 用户在request属性里_能取到ID与登录名() {
        request.setAttribute("currentUser", user(1L, "admin"));

        assertEquals(1L, service.getUserId());
        assertEquals("admin", service.getLoginName());
    }

    /** ThreadLocal 有值时优先用它（虽然当前没有填充方，接口契约仍保留这条路径） */
    @Test
    void 用户在ThreadLocal里_优先取它() {
        CurrentUserContext.setCurrentUser(user(2L, "tom"));
        request.setAttribute("currentUser", user(1L, "admin"));

        assertEquals(2L, service.getUserId());
        assertEquals("tom", service.getLoginName());
    }

    /**
     * 取不到用户时 userId 返回 null 而不是 0L：0 不是任何真实用户的ID，
     * 用它兜底会让「未登录」被误判成「某个用户」。
     */
    @Test
    void 取不到用户_ID为null登录名为anonymous() {
        assertNull(service.getUserId());
        assertEquals("anonymous", service.getLoginName());
    }

    @Test
    void 无请求上下文_不抛异常() {
        RequestContextHolder.resetRequestAttributes();

        assertNull(service.getUserId());
        assertEquals("anonymous", service.getLoginName());
    }

    /** getUser / getUserId / getLoginName 必须取自同一来源，否则会出现「有用户但没ID」这种矛盾态 */
    @Test
    void 三个方法口径一致() {
        request.setAttribute("currentUser", user(7L, "jack"));

        SysUser user = service.getUser();
        assertEquals(user.getUserId(), service.getUserId());
        assertEquals(user.getLoginName(), service.getLoginName());
    }
}
