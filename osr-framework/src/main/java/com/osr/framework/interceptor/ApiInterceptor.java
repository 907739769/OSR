package com.osr.framework.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ApiInterceptor.class);

    private static final String[] SKIP_PATHS = {
        "/css/", "/js/", "/img/", "/fonts/", "/favicon.ico",
        "/service-worker.js", "/manifest.json", "/apple-touch-icon.png"
    };

    private long startTime;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        if (isSkipPath(uri)) {
            return true;
        }
        startTime = System.currentTimeMillis();
        String method = request.getMethod();
        String ip = getClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        log.info("[API] {} {} from {} UA: {}", method, uri, ip, userAgent);
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        String uri = request.getRequestURI();
        if (isSkipPath(uri)) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[API] {} {} -> {} ({}ms)", request.getMethod(), uri, response.getStatus(), elapsed);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        String uri = request.getRequestURI();
        if (isSkipPath(uri)) {
            return;
        }
        if (ex != null) {
            log.error("[API] {} {} error: {}", request.getMethod(), uri, ex.getMessage());
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
