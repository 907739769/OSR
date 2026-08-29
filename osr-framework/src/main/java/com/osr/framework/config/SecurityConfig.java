package com.osr.framework.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osr.common.core.domain.Result;
import com.osr.framework.config.properties.PermitAllUrlProperties;
import com.osr.framework.security.JwtAuthenticationFilter;
import com.osr.framework.security.ModuleAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置。
 * <p>
 * <b>{@code @EnableMethodSecurity} 当前是一道防呆而不是在用的功能</b>：仓库里现在一个
 * {@code @PreAuthorize} 都没有，加上它不改变任何行为。加它的理由是关掉一个静默陷阱——
 * 没有这个注解时，谁往 Controller 上标了 {@code @PreAuthorize}，Spring 不会报错、不会告警，
 * 只是那行注解<b>完全不生效</b>，接口照常 200 返回。这类"看起来加了权限其实没加"的缺陷
 * 从代码审查和冒烟测试里都看不出来。
 * </p>
 * <p>
 * 需要注意 {@code authorizeHttpRequests} 这一层<b>只做认证不做授权</b>
 * （{@code anyRequest().authenticated()}）：接口级的权限判定散在各 Controller 里，见
 * {@code BaseController#isAdmin} 与 {@code BaseCrudRestController#adminOnlyWrite}。
 * {@code sys_menu.perms} 目前只驱动前端菜单可见性，不参与后端放行。
 * </p>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Autowired
    @Lazy
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @Autowired
    private PermitAllUrlProperties permitAllUrlProperties;

    /**
     * 业务模块自带的认证过滤器（如 MCP 令牌），见 {@link ModuleAuthenticationFilter}。
     * 一个都没有时注入空列表，行为与引入这个扩展点之前完全一致。
     * <p>
     * <b>{@code @Lazy} 不能去掉</b>，理由与上面的 {@code jwtAuthenticationFilter} 完全相同：
     * 这类过滤器都要注入 {@code UserDetailsService}，而它那条依赖链最终会回到本配置类里的
     * {@code passwordEncoder}，构成循环依赖。表现是启动直接失败（{@code APPLICATION FAILED TO START}），
     * 而单元测试因为直接 new 过滤器、绕开 Spring 装配，一条都拦不住。
     * </p>
     */
    @Autowired(required = false)
    @Lazy
    private List<ModuleAuthenticationFilter> moduleAuthenticationFilters = new ArrayList<>();

    /**
     * 是否允许匿名访问 {@code /actuator/**}。<b>默认 false</b>。
     * <p>
     * Prometheus 这类抓取端拿不到 JWT，所以监控要能用就得开这个口子；但它默认必须是关的——
     * {@code /actuator/env} 会把包括数据库密码在内的全部配置原样吐出来，
     * {@code /actuator/heapdump} 更是直接给出一份内存快照。本项目的部署形态又常常是
     * 「路由器上开个端口映射就当远程访问了」，一个默认敞开的 actuator 等于把上面这些一并敞开。
     * </p>
     * <p>
     * 因此这里是<b>显式 opt-in</b>：设 {@code ACTUATOR_ANONYMOUS=true} 才放行，而且要配合
     * {@code application.yml} 里那份白名单——那边只暴露 health/info/metrics/prometheus 四个，
     * env 与 heapdump 压根不在其中。两道锁缺一不可：这道决定「谁能访问」，那道决定「有什么可访问」。
     * </p>
     */
    @Value("${ACTUATOR_ANONYMOUS:false}")
    private boolean actuatorAnonymous;

    /**
     * 关掉 {@link JwtAuthenticationFilter} 的 <b>servlet 层自动注册</b>，只保留它在 Security
     * 过滤链里的那一次（见下面 {@code filterChain} 的 {@code addFilterBefore}）。
     * <p>
     * 起因是 Spring Boot 有一条默认行为：<b>容器里每一个 {@code Filter} 类型的 bean 都会被
     * 自动包一层 {@code FilterRegistrationBean} 注册进 servlet 过滤器链</b>
     * （{@code ServletContextInitializerBeans#addAdaptableBeans}）。而
     * {@code JwtAuthenticationFilter} 标了 {@code @Component}，于是它同时挂在两条链上，
     * <b>每个带 {@code Authorization: Bearer} 头的请求都会把它整个跑两遍</b>：
     * </p>
     * <ul>
     *   <li>多一次 {@code parseToken} 和一次 {@code loadUserByUsername}，而后者是
     *       {@code select * from sys_user} 再加角色、菜单两次查询——这是<b>每个业务请求</b>
     *       都要白付的数据库往返，不是某个接口的个别开销。</li>
     *   <li>而且那一遍<b>纯属白做</b>：会话策略是 {@code STATELESS}，servlet 链（外层）那次写进
     *       {@code SecurityContextHolder} 的认证结果，紧接着就会被 Security 链最前面的
     *       {@code SecurityContextHolderFilter} 用一个空上下文覆盖掉。真正生效的自始至终
     *       只有 Security 链里那一次。</li>
     * </ul>
     * <p>
     * 这个 bug 的隐蔽之处在于<b>它没有任何错误现象</b>：认证行为完全正确，接口照常 200，
     * 只是每个请求悄悄多打了一轮库。所以这个 bean <b>删掉不会有任何测试或功能变红</b>——
     * {@code JwtAuthenticationFilterRegistrationTest} 就是为了让删掉它这件事立刻失败而写的。
     * </p>
     * <p>
     * 两条不要改坏的：
     * </p>
     * <ol>
     *   <li><b>必须持有与 Security 链里同一个实例</b>。Spring Boot 是拿
     *       {@code registration.getFilter()} 返回的对象去和容器里的 {@code Filter} bean
     *       逐个比对（{@code Seen#contains}，走的是 equals/hashCode）来决定跳过谁的，比不上
     *       就照样自动注册。所以这里刻意用<b>方法参数</b>注入真实 bean，而不是复用上面那个
     *       {@code @Lazy} 字段——那是个 CGLIB 代理，与真实 bean 不相等，写进来等于这个 bean
     *       完全不起作用，而表面上一切正常。</li>
     *   <li><b>是 {@code setEnabled(false)} 而不是把它从容器里摘掉</b>。注册项本身仍要存在，
     *       它的作用就是向 Boot 声明「这个 Filter 已经有人管了」；enabled=false 只是让它自己
     *       不往 servlet 链上挂。</li>
     * </ol>
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public AuthenticationEntryPoint jsonAuthenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(Result.error(401, "未认证"));
            response.getWriter().write(json);
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(customizer -> customizer.disable())
            .cors(customizer -> customizer.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth.requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers("/api/doc.html", "/api/v3/api-docs/**", "/swagger-resources/**", "/webjars/**").permitAll()
                    .requestMatchers("/websocket/**").permitAll()
                    .requestMatchers(getPermitAllUrls().toArray(new String[0])).permitAll();
                if (actuatorAnonymous) {
                    auth.requestMatchers("/actuator/**").permitAll();
                }
                auth.anyRequest().authenticated();
            })
            .exceptionHandling(ex -> ex.authenticationEntryPoint(jsonAuthenticationEntryPoint()))
            // 这是 JwtAuthenticationFilter 唯一真正生效的注册点；它的 servlet 层自动注册
            // 由 jwtAuthenticationFilterRegistration() 关掉，理由见那个方法的注释。
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        for (ModuleAuthenticationFilter filter : moduleAuthenticationFilters) {
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        }
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        String origins = System.getenv("CORS_ALLOWED_ORIGINS");
        if (origins != null && !origins.isBlank()) {
            configuration.setAllowedOriginPatterns(Arrays.asList(origins.split(",")));
        } else {
            // 默认值只服务于本地开发：前端 dev server 端口见 osr-web/vite.config.ts。
            // 生产由 Nginx 同源代理 /api，不构成跨域请求，走不到这里；若前后端分域部署，
            // 用 CORS_ALLOWED_ORIGINS 显式覆盖。
            // 注意 origin 里的默认端口会被浏览器省略，localhost:80 要写成 http://localhost。
            configuration.setAllowedOriginPatterns(List.of("http://localhost:3000", "http://localhost"));
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private List<String> getPermitAllUrls() {
        List<String> urls = permitAllUrlProperties.getUrls();
        if (urls == null || urls.isEmpty()) {
            return new ArrayList<>();
        }
        return urls;
    }
}
