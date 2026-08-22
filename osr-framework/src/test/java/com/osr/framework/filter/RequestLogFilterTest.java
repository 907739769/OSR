package com.osr.framework.filter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RequestLogFilter} 的访问日志。
 *
 * <p>本类接手了已删除的 ApiInterceptorTest 所保护的两条知识。那两条坑在 filter 里换了形式：
 * 耗时用的是 {@code doFilter} 的方法内局部变量（拦截器那边是单例的实例字段），收尾在
 * {@code finally}（拦截器那边要在 postHandle / afterCompletion 之间做对选择）。
 * 换句话说 filter 天然不会犯那两个错——但「天然」只在没人把它改回去的前提下成立，
 * 所以断言留着。
 */
class RequestLogFilterTest {

    private static final Pattern ELAPSED = Pattern.compile("[(]([0-9]+)ms[)]");

    private RequestLogFilter filter;
    private Logger accessLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        filter = new RequestLogFilter();
        accessLogger = (Logger) LoggerFactory.getLogger("access");
        appender = new ListAppender<>();
        appender.start();
        accessLogger.addAppender(appender);
        accessLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        accessLogger.detachAppender(appender);
        appender.stop();
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        request.setRemoteAddr("10.0.0.7");
        request.addHeader("User-Agent", "JUnit");
        return request;
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    private String messageFor(String uri) {
        return events().stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(uri))
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有 " + uri + " 的访问日志: " + events()));
    }

    private long elapsedOf(String uri) {
        Matcher m = ELAPSED.matcher(messageFor(uri));
        assertTrue(m.find(), "访问日志里应带耗时: " + messageFor(uri));
        return Long.parseLong(m.group(1));
    }

    @Test
    @DisplayName("一次请求只留一行，方法/URI/状态码/耗时/IP/UA 都在里面")
    void oneRequestOneLine() throws Exception {
        MockHttpServletRequest request = request("/api/things");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(201);

        filter.doFilter(request, response, new MockFilterChain());

        // 合并前这件事由两个组件各打两行（本类两行 + ApiInterceptor 两行），
        // 其中方法、URI、耗时是逐字重复的
        assertEquals(1, events().size(), "一次请求应当只产生一条访问日志: " + events());
        String msg = messageFor("/api/things");
        assertTrue(msg.contains("GET"), msg);
        assertTrue(msg.contains("201"), "状态码要在（原先只有 ApiInterceptor 记它）: " + msg);
        assertTrue(msg.contains("10.0.0.7"), "客户端 IP 要在: " + msg);
        assertTrue(msg.contains("JUnit"), "UA 要在: " + msg);
    }

    @Test
    @DisplayName("健康检查探针不产生访问日志")
    void healthProbeIsSilent() throws Exception {
        // docker healthcheck 每 15 秒一次；排除前它一家占掉全部日志的 60.5%
        filter.doFilter(request("/api/health"), new MockHttpServletResponse(), new MockFilterChain());

        assertTrue(events().isEmpty(), "健康检查不该留下访问日志: " + events());
    }

    @Test
    @DisplayName("下游抛异常时照样记录——收尾在 finally 里")
    void logsEvenWhenChainThrows() {
        MockHttpServletRequest request = request("/api/boom");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("炸了");
        };

        assertThrows(IllegalStateException.class, () -> filter.doFilter(request, response, boom));

        // 出错的请求恰恰是最需要知道它跑了多久的那种。被删掉的 ApiInterceptor 为此
        // 专门把收尾从 postHandle 挪到 afterCompletion（前者在异常路径上根本不被调用）；
        // filter 的 finally 天然覆盖这条路径，别改成写在 chain.doFilter 之后。
        assertTrue(elapsedOf("/api/boom") >= 0, "异常请求也应留下访问日志");
    }

    @Test
    @DisplayName("并发请求各算各的耗时，起点不会被后来者覆盖")
    void concurrentRequestsDoNotShareStartTime() throws Exception {
        // 这是被删掉的 ApiInterceptor 上真实发生过的 bug：它把请求开始时间存成实例字段，
        // 而 HandlerInterceptor 是单例，后到的请求会把先到者的起点覆盖掉，算出来的耗时
        // <b>偏小</b>——一次真实耗时 56.7 秒的搜索被记成 15.2 秒，慢接口反而显示得很快。
        // filter 用的是 doFilter 的方法内局部变量，天生按请求隔离；这条断言防的是有人
        // 为了「复用」把它挪回字段上。
        FilterChain slow = (req, res) -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        Thread a = new Thread(() -> {
            try {
                filter.doFilter(request("/api/slow"), new MockHttpServletResponse(), slow);
            } catch (IOException | ServletException e) {
                throw new IllegalStateException(e);
            }
        });
        a.start();
        // 让 A 先进入，B 随后开始——若起点是共享字段，B 会把 A 的起点冲掉
        Thread.sleep(20);
        filter.doFilter(request("/api/fast"), new MockHttpServletResponse(), new MockFilterChain());
        a.join();

        long slowElapsed = elapsedOf("/api/slow");
        assertTrue(slowElapsed >= 50,
                "慢请求的耗时应覆盖它自己的完整时长（>=50ms），实际 " + slowElapsed + "ms —— 起点被另一个请求覆盖了");
    }
}
