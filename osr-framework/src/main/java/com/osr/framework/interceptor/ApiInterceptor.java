package com.osr.framework.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.TimeUnit;

@Component
public class ApiInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ApiInterceptor.class);

    private static final String[] SKIP_PATHS = {
        "/css/", "/js/", "/img/", "/fonts/", "/favicon.ico",
        "/service-worker.js", "/manifest.json", "/apple-touch-icon.png"
    };

    /**
     * 请求开始时间（纳秒）挂在 request 上的 key。
     * <p>
     * <b>绝不能改回拦截器的实例字段</b>：{@code HandlerInterceptor} 是单例，所有并发请求
     * 共用同一个实例。用字段存开始时间时，后到的请求会在 {@code preHandle} 里把它覆盖掉，
     * 于是先到的那个请求算出来的耗时是「现在 - 最后一个请求的开始时间」，与它自己毫无关系。
     * 实测一次真实耗时 56.7 秒的搜索请求被记成 15.2 秒，差出来的那 41 秒正是另一个请求的到达时刻。
     * 症状很隐蔽：单请求下完全正确，只有并发时才偏，而且<b>偏小</b>——慢接口反而显示得很快，
     * 正好瞒过了要靠这条日志找的那类问题。request attribute 天然按请求隔离。
     * </p>
     */
    private static final String ATTR_START_NANOS = ApiInterceptor.class.getName() + ".startNanos";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        if (isSkipPath(uri)) {
            return true;
        }
        // nanoTime 而非 currentTimeMillis：单调时钟，不受 NTP 校时影响，与 RequestLogFilter 一致
        request.setAttribute(ATTR_START_NANOS, System.nanoTime());
        String method = request.getMethod();
        String ip = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        log.info("[API] {} {} from {} UA: {}", method, uri, ip, userAgent);
        return true;
    }

    /**
     * 收尾日志放在 {@code afterCompletion} 而不是 {@code postHandle}：后者在 handler 抛异常时
     * 压根不会被调用，出错的请求因此一条耗时都不会留下——而那正是最需要知道它跑了多久的情况。
     * 它也早于视图渲染，测不到请求的完整时长。
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        String uri = request.getRequestURI();
        if (isSkipPath(uri)) {
            return;
        }
        if (ex != null) {
            log.error("[API] {} {} error: {}", request.getMethod(), uri, ex.getMessage());
        }
        // attribute 缺失说明没走到 preHandle（被前置拦截器拦下等），没有可信起点就不编一个耗时出来
        if (request.getAttribute(ATTR_START_NANOS) instanceof Long startNanos) {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            log.info("[API] {} {} -> {} ({}ms)", request.getMethod(), uri, response.getStatus(), elapsed);
        }
    }

    private boolean isSkipPath(String uri) {
        for (String skipPath : SKIP_PATHS) {
            if (uri.startsWith(skipPath) || uri.equals(skipPath)) {
                return true;
            }
        }
        return false;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
