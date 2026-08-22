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
@Order(RequestLogFilter.ORDER)
public class RequestLogFilter implements Filter {

    /**
     * 排在 Spring Security 之前。
     *
     * <p>原先是 {@code @Order(1)}，而 Spring Boot 给 Security 过滤器链的默认 order 是
     * <b>-100</b>——也就是说这个 filter 一直排在 Security <b>后面</b>，被 Security 拦下的请求
     * 根本走不到这里。实测发两个无 token 的请求（受保护接口与不存在的路径，均返回 401），
     * 日志里<b>一行都没有</b>：整套请求日志对认证失败完全失明，而「点什么都没反应」「接口报 401」
     * 恰恰是最常需要回溯的一类问题。
     *
     * <p>-101 是紧挨着 Security 之前的位置，不去抢 XssFilter（HIGHEST_PRECEDENCE）的次序。
     * 顺带的好处：traceId 现在也在 Security 之前就绪，JwtAuthenticationFilter 里的日志
     * 从此也带得上 traceId。
     */
    static final int ORDER = -101;

    /** 本类自身的问题（记日志时出错）走这个 logger，与访问日志分开 */
    private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

    /**
     * 访问日志专用 logger，独立 appender 写 sys-access.log（logback 里 additivity=false）。
     *
     * <p>访问日志与业务日志是两类东西：前者是运维流水、按请求产生，后者讲的是系统在做什么。
     * 混在 sys-all.log 里的结果是后者被前者淹没——这正是 sys-user.log 早就独立出去的理由。
     * 实时日志页因此多一个「访问日志」源，两边都看得到。
     */
    private static final Logger accessLog = LoggerFactory.getLogger("access");

    /**
     * 只跳过<日志>、不改变请求处理的路径。
     *
     * <p>与下面的 {@link #EXCLUDE_PATHS} 是两回事：那个清单命中后会走「非 GET 一律 405」的
     * 静态资源分支，把一个真实 API 端点放进去会连带改掉它的请求语义。
     *
     * <p>/api/health 是 docker-compose healthcheck 的探针，每 15 秒一次。排除前它与
     * ApiInterceptor（已删除）合计每天留下 23040 行，在一份 1085 行的样本里独占 60.5%。
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

            // 参数与 body 只在 DEBUG 打：它们对排查个别请求有用，但量大且含业务内容，
            // 不该进入按请求恒定产生的访问日志。
            if (!skipLog && accessLog.isDebugEnabled()) {
                logRequestInfo(wrappedRequest);
            }

            // 继续调用业务链
            chain.doFilter(wrappedRequest, response);

        } finally {
            // 一条请求一行，收尾时打。放在 finally 里，异常路径同样留痕——这是 filter 相对
            // 拦截器的天然优势：不需要区分 postHandle / afterCompletion，也没有「哪个回调在
            // 异常时不被调用」的坑。
            if (!skipLog) {
                logAccess(httpRequest, response, startTime);
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

    /**
     * 一次请求 = 一行访问日志。
     *
     * <p>合并前这件事由两个组件各打两行：本类的 {@code Request =>} / {@code Response <=}
     * 与 ApiInterceptor 的两条 {@code [API]}，四行里方法、URI、耗时是重复的，只有
     * 「参数·body」与「IP·UA·状态码」各自独有。ApiInterceptor 已随本次改造删除，
     * 它独有的那三个字段并入这里。
     *
     * <p>{@code startTime} 是 {@code doFilter} 的<b>方法内局部变量</b>，天生按请求隔离。
     * 被删掉的 ApiInterceptor 曾把它存成拦截器的实例字段，而 HandlerInterceptor 是单例，
     * 并发时后到的请求会覆盖先到者的起点，算出来的耗时<b>偏小</b>——慢接口反而显示得很快，
     * 正好瞒过要靠这条日志找的那类问题（实测 56.7 秒被记成 15.2 秒）。
     * 不要为了「复用」把它挪到字段上。
     */
    private void logAccess(HttpServletRequest request, ServletResponse response, long startTime) {
        long elapsedTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
        int status = response instanceof HttpServletResponse httpResponse ? httpResponse.getStatus() : 0;
        accessLog.info("{} {} -> {} ({}ms) from {} UA: {}",
                request.getMethod(),
                request.getRequestURI(),
                status,
                elapsedTime,
                getClientIp(request),
                request.getHeader("User-Agent"));
    }

    /**
     * 取真实客户端 IP。本项目前端由 Nginx 反代，直接用 getRemoteAddr() 拿到的是容器网关地址，
     * 所有请求看起来都来自同一个 IP。
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For 是逗号分隔的链，第一个才是最初的客户端
        if (ip != null) {
            int comma = ip.indexOf(',');
            if (comma > 0) {
                ip = ip.substring(0, comma).trim();
            }
        }
        return ip;
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
