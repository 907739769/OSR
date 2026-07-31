package com.osr.web.controller.monitor;

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
 * 实时日志监控 WebSocket
 * 优化：增加移动端适配，智能隐藏元数据
 */
@ServerEndpoint("/websocket/log/{logType}")
@Component
public class LogWebSocket {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocket.class);
    private static final String LOG_BASE_PATH = "/data/logs";
    private static final String REQUIRED_PERM = "monitor:log:view";
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

    // 正则表达式：匹配若依默认日志格式
    // 格式: [日期 时间][TraceId][级别][Logger] 消息
    // Group 1: 时间 [2025-01-01 10:00:00.123]
    // Group 2: 中间元数据 [trace][INFO ][logger]
    // Group 3: 剩余消息内容
    private static final Pattern LOG_PATTERN = Pattern.compile("^(\\[\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\])(\\[.*?\\]\\[.*?\\]\\[.*?\\])(.*)");

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
            try {
                session.getBasicRemote().sendText("unauthorized");
                session.close();
            } catch (Exception e) {
                log.debug("关闭 WebSocket 连接时出错", e);
            }
            log.warn("WebSocket 连接被拒绝：token 无效或已过期, logType={}", logType);
            return;
        }

        // 权限校验：非管理员必须拥有 monitor:log:view 权限
        if (!SysUser.isAdmin(userId) && !menuService.selectPermsByUserId(userId).contains(REQUIRED_PERM)) {
            try {
                session.getBasicRemote().sendText("unauthorized");
                session.close();
            } catch (Exception e) {
                log.debug("关闭 WebSocket 连接时出错", e);
            }
            log.warn("WebSocket 连接被拒绝：用户 {} 缺少权限 {}, logType={}", userId, REQUIRED_PERM, logType);
            return;
        }

        try {
            String fileName = "sys-info.log";
            if ("debug".equalsIgnoreCase(logType)) fileName = "sys-debug.log";
            else if ("error".equalsIgnoreCase(logType)) fileName = "sys-error.log";

            File file = new File(LOG_BASE_PATH, fileName);
            if (!file.exists()) {
                session.getBasicRemote().sendText("<div class='log-item log-error'>文件不存在: " + file.getAbsolutePath() + "</div>");
                return;
            }

            // 发送历史日志
            sendHistoryLogs(session, file, 200);

            // 监听新日志
            executorService = Executors.newVirtualThreadPerTaskExecutor();
            executorService.submit(() -> {
                Tailer t = new Tailer(file, new TailerListenerAdapter() {
                    @Override
                    public void handle(String line) {
                        try {
                            if (session.isOpen()) {
                                session.getBasicRemote().sendText(formatLogLine(line));
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

    private void sendHistoryLogs(Session session, File file, int linesToRead) {
        try (ReversedLinesFileReader reader = new ReversedLinesFileReader(file, StandardCharsets.UTF_8)) {
            List<String> messages = new ArrayList<>();
            String line;
            int counter = 0;
            while ((line = reader.readLine()) != null && counter < linesToRead) {
                messages.add(formatLogLine(line));
                counter++;
            }
            Collections.reverse(messages);
            messages.add("<div class='log-item' style='color: #888; border-bottom: 1px dashed #555; margin: 10px 0;'>--- 历史日志结束 (History End) ---</div>");
            for (String msg : messages) {
                session.getBasicRemote().sendText(msg);
            }
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
     * 核心格式化方法：
     * 1. HTML转义
     * 2. 颜色高亮
     * 3. 结构化标记 (用于移动端隐藏)
     */
    private String formatLogLine(String line) {
        if (line == null) return "";

        // 1. 转义 HTML (防止日志里的 <script> 被执行)
        String content = line.replace("<", "&lt;").replace(">", "&gt;");

        // 2. 识别日志级别颜色
        String cssClass = "log-info";
        if (content.contains("ERROR")) cssClass = "log-error";
        else if (content.contains("WARN")) cssClass = "log-warn";
        else if (content.contains("DEBUG")) cssClass = "log-debug";

        // 3. 移动端适配处理：正则提取 [Trace][Level][Logger] 并包裹 hidden-xs 类
        Matcher matcher = LOG_PATTERN.matcher(content);
        if (matcher.find()) {
            // Group 1: 时间
            // Group 2: 元数据 -> 添加 class='hidden-xs' (Bootstrap类，手机端隐藏)
            // Group 3: 消息
            content = matcher.replaceFirst("$1<span class='hidden-xs'>$2</span>$3");
        }

        return "<div class='log-item " + cssClass + "'>" + content + "</div>";
    }
}