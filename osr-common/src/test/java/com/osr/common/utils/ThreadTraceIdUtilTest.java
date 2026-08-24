package com.osr.common.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * traceId 的段数必须有上界。
 * <p>
 * {@code AsynHelper} 那两处递归自我重排的监控循环会在已包装的任务内部再 wrap 一次，
 * 于是每一轮都在上一轮的 id 上再接一段——每分钟 9 字节，没有上界。生产日志里实测到
 * <b>145 段 / 1304 字节</b>的 traceId（消息本身只有 50 字节），traceId 独占整份日志的 31.1%。
 * <p>
 * 保留根段是这里最要紧的一条：实时日志页靠整行子串匹配，粘根段就能把这条链路的
 * 全部轮次一并捞出来。
 *
 * @author Jack
 */
class ThreadTraceIdUtilTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private static int segments(String traceId) {
        return traceId.split("-").length;
    }

    @Test
    void 正常嵌套深度不受影响() {
        String id = "aaaaaaaa";
        for (int i = 1; i < ThreadTraceIdUtil.MAX_TRACE_SEGMENTS; i++) {
            String child = ThreadTraceIdUtil.appendSegment(id, "bbbbbbbb");
            assertEquals(segments(id) + 1, segments(child), "未到上限时应逐段追加");
            assertTrue(child.startsWith(id + "-"), "未到上限时子 id 必须以父 id 为前缀：" + child);
            id = child;
        }
        assertEquals(ThreadTraceIdUtil.MAX_TRACE_SEGMENTS, segments(id));
    }

    @Test
    void 自我重排一千轮也不会涨过上限() {
        // 这正是 AsynHelper 的形态：每一轮都在上一轮的 id 上再接一段
        String id = ThreadTraceIdUtil.generateTraceId();
        for (int round = 0; round < 1000; round++) {
            id = ThreadTraceIdUtil.appendSegment(id, ThreadTraceIdUtil.generateTraceId());
            assertTrue(segments(id) <= ThreadTraceIdUtil.MAX_TRACE_SEGMENTS,
                    "第 " + round + " 轮涨到了 " + segments(id) + " 段");
        }
        // 上限 8 段 = 8×8 字符 + 7 个连字符
        assertEquals(71, id.length(), id);
    }

    @Test
    void 超限时保留根段() {
        String root = "52879ede";
        String id = root;
        for (int i = 0; i < 200; i++) {
            id = ThreadTraceIdUtil.appendSegment(id, ThreadTraceIdUtil.generateTraceId());
        }
        // 根段是把这条链路的全部轮次捞回来的唯一抓手，截断时第一个要保住的就是它
        assertTrue(id.startsWith(root + "-"), "根段丢了：" + id);
    }

    @Test
    void 超限时保留最近的段_用来看当前嵌套位置() {
        String id = "root0000";
        for (int i = 0; i < 50; i++) {
            id = ThreadTraceIdUtil.appendSegment(id, ThreadTraceIdUtil.generateTraceId());
        }
        String last = "ffffffff";
        String next = ThreadTraceIdUtil.appendSegment(id, last);
        assertTrue(next.endsWith("-" + last), "最新一段应在末尾：" + next);
    }

    @Test
    void 没有父_traceId_时生成新的() {
        MDC.clear();
        String id = ThreadTraceIdUtil.createChildTraceId();
        assertEquals(1, segments(id));
        assertEquals(8, id.length());
    }

    @Test
    void 父_traceId_为空串时不拼出前导横线() {
        // setTraceId 对空串会 remove，但别的地方直接 MDC.put 空串就会走到这里
        MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, "");
        String id = ThreadTraceIdUtil.createChildTraceId();
        assertEquals(8, id.length(), id);
        assertNotEquals('-', id.charAt(0));
    }

    @Test
    void createChildTraceId_走的是同一套封顶() {
        StringBuilder deep = new StringBuilder("root0000");
        for (int i = 0; i < 100; i++) {
            deep.append('-').append("cccccccc");
        }
        MDC.put(ThreadTraceIdUtil.TRACE_ID_KEY, deep.toString());

        String child = ThreadTraceIdUtil.createChildTraceId();
        assertEquals(ThreadTraceIdUtil.MAX_TRACE_SEGMENTS, segments(child), child);
        assertTrue(child.startsWith("root0000-"), child);
    }
}
