package com.osr.framework.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.osr.common.core.domain.Result;
import com.osr.framework.config.properties.PermitAllUrlProperties;
import com.osr.framework.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
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
