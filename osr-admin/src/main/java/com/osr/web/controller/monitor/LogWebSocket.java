package com.osr.web.controller.monitor;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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
import com.osr.common.utils.Threads;
import com.osr.system.service.ISysMenuService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>查询参数（除 token 外都可省略，省略即旧协议，兼容尚未升级的前端）：
 * <ul>
 *   <li>{@code v=2}：按批推送 {@code {"t":"batch","lines":[…]}}，一轮轮询读到的所有行一帧发完。
 *       旧协议一行一帧，DEBUG 洪水时每秒几百帧，前端每帧都要触发一次整表重算。</li>
 *   <li>{@code levels=INFO,WARN,ERROR}：服务端按级别过滤。{@code sys-all.log} 里业务模块常态跑
 *       DEBUG、占大部分行数，只在前端过滤的话取消勾选 Debug 之后这些行照样全量传输。
 *       过滤同样作用于历史：只看 WARN/ERROR 时首屏那 500 行覆盖的时间窗口会宽得多。</li>
 * </ul>
 */
@ServerEndpoint("/websocket/log/{logType}")
@Component
public class LogWebSocket {

    private static final Logger log = LoggerFactory.getLogger(LogWebSocket.class);
    private static final String LOG_BASE_PATH = "/data/logs";
    private static final String REQUIRED_PERM = "monitor:log:view";

    /**
     * 首次连接回推的历史行数（过滤之后的行数）。
     * 合并成单一全量文件后同样的行数覆盖的时间窗口变窄了（原来 sys-info.log 里不含业务模块的
     * DEBUG），所以从 200 提到 500——打开页面第一眼能看到的上下文，比连上之后新滚出来的那几行有用得多。
     */
    private static final int HISTORY_LINES = 500;

    /** 带级别过滤时往回扫的原始行数上限：只看 ERROR 时 500 行可能散在几万行 DEBUG 里 */
    private static final int HISTORY_SCAN_LINES = 20000;

    /** 历史最多往回扫的字节数，挡住单行极长或没有换行的异常文件 */
    private static final long HISTORY_MAX_BYTES = 8L * 1024 * 1024;

    /** 跟随新行的轮询间隔，也是前端收到批次的最小间隔 */
    private static final long POLL_MILLIS = 300;

    private static final Set<String> KNOWN_LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR");

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

    private volatile LogFileFollower follower;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    @OnOpen
    public void onOpen(Session session, @PathParam("logType") String logType) {
        Map<String, String> query = parseQuery(session.getQueryString());

        // Token 校验
        String token = query.get("token");
        Long userId = null;
        boolean tokenValid = token != null && !token.isEmpty();
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

        Path file = Path.of(LOG_BASE_PATH, resolveFileName(logType));
        try {
            Sender sender = new Sender(session, "2".equals(query.get("v")));
            LevelFilter filter = LevelFilter.parse(query.get("levels"));

            if (!Files.isRegularFile(file)) {
                // 新部署时访问日志等文件要等第一条日志写入才会建出来。发完原因就关掉：
                // 原先留着一条什么都不推的连接，页面上显示「已连接」却一直空着
                sender.control("error", "日志文件还不存在：" + file + "（该类日志尚未产生）");
                closeQuietly(session);
                return;
            }

            // history 与 follower 共用一个 codec，让跨越两者边界的异常堆栈也能正确继承级别。
            // 两者不并发：follower 在 history 发完之后才启动。
            LineCodec codec = new LineCodec();

            // 先定上界再读历史，follower 从历史读到的精确边界接着读：中间写进来的行一条不漏
            long end = Files.size(file);
            LogFileFollower.Tail tail = LogFileFollower.readTail(file, end,
                    filter.acceptsAll() ? HISTORY_LINES : HISTORY_SCAN_LINES, HISTORY_MAX_BYTES);
            sendHistory(sender, tail.lines(), codec, filter);

            LogFileFollower f = new LogFileFollower(file, tail.resumePos(), POLL_MILLIS, new LogFileFollower.Sink() {
                @Override
                public void lines(List<String> lines) throws IOException {
                    List<JSONObject> out = new ArrayList<>(lines.size());
                    for (String l : lines) {
                        JSONObject o = codec.parse(l);
                        if (filter.accepts(o.getString("level"))) {
                            out.add(o);
                        }
                    }
                    sender.lines(out);
                }

                @Override
                public void rotated() throws IOException {
                    sender.control("rotated", null);
                }

                @Override
                public void failed(IOException e) {
                    log.warn("实时日志读取失败，file={}：{}", file, e.getMessage(), e);
                    try {
                        sender.control("error", "读取日志文件失败：" + e.getMessage());
                    } catch (IOException ignored) {
                        // 连接多半也已断开，原因已记在上面那条 WARN 里
                    }
                    closeQuietly(session);
                }
            });
            follower = f;
            Thread.ofVirtual().name("log-follow-" + session.getId()).start(Threads.wrap(f));
            // 连接可能在 follower 就位前就已关闭，此时 stopFollower 看不到它，这里补停
            if (closed.get()) {
                f.stop();
            }
        } catch (Exception e) {
            log.error("实时日志连接初始化失败，logType={}, file={}：{}", logType, file, e.getMessage(), e);
            try {
                new Sender(session, false).control("error", "初始化失败：" + e.getMessage());
            } catch (Exception ignored) {
                // 同上
            }
            closeQuietly(session);
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
            new Sender(session, false).control("unauthorized", null);
        } catch (Exception e) {
            log.debug("发送鉴权失败通知时出错：{}", e.getMessage());
        }
        closeQuietly(session);
        log.warn("WebSocket 连接被拒绝：{}, logType={}", reason, logType);
    }

    /**
     * 历史行倒序读出、在 {@link LogFileFollower#readTail} 里已经翻回正序，这里<b>正序</b>逐行编码：
     * 异常堆栈的续行要继承上一条的级别，倒序编码会让它继承到时间上更靠后的那条，级别全错。
     * 编码之后再按级别过滤、取最后 {@link #HISTORY_LINES} 行——先过滤会把续行和首行拆散。
     */
    private void sendHistory(Sender sender, List<String> raw, LineCodec codec, LevelFilter filter) throws IOException {
        List<JSONObject> out = new ArrayList<>();
        for (String l : raw) {
            JSONObject o = codec.parse(l);
            if (filter.accepts(o.getString("level"))) {
                out.add(o);
            }
        }
        if (out.size() > HISTORY_LINES) {
            out = out.subList(out.size() - HISTORY_LINES, out.size());
        }
        sender.lines(out);
        sender.control("history-end", null);
    }

    @OnClose
    public void onClose() {
        stopFollower();
    }

    @OnError
    public void onError(Session session, Throwable error) {
        // 浏览器直接关标签页、网络断开都会走到这里，属于常态，记 DEBUG 即可
        log.debug("实时日志连接异常，sessionId={}：{}", session.getId(), error.getMessage(), error);
        stopFollower();
    }

    private void stopFollower() {
        closed.set(true);
        LogFileFollower f = follower;
        if (f != null) {
            f.stop();
        }
    }

    private static void closeQuietly(Session session) {
        try {
            session.close();
        } catch (Exception e) {
            log.debug("关闭 WebSocket 连接时出错：{}", e.getMessage());
        }
    }

    static Map<String, String> parseQuery(String queryString) {
        Map<String, String> map = new HashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return map;
        }
        for (String param : queryString.split("&")) {
            int idx = param.indexOf('=');
            if (idx > 0) {
                // 前端用 URLSearchParams 拼参数，逗号会被编成 %2C，不解码的话 levels 一个级别都认不出来
                map.put(param.substring(0, idx), URLDecoder.decode(param.substring(idx + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    /** 往一个连接上发消息。只有 onOpen 线程（历史）与 follower 线程（新行）两个写者，且二者先后不重叠 */
    private static final class Sender {
        /** 单帧最多装多少行，与 follower 的单批上限一致 */
        private static final int MAX_LINES_PER_FRAME = LogFileFollower.MAX_BATCH;

        private final Session session;
        private final boolean batch;

        Sender(Session session, boolean batch) {
            this.session = session;
            this.batch = batch;
        }

        void lines(List<JSONObject> lines) throws IOException {
            if (lines.isEmpty()) {
                return;
            }
            if (!batch) {
                for (JSONObject o : lines) {
                    JSONObject m = new JSONObject();
                    m.put("t", "log");
                    m.putAll(o);
                    send(m.toJSONString());
                }
                return;
            }
            for (int i = 0; i < lines.size(); i += MAX_LINES_PER_FRAME) {
                JSONObject m = new JSONObject();
                m.put("t", "batch");
                m.put("lines", new JSONArray(lines.subList(i, Math.min(i + MAX_LINES_PER_FRAME, lines.size()))));
                send(m.toJSONString());
            }
        }

        void control(String type, String msg) throws IOException {
            JSONObject o = new JSONObject();
            o.put("t", type);
            if (msg != null) {
                o.put("msg", msg);
            }
            send(o.toJSONString());
        }

        private void send(String text) throws IOException {
            if (!session.isOpen()) {
                throw new IOException("连接已关闭");
            }
            try {
                session.getBasicRemote().sendText(text);
            } catch (IllegalStateException e) {
                // 与关闭并发时容器抛的是 IllegalStateException，统一成「对端不可写」
                throw new IOException(e.getMessage(), e);
            }
        }
    }

    /**
     * 服务端级别过滤。未列在复选框里的级别（TRACE 等）跟随 INFO，与前端的处理一致。
     * {@code levels} 参数缺省表示不过滤；给了但一个都不认识（前端全部取消勾选时传 {@code -}）表示全部过滤掉。
     */
    record LevelFilter(Set<String> levels) {

        static LevelFilter parse(String param) {
            if (param == null) {
                return new LevelFilter(null);
            }
            Set<String> set = new HashSet<>();
            for (String s : param.split(",")) {
                String level = s.trim().toUpperCase(Locale.ROOT);
                if (KNOWN_LEVELS.contains(level)) {
                    set.add(level);
                }
            }
            return new LevelFilter(set);
        }

        boolean acceptsAll() {
            return levels == null || levels.containsAll(KNOWN_LEVELS);
        }

        boolean accepts(String level) {
            if (levels == null) {
                return true;
            }
            return KNOWN_LEVELS.contains(level) ? levels.contains(level) : levels.contains("INFO");
        }
    }

    /**
     * 把一行文本日志解析成结构化字段。
     *
     * <p>持有「上一条解析成功的行的级别」，因此<b>每个连接一个实例</b>，不能做成静态工具方法。
     * 异常堆栈的 {@code at com.osr...} 那些续行匹配不上 pattern，必须继承首行的 ERROR：
     * 否则前端关掉 Error 过滤时堆栈还在刷屏，开着 Error 过滤时又只剩一句异常消息没有堆栈——
     * 而堆栈正是这个页面在故障时唯一有用的东西。服务端级别过滤也依赖这一点，续行才会跟着首行一起留下或丢掉。
     */
    static final class LineCodec {

        private String lastLevel = "INFO";

        /** 一行对应的字段（不含消息类型 {@code t}），批量协议里直接作为数组元素 */
        JSONObject parse(String line) {
            JSONObject o = new JSONObject();
            if (line == null) {
                o.put("level", lastLevel);
                o.put("msg", "");
                return o;
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
            return o;
        }

        /** 旧协议的一条消息：{@code {"t":"log", …字段}} */
        String encode(String line) {
            JSONObject o = new JSONObject();
            o.put("t", "log");
            o.putAll(parse(line));
            return o.toJSONString();
        }
    }
}
