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

    /**
     * 不记访问日志的路径。
     *
     * <p><b>/api/health 必须在这里</b>：它是 docker-compose 的 healthcheck 端点，wget 每 15 秒
     * 打一次，而一次请求会在这里留下两行（preHandle + afterCompletion）、在 RequestLogFilter
     * 再留下两行，合计 <b>4 行 × 5760 次 = 每天 23040 行</b>。实测在一份 1085 行的样本里，
     * 健康检查独占 60.5%——真正的业务日志被挤到 3%。它是给容器编排看的，不是给人看的。
     */
    private static final String[] SKIP_PATHS = {
        "/api/health",
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
            // 占位符照旧带上 ex.getMessage()，同时把 ex 本身作为<末参>传进去：SLF4J 在参数比
            // 占位符多一个且末参是 Throwable 时，会把它当异常处理而不是填进消息。
            // 两者都要——只有 message 时 sys-error.log 里没有堆栈（最需要的东西恰好没记），
            // 只有堆栈时又没法按错误文本 grep。ApiInterceptorTest 钉着前半条。
            log.error("[API] {} {} error: {}", request.getMethod(), uri, ex.getMessage(), ex);
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
