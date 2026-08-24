package com.osr.common.utils;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * @Author Jack
 * @Date 2025/7/23 20:02
 * @Version 1.0.0
 */
public class ThreadTraceIdUtil {

    public static final String TRACE_ID_KEY = "traceId";

    // 生成新的追踪ID
    public static String generateTraceId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // 获取当前线程的追踪ID
    public static String getCurrentTraceId() {
        return MDC.get(TRACE_ID_KEY);
    }

    // 设置当前线程的追踪ID
    public static void setTraceId(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            MDC.remove(TRACE_ID_KEY);
        } else {
            MDC.put(TRACE_ID_KEY, traceId);
        }
    }

    // 初始化追踪ID（用于主线程或新请求）
    public static void initTraceId() {
        String traceId = generateTraceId();
        setTraceId(traceId);
    }

    /**
     * traceId 最多保留的段数。超出时保留<b>根段</b>与最近的若干段，中间的丢弃。
     * <p>
     * 实测正常嵌套深度是 2~4（请求 → 异步 → 子异步），一份 3431 条记录的生产日志里
     * 3139 条不足 10 段，8 段留足了余量。
     * </p>
     */
    static final int MAX_TRACE_SEGMENTS = 8;

    /**
     * 为子线程创建带层级关系的追踪ID，段数封顶。
     * <p>
     * <b>封顶是必需的，不是优化</b>：本方法是 {@code 父 + "-" + 8位}，而
     * {@code AsynHelper} 那两处<b>递归自我重排</b>的监控循环
     * （{@code processCopyListRecursive} / {@code checkOneFileRecursive}）会在
     * <b>已包装的任务内部</b>再调一次 {@code Threads.wrap}，于是每一轮都在上一轮的 id 上
     * 再接一段——每分钟 9 字节，没有上界。生产日志里实测到 <b>145 段 / 1304 字节</b>的
     * traceId，而那一行的消息只有 50 字节；traceId 独占整份日志的 31.1%。跑满一天的复制
     * 任务，每一行会拖着约 13 KB 的 id。
     * </p>
     * <p>
     * 除了体积，还有两笔账：MDC 是逐线程拷贝的，那个串每轮重新拼一次（任务生命期上是
     * O(n²)）；而 1.3 KB 的 id 已经没法粘进实时日志页的过滤框——那是它存在的全部理由。
     * </p>
     * <p>
     * <b>封在这里而不是改那两个调用点</b>：这样以后再有人写自我重排的循环也自动安全，
     * 而不是留下一条「需要有人记得」的规矩（同 {@code MediaExtensionProvider} 按配置原文
     * 缓存而不是按键名的取向）。
     * </p>
     * <p>
     * <b>保留根段</b>是关键：实时日志页靠整行子串匹配，粘根段就能把这条链路的全部轮次
     * 一并捞出来，而截断后的中间段没有人会去读。最近的几段留着，用来看当前的嵌套位置。
     * </p>
     */
    public static String createChildTraceId() {
        String parentTraceId = getCurrentTraceId();
        // 空串也当没有父：否则 split 出一个空段，拼出 "-xxxxxxxx" 这种带前导横线的 id
        if (parentTraceId == null || parentTraceId.isEmpty()) {
            return generateTraceId();
        }
        return appendSegment(parentTraceId, generateTraceId());
    }

    /** 拼接并按 {@link #MAX_TRACE_SEGMENTS} 封顶，保留根段与最近的若干段 */
    static String appendSegment(String parentTraceId, String childSegment) {
        String[] segments = parentTraceId.split("-");
        if (segments.length < MAX_TRACE_SEGMENTS) {
            return parentTraceId + "-" + childSegment;
        }
        StringBuilder sb = new StringBuilder(segments[0]);
        // 末尾要给新段留一个位置，因此从倒数第 (MAX-2) 段开始接
        for (int i = segments.length - (MAX_TRACE_SEGMENTS - 2); i < segments.length; i++) {
            sb.append('-').append(segments[i]);
        }
        return sb.append('-').append(childSegment).toString();
    }

}
