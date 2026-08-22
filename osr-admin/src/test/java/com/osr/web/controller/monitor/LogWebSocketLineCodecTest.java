package com.osr.web.controller.monitor;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住实时日志的行解析。
 *
 * <p><b>这里的样本行必须与 logback-spring.xml 里的 log.pattern 逐字对应</b>：
 * {@code [%d{yyyy-MM-dd HH:mm:ss.SSS}][%X{traceId}][%-5level][%logger{0}] %msg}。
 * 两者是一对隐性耦合——改了 pattern 而没改这里的正则，页面不会报错也不会告警，
 * 只是每一行都落进「续行」分支：时间、级别、logger 全部不显示，级别过滤全部失效，
 * 整屏日志变成一种颜色。这个测试就是为了让那种改动在 CI 上立刻红掉。
 */
class LogWebSocketLineCodecTest {

    private final LogWebSocket.LineCodec codec = new LogWebSocket.LineCodec();

    private JSONObject encode(String line) {
        return JSON.parseObject(codec.encode(line));
    }

    @Test
    @DisplayName("标准行：时间/traceId/级别/logger/消息各归各位")
    void parsesStandardLine() {
        JSONObject o = encode("[2026-08-22 10:00:00.123][7f3a][INFO ][StrmTaskService] 生成 STRM 完成，共 12 个文件");

        assertEquals("log", o.getString("t"));
        assertEquals("2026-08-22 10:00:00.123", o.getString("ts"));
        assertEquals("7f3a", o.getString("trace"));
        // %-5level 是左对齐补空格，"INFO " 必须 trim 掉，否则前端按 level 字段过滤时永远匹配不上
        assertEquals("INFO", o.getString("level"));
        assertEquals("StrmTaskService", o.getString("logger"));
        assertEquals("生成 STRM 完成，共 12 个文件", o.getString("msg"));
        assertNull(o.get("cont"));
    }

    @Test
    @DisplayName("traceId 为空时仍能解析")
    void parsesLineWithoutTraceId() {
        // 定时任务、启动阶段的日志没有 traceId，%X{traceId} 输出空串
        JSONObject o = encode("[2026-08-22 10:00:00.123][][WARN ][AutoSearchTask] 上一轮尚未结束，跳过本次心跳");

        assertEquals("", o.getString("trace"));
        assertEquals("WARN", o.getString("level"));
        assertEquals("AutoSearchTask", o.getString("logger"));
    }

    @Test
    @DisplayName("消息正文里出现 ERROR 字样的 INFO 行，不能被判成 ERROR")
    void doesNotGuessLevelFromMessageBody() {
        // 这正是改造前的 bug：前后端都用 line.contains("ERROR") 猜级别，于是打印索引器响应体、
        // 异常消息文本的 INFO 行会被染红、并被前端的 Error 过滤框筛出来。
        JSONObject o = encode("[2026-08-22 10:00:00.123][7f3a][INFO ][TorznabClient] 站点返回 {\"code\":\"ERROR\",\"msg\":\"rate limited\"}");

        assertEquals("INFO", o.getString("level"));
        assertTrue(o.getString("msg").contains("ERROR"));
    }

    @Test
    @DisplayName("异常堆栈的续行继承首行的 ERROR")
    void stackTraceLinesInheritErrorLevel() {
        // 堆栈续行匹配不上 pattern。若让它退回默认 INFO：关掉 Error 过滤时堆栈还在刷屏，
        // 开着 Error 过滤时又只剩一句异常消息没有堆栈——而堆栈正是故障时唯一有用的东西。
        JSONObject head = encode("[2026-08-22 10:00:00.123][7f3a][ERROR][AsynHelper] 复制任务失败");
        assertEquals("ERROR", head.getString("level"));

        JSONObject cont1 = encode("java.lang.IllegalStateException: boom");
        assertEquals("ERROR", cont1.getString("level"));
        assertEquals(Boolean.TRUE, cont1.getBoolean("cont"));
        assertEquals("java.lang.IllegalStateException: boom", cont1.getString("msg"));

        JSONObject cont2 = encode("\tat com.osr.openliststrm.helper.AsynHelper.copy(AsynHelper.java:120)");
        assertEquals("ERROR", cont2.getString("level"));
        assertEquals(Boolean.TRUE, cont2.getBoolean("cont"));

        // 下一条正常行必须把继承级别重新拉回来，不能让 ERROR 一直粘着
        JSONObject next = encode("[2026-08-22 10:00:01.000][7f3a][DEBUG][AsynHelper] 重试第 1 次");
        assertEquals("DEBUG", next.getString("level"));
        assertNull(next.get("cont"));
    }

    @Test
    @DisplayName("消息里带方括号不会截断解析")
    void bracketsInMessageAreKept() {
        JSONObject o = encode("[2026-08-22 10:00:00.123][7f3a][DEBUG][SubscriptionMatcher] 候选 [Sakurato] One Piece - 1173 [2160p]");

        assertEquals("DEBUG", o.getString("level"));
        assertEquals("SubscriptionMatcher", o.getString("logger"));
        assertEquals("候选 [Sakurato] One Piece - 1173 [2160p]", o.getString("msg"));
    }

    @Test
    @DisplayName("空消息与 null 行不抛异常")
    void handlesEmptyAndNull() {
        JSONObject empty = encode("[2026-08-22 10:00:00.123][7f3a][INFO ][Foo] ");
        assertEquals("INFO", empty.getString("level"));
        assertEquals("", empty.getString("msg"));

        JSONObject nul = encode(null);
        assertEquals("", nul.getString("msg"));
    }
}
