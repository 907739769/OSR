package com.osr.web.controller.monitor;

import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.io.input.ReversedLinesFileReader;
import org.apache.commons.io.input.Tailer;
import org.apache.commons.io.input.TailerListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.utils.JwtTokenUtil;
import com.osr.system.service.ISysMenuService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实时日志监控 WebSocket。
 *
 * <p>推给前端的是<b>结构化 JSON</b>，不是渲染好的 HTML。早先这里拼的是
 * {@code <div class='log-item log-info'>…</div>}，而前端拿到后又用 DOMParser 把标签剥掉、
 * 只取 textContent，再自己重新判级别重新上色——那一层 HTML 是纯浪费（每行一次 DOM 解析），
 * 而且它曾经是个实打实的 XSS 面：日志里含有来自网盘的文件名等非可信数据。
 *
 * <p>更要紧的是级别判定。两边原先都用 {@code line.contains("ERROR")} 猜级别，
 * 于是消息正文里出现 "ERROR" 字样的 INFO 行（打印索引器响应体、异常消息文本时很常见）
 * 会被染成红色、并被前端的 Error 过滤框筛出来。级别在日志行里本来就是一个有确定位置的字段，
 * 在这里解析一次、以 {@code level} 字段推给前端，前端按字段精确过滤即可。
 */
@ServerEndpoint("/websocket/log/{logType}")
@Component
public class LogWebSocket {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocket.class);
    private static final String LOG_BASE_PATH = "/data/logs";
    private static final String REQUIRED_PERM = "monitor:log:view";

    /**
     * 首次连接回推的历史行数。
     * 合并成单一全量文件后同样的行数覆盖的时间窗口变窄了（原来 sys-info.log 里不含业务模块的
     * DEBUG），所以从 200 提到 500——打开页面第一眼能看到的上下文，比连上之后新滚出来的那几行有用得多。
     */
    private static final int HISTORY_LINES = 500;

    private static JwtTokenUtil jwtTokenUtil;
    private static ISysMenuService menuService;

    @Autowired
    private JwtTokenUtil tokenUtil;

    @Autowired
    private ISysMenuService sysMenuService;

    @PostConstruct
    void init() {
        jwtTokenUtil = tokenUtil;
        menuService = sysMenuService;
    }

    static {
        log.info(">>> LogWebSocket class loaded, @ServerEndpoint=/websocket/log/{logType}");
    }

    /**
     * 匹配 logback.xml 里的 pattern：
     * {@code [%d{yyyy-MM-dd HH:mm:ss.SSS}][%X{traceId}][%-5level][%logger{0}] %msg}
     * <p>
     * Group 1 时间、2 traceId（可能为空）、3 级别（%-5level 左对齐补空格，需 trim）、4 logger、5 消息。
     * 匹配不上的行是异常堆栈的续行，交由 {@link LineCodec} 继承上一条的级别。
     */
    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^\\[(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\]"
                    + "\\[([^\\]]*)\\]\\[([A-Z ]{1,5})\\]\\[([^\\]]*)\\] ?(.*)$");

    private volatile ExecutorService executorService;
    private volatile Tailer tailer;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @OnOpen
    public void onOpen(Session session, @PathParam("logType") String logType) {
        // Token 校验
        String queryString = session.getQueryString();
        String token = extractToken(queryString);
        Long userId = null;
        boolean tokenValid = token != null;
        if (tokenValid) {
            try {
                tokenValid = !jwtTokenUtil.isTokenExpired(token);
                if (tokenValid) {
                    userId = jwtTokenUtil.getUserIdFromToken(token);
                }
            } catch (Exception e) {
                tokenValid = false;
            }
        }
        if (!tokenValid) {
            rejectUnauthorized(session, "token 无效或已过期", logType);
            return;
        }

        // 权限校验：非管理员必须拥有 monitor:log:view 权限
        if (!SysUser.isAdmin(userId) && !menuService.selectPermsByUserId(userId).contains(REQUIRED_PERM)) {
            rejectUnauthorized(session, "用户 " + userId + " 缺少权限 " + REQUIRED_PERM, logType);
            return;
        }

        try {
            File file = new File(LOG_BASE_PATH, resolveFileName(logType));
            if (!file.exists()) {
                sendControl(session, "error", "文件不存在: " + file.getAbsolutePath());
                return;
            }

            // history 与 tailer 共用一个 codec，让跨越两者边界的异常堆栈也能正确继承级别。
            // 两者不并发：executorService.submit 建立 happens-before，history 在此之前已发完。
            LineCodec codec = new LineCodec();

            // 发送历史日志
            sendHistoryLogs(session, file, codec);

            // 监听新日志
            executorService = Executors.newVirtualThreadPerTaskExecutor();
            executorService.submit(() -> {
                Tailer t = new Tailer(file, new TailerListenerAdapter() {
                    @Override
                    public void handle(String line) {
                        try {
                            if (session.isOpen()) {
                                session.getBasicRemote().sendText(codec.encode(line));
                            }
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                }, 500, true);
                tailer = t;
                // 连接可能在 Tailer 创建完成前就已被关闭，此时需立即停止，避免线程泄漏
                if (closed.get()) {
                    t.stop();
                    return;
                }
                t.run();
            });

        } catch (Exception e) {
            log.error("WebSocket启动失败", e);
        }
    }

    /**
     * logType 到日志文件的映射。
     *
     * <p>三档：「全量」「仅错误」「访问日志」，对应 logback 里的三个文件。访问日志是
     * 独立 logger（additivity=false）写的，不进 sys-all.log，所以必须单开一档——否则
     * 它从页面上彻底消失，那是功能退化而不是降噪。
     *
     * <p>旧的 {@code info} / {@code debug} 两个取值不再有对应文件，一并落到全量——
     * 它们本来就是同一份日志的两半，客户端还在用旧值时给全量是唯一说得通的降级。
     */
    private String resolveFileName(String logType) {
        if ("error".equalsIgnoreCase(logType)) {
            return "sys-error.log";
        }
        if ("access".equalsIgnoreCase(logType)) {
            return "sys-access.log";
        }
        return "sys-all.log";
    }

    private void rejectUnauthorized(Session session, String reason, String logType) {
        try {
            sendControl(session, "unauthorized", null);
            session.close();
        } catch (Exception e) {
            log.debug("关闭 WebSocket 连接时出错", e);
        }
        log.warn("WebSocket 连接被拒绝：{}, logType={}", reason, logType);
    }

    private void sendControl(Session session, String type, String msg) throws IOException {
        JSONObject o = new JSONObject();
        o.put("t", type);
        if (msg != null) {
            o.put("msg", msg);
        }
        session.getBasicRemote().sendText(o.toJSONString());
    }

    private void sendHistoryLogs(Session session, File file, LineCodec codec) {
        try (ReversedLinesFileReader reader = new ReversedLinesFileReader(file, StandardCharsets.UTF_8)) {
            // 倒着读取原始行，reverse 之后再<正序>逐行编码：异常堆栈的续行要继承上一条的级别，
            // 倒序编码会让它继承到时间上更靠后的那条，级别全错。
            List<String> raw = new ArrayList<>();
            String line;
            while (raw.size() < HISTORY_LINES && (line = reader.readLine()) != null) {
                raw.add(line);
            }
            Collections.reverse(raw);

            for (String l : raw) {
                session.getBasicRemote().sendText(codec.encode(l));
            }
            sendControl(session, "history-end", null);
        } catch (IOException e) {
            log.error("读取历史日志失败", e);
        }
    }

    @OnClose
    public void onClose() { stopTailer(); }

    @OnError
    public void onError(Session session, Throwable error) { stopTailer(); }

    private void stopTailer() {
        closed.set(true);
        if (tailer != null) tailer.stop();
        if (executorService != null) executorService.shutdownNow();
    }

    private String extractToken(String queryString) {
        if (queryString == null || queryString.isEmpty()) return null;
        for (String param : queryString.split("&")) {
            int idx = param.indexOf('=');
            if (idx > 0 && "token".equals(param.substring(0, idx))) {
                return param.substring(idx + 1);
            }
        }
        return null;
    }

    /**
     * 把一行文本日志编成一条 JSON 消息。
     *
     * <p>持有「上一条解析成功的行的级别」，因此<b>每个连接一个实例</b>，不能做成静态工具方法。
     * 异常堆栈的 {@code at com.osr...} 那些续行匹配不上 pattern，必须继承首行的 ERROR：
     * 否则前端关掉 Error 过滤时堆栈还在刷屏，开着 Error 过滤时又只剩一句异常消息没有堆栈——
     * 而堆栈正是这个页面在故障时唯一有用的东西。
     */
    static final class LineCodec {

        private String lastLevel = "INFO";

        String encode(String line) {
            JSONObject o = new JSONObject();
            o.put("t", "log");
            if (line == null) {
                o.put("level", lastLevel);
                o.put("msg", "");
                return o.toJSONString();
            }

            Matcher m = LOG_PATTERN.matcher(line);
            if (m.matches()) {
                String level = m.group(3).trim();
                lastLevel = level;
                o.put("ts", m.group(1));
                o.put("trace", m.group(2));
                o.put("level", level);
                o.put("logger", m.group(4));
                o.put("msg", m.group(5));
            } else {
                // 续行（异常堆栈、多行消息）：继承上一条的级别，前端按 cont 标记缩进显示
                o.put("level", lastLevel);
                o.put("msg", line);
                o.put("cont", true);
            }
            return o.toJSONString();
        }
    }
}
