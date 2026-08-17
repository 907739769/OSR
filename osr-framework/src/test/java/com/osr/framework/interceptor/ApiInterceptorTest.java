package com.osr.framework.interceptor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ApiInterceptor} 的耗时统计。
 *
 * @author Jack
 */
class ApiInterceptorTest {

    private static final Pattern ELAPSED = Pattern.compile("[(]([0-9]+)ms[)]");

    private ApiInterceptor interceptor;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        interceptor = new ApiInterceptor();
        logger = (Logger) LoggerFactory.getLogger(ApiInterceptor.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void 并发请求各自计时_后到的请求不污染先到的耗时() throws Exception {
        // 这条守着一个只在并发下才出现的 bug：HandlerInterceptor 是单例，开始时间一旦存进
        // 实例字段，B 的 preHandle 就会把 A 的起点覆盖掉，A 算出来的耗时变成「现在 - B 的起点」。
        // 单请求下完全正确，所以不并发就测不出来
        MockHttpServletRequest reqA = request("/api/slow");
        MockHttpServletResponse respA = new MockHttpServletResponse();
        interceptor.preHandle(reqA, respA, null);

        Thread.sleep(60);

        MockHttpServletRequest reqB = request("/api/fast");
        MockHttpServletResponse respB = new MockHttpServletResponse();
        interceptor.preHandle(reqB, respB, null);

        // A 后完成：它的耗时必须覆盖上面那段 sleep，而不是 B 刚开始到现在的那一丁点
        interceptor.afterCompletion(reqA, respA, null, null);

        long elapsedA = elapsedOf("/api/slow");
        assertTrue(elapsedA >= 50,
                "A 的耗时应覆盖它自己的完整时长（>=50ms），实际 " + elapsedA + "ms —— 起点被 B 覆盖了");
    }

    @Test
    void handler抛异常时仍记录耗时() throws Exception {
        // postHandle 在异常路径上根本不会被调用，收尾日志必须挂在 afterCompletion 上，
        // 否则出错的请求一条耗时都不会留下——而那正是最需要知道它跑了多久的情况
        MockHttpServletRequest request = request("/api/boom");
        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, null);

        interceptor.afterCompletion(request, response, null, new IllegalStateException("炸了"));

        assertTrue(elapsedOf("/api/boom") >= 0, "异常请求也应留下耗时行");
        assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("炸了")),
                "异常本身仍要记一条 error");
    }

    @Test
    void 没走过preHandle的请求_不编造耗时() throws Exception {
        // 被前置拦截器拦下时没有可信起点，宁可不记也不能记一个从 0 开始算的假数字
        MockHttpServletRequest request = request("/api/blocked");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.afterCompletion(request, response, null, null);

        assertEquals(0, appender.list.stream()
                .filter(e -> ELAPSED.matcher(e.getFormattedMessage()).find())
                .count());
    }

    @Test
    void 静态资源路径_不记录任何日志() throws Exception {
        MockHttpServletRequest request = request("/favicon.ico");
        MockHttpServletResponse response = new MockHttpServletResponse();

        interceptor.preHandle(request, response, null);
        interceptor.afterCompletion(request, response, null, null);

        assertTrue(appender.list.isEmpty());
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    /** 取该 uri 那条收尾日志里的毫秒数 */
    private long elapsedOf(String uri) {
        List<ILoggingEvent> events = appender.list;
        for (ILoggingEvent event : events) {
            String message = event.getFormattedMessage();
            if (!message.contains(uri)) {
                continue;
            }
            Matcher matcher = ELAPSED.matcher(message);
            if (matcher.find()) {
                return Long.parseLong(matcher.group(1));
            }
        }
        throw new AssertionError("没有找到 " + uri + " 的耗时日志，实际日志：" + events);
    }
}
