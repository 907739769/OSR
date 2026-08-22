package com.osr.framework.filter;

import com.osr.common.utils.ThreadTraceIdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Order(1)
public class RequestLogFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    /**
     * 只跳过<日志>、不改变请求处理的路径。
     *
     * <p>与下面的 {@link #EXCLUDE_PATHS} 是两回事：那个清单命中后会走「非 GET 一律 405」的
     * 静态资源分支，把一个真实 API 端点放进去会连带改掉它的请求语义。
     *
     * <p>/api/health 是 docker-compose healthcheck 的探针，每 15 秒一次，本类留两行、
     * ApiInterceptor 再留两行，合计每天 23040 行。实测它在一份 1085 行的样本里独占 60.5%。
     */
    private static final List<String> NO_LOG_PATHS = Arrays.asList("/api/health");

    private static final List<String> EXCLUDE_PARAMS = Arrays.asList("password");
    private static final int MAX_PARAM_LENGTH = 200;
    private static final int MAX_BODY_LENGTH = 1000; // 限制body最大打印长度
    // 静态资源路径排除列表
    private static final List<String> EXCLUDE_PATHS = Arrays.asList(
            "/static/", "/ajax/libs/", "/fonts/", "/css/", "/js/", "/file/", "/html/", "/i18n/", "/img/",
            "/osr/", "/images/", ".css", ".js", ".png", ".jpg", ".woff2", ".html", "/swagger-resources", "/webjars", ".ico",
            "/v2/api-docs", "/v3/api-docs"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // 过滤静态资源请求
        if (isStaticResource(httpRequest.getRequestURI())) {
            if (!"GET".equalsIgnoreCase(httpRequest.getMethod())) {
                ((HttpServletResponse) response).sendError(405);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        long startTime = System.nanoTime();

        // 只关掉日志，不提前 return：traceId 的初始化与 MDC.clear()、请求体缓存都在下面的
        // try/finally 里，跳过整段会让这些请求丢掉 traceId，行为与其它请求不一致。
        boolean skipLog = isNoLogPath(httpRequest.getRequestURI());

        // 这里的request已经是CachedBodyHttpServletRequest了，可以直接强转
        ContentCachingRequestWrapper wrappedRequest;
        if (httpRequest instanceof ContentCachingRequestWrapper) {
            wrappedRequest = (ContentCachingRequestWrapper) httpRequest;
        } else {
            wrappedRequest = new ContentCachingRequestWrapper(httpRequest, 8192);
        }

        try {
            // 初始化traceId
            initTraceId(httpRequest);

            // 先打印请求日志（此时请求体已经被包装缓存，可以读取）
            if (!skipLog) {
                logRequestInfo(wrappedRequest);
            }

            // 继续调用业务链
            chain.doFilter(wrappedRequest, response);

        } finally {
            // 记录耗时
            if (!skipLog) {
                logElapsedTime(httpRequest, startTime);
            }
            MDC.clear();
        }
    }

    private boolean isNoLogPath(String uri) {
        return NO_LOG_PATHS.contains(uri);
    }

    private void initTraceId(HttpServletRequest request) {
        String traceId = Optional.ofNullable(request.getHeader("X-Trace-ID"))
                .orElseGet(ThreadTraceIdUtil::generateTraceId);
        MDC.put("traceId", traceId);
    }

    private void logRequestInfo(ContentCachingRequestWrapper request) {
        try {
            Map<String, String> safeParams = getSafeParameters(request);
            String requestBody = getRequestBody(request);
            StringBuilder logMessage = new StringBuilder();
            logMessage.append("Request => ").append(request.getMethod()).append(" ").append(getRequestUrl(request));

            if (!safeParams.isEmpty()) {
                logMessage.append(" [Params: ").append(formatParameters(safeParams)).append("]");
            }

            if (requestBody != null && !requestBody.isEmpty()) {
                logMessage.append(" [Body: ").append(requestBody).append("]");
            }

            log.debug(logMessage.toString());

        } catch (Exception e) {
            log.warn("记录请求日志出错", e);
        }
    }

    private String getRequestBody(ContentCachingRequestWrapper request) {
        // 只处理POST/PUT/PATCH等可能有body的请求
        if (!Arrays.asList("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())) {
            return null;
        }

        // 如果是文件上传请求，不记录body
        if (isFileUpload(request)) {
            return "[FILE_UPLOAD]";
        }

        // 检查是否是JSON请求
        String contentType = request.getContentType();
        if (contentType == null || !contentType.contains("application/json")) {
            return null;
        }

        // 获取缓存的内容
        byte[] buf = request.getContentAsByteArray();
        if (buf == null || buf.length == 0) {
            return null;
        }

        // 转换为字符串并截断
        String body = null;
        try {
            body = new String(buf, 0, Math.min(buf.length, MAX_BODY_LENGTH), request.getCharacterEncoding());
        } catch (UnsupportedEncodingException e) {
            log.warn("读取请求体失败，编码不支持: {} {}", request.getMethod(), request.getRequestURI(), e);
        }
        if (buf.length > MAX_BODY_LENGTH) {
            body += "...[truncated]";
        }

        return body;
    }

    private String getRequestUrl(HttpServletRequest request) {
        String queryString = request.getQueryString();
        return request.getRequestURI() + (queryString != null ? "?" + queryString : "");
    }

    private Map<String, String> getSafeParameters(HttpServletRequest request) {
        Map<String, String> safeParams = new LinkedHashMap<>();

        // 获取URL参数
        request.getParameterMap().forEach((key, values) -> {
            if (!shouldExcludeParam(key) && values != null && values.length > 0) {
                String value = values[0];
                safeParams.put(key, truncateParam(value));
            } else if (shouldExcludeParam(key) && values != null && values.length > 0) {
                safeParams.put(key, "***");
            }
        });

        // 特殊处理文件上传请求
        if (isFileUpload(request)) {
            safeParams.put("_file_upload", getFileUploadDescription(request)); // 标记为文件请求
        }

        return safeParams;
    }

    private boolean shouldExcludeParam(String paramName) {
        return EXCLUDE_PARAMS.stream()
                .anyMatch(exclude -> paramName.toLowerCase().contains(exclude));
    }

    private String truncateParam(String value) {
        if (value.length() > MAX_PARAM_LENGTH) {
            return value.substring(0, MAX_PARAM_LENGTH) + "...[truncated]";
        }
        return value;
    }

    private String formatParameters(Map<String, String> params) {
        if (params.isEmpty()) {
            return "None";
        }
        return params.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private void logElapsedTime(HttpServletRequest request, long startTime) {
        long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        log.debug("Response <= {} {} [Time: {}ms]",
                request.getMethod(),
                request.getRequestURI(),
                elapsedTime);
    }

    private boolean isStaticResource(String path) {
        return EXCLUDE_PATHS.stream()
                .anyMatch(exclude -> path.contains(exclude) || path.endsWith(exclude));
    }

    private boolean isFileUpload(HttpServletRequest request) {
        return request.getContentType() != null
                && request.getContentType().startsWith("multipart/form-data");
    }

    private String getFileUploadDescription(HttpServletRequest request) {
        try {
            if (request instanceof MultipartHttpServletRequest) {
                MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest) request;
                return multipartRequest.getFileMap().keySet().toString();
            }
        } catch (Exception e) {
            // ignore
        }
        return "[FILE_UPLOAD]";
    }
}
