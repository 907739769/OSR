package com.osr.framework.config;

import com.osr.framework.security.JwtAuthenticationFilter;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.ServletContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code SecurityConfig#jwtAuthenticationFilterRegistration} —— 关掉
 * {@link JwtAuthenticationFilter} 的 servlet 层自动注册。
 *
 * <p>要守的事实是 Spring Boot 的一条默认行为：容器里每个 {@code Filter} bean 都会被自动
 * 注册进 servlet 过滤器链。{@code JwtAuthenticationFilter} 标了 {@code @Component}，而
 * {@code SecurityConfig} 又用 {@code addFilterBefore} 把它挂进了 Security 链，于是每个带
 * {@code Authorization: Bearer} 头的请求都会把它跑两遍——多一次 {@code loadUserByUsername}
 * （即一次 {@code select * from sys_user} 加角色、菜单查询），而外层那次写进
 * {@code SecurityContextHolder} 的结果随后会被 {@code SecurityContextHolderFilter} 用空上下文
 * 覆盖掉（会话策略 STATELESS），纯属白做。
 *
 * <p><b>这个缺陷没有任何错误现象</b>：认证行为完全正确，接口照常 200，只是每个请求悄悄多打了
 * 一轮库。所以它从代码审查、冒烟测试、功能测试里都看不出来，删掉那个 bean 也不会有别的测试
 * 变红——本类存在的全部理由就是让那次删除立刻失败。
 *
 * <p>第一条用例是<b>对照组</b>：它证明「不加那个 bean 就真的会被自动注册」这个前提今天仍然
 * 成立。哪天 Spring Boot 改掉了这条默认行为，它会先红，那时才轮到讨论要不要把 bean 拿掉。
 */
class JwtAuthenticationFilterRegistrationTest {

    /**
     * 对照组：只有 {@code @Component} 的 Filter bean、没有注册项时，Boot 确实会把它挂上
     * servlet 链。这条红了说明前提变了，不是代码坏了。
     */
    @Test
    @DisplayName("对照组：没有注册项时，Filter bean 会被 Boot 自动注册进 servlet 链")
    void filterBeanIsAutoRegisteredWithoutRegistrationBean() throws Exception {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("jwtAuthenticationFilter", new JwtAuthenticationFilter());

        ServletContext servletContext = applyInitializers(beanFactory);

        verify(servletContext, atLeastOnce()).addFilter(anyString(), any(Filter.class));
    }

    @Test
    @DisplayName("加上 enabled=false 的注册项后，servlet 链上一次都不注册")
    void registrationBeanSuppressesServletAutoRegistration() throws Exception {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("jwtAuthenticationFilter", filter);
        beanFactory.registerSingleton("jwtAuthenticationFilterRegistration",
                new SecurityConfig().jwtAuthenticationFilterRegistration(filter));

        ServletContext servletContext = applyInitializers(beanFactory);

        verify(servletContext, never()).addFilter(anyString(), any(Filter.class));
    }

    /**
     * 注册项必须是关着的，且必须持有<b>与 Security 链里同一个实例</b>：Boot 是拿
     * {@code getFilter()} 返回的对象与容器里的 Filter bean 逐个比对来决定跳过谁的，换成
     * {@code @Lazy} 代理或另 new 一个都比不上，那样这个 bean 就完全不起作用了——而表面上
     * 依旧一切正常。
     */
    @Test
    @DisplayName("注册项自身关着，且包的就是容器里那个 Filter 实例")
    void registrationBeanIsDisabledAndWrapsTheSameInstance() {
        JwtAuthenticationFilter filter = new JwtAuthenticationFilter();

        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new SecurityConfig().jwtAuthenticationFilterRegistration(filter);

        assertFalse(registration.isEnabled(), "注册项必须是关着的，否则等于没改");
        assertSame(filter, registration.getFilter(), "必须持有容器里那个实例，否则 Boot 认不出来");
    }

    /** 按 Boot 启动时的做法把全部 {@code ServletContextInitializer} 应用到一个假的 ServletContext 上。 */
    private ServletContext applyInitializers(DefaultListableBeanFactory beanFactory) throws Exception {
        ServletContext servletContext = mock(ServletContext.class);
        // 注册成功时 Boot 要拿这个返回值继续配置，返回 null 会被它当成「注册失败」而抛异常。
        when(servletContext.addFilter(anyString(), any(Filter.class)))
                .thenReturn(mock(FilterRegistration.Dynamic.class));
        for (ServletContextInitializer initializer : new ServletContextInitializerBeans(beanFactory)) {
            initializer.onStartup(servletContext);
        }
        return servletContext;
    }
}
