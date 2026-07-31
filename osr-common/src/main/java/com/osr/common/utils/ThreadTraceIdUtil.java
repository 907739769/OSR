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

    // 为子线程创建带层级关系的追踪ID
    public static String createChildTraceId() {
        String parentTraceId = getCurrentTraceId();
        if (parentTraceId == null) {
            return generateTraceId();
        }
        return parentTraceId + "-" + generateTraceId();
    }

}
