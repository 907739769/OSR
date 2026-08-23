package com.osr.openliststrm.monitor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关停期的任务被拒不算故障，不许进 sys-error.log。
 * <p>
 * 应用关停时 WatchService 还在派事件，而派给的 executor 已经在关了。生产日志里这是
 * 整整 6 小时中<b>唯一</b>一条 ERROR，还拖着一份堆栈；而 sys-error.log 的全部价值
 * 在于噪音为零（后端异常不进 docker stdout，它是排查启动失败的唯一入口）。
 * <p>
 * 反过来「队列满了」也表现为任务被拒，那是真故障，必须留在 ERROR——所以判据不能只看类型。
 *
 * @author Jack
 */
class WatchServiceMonitorShutdownTest {

    /** 与 Spring 的 TaskRejectedException 同名但不同包：判据按类名后缀走，刻意不绑定具体实现 */
    static class TaskRejectedException extends RuntimeException {
        TaskRejectedException(String m) { super(m); }
    }

    @Test
    void 关停导致的任务被拒_认出来() {
        // 生产日志里的原文
        assertTrue(WatchServiceMonitor.isShutdownRejection(new TaskRejectedException(
                "ExecutorService in shutdown state did not accept task: com.osr.common.utils.Threads$$Lambda/0x86b99")));
    }

    @Test
    void 包在外层也要认出来_沿异常链找() {
        Exception wrapped = new IllegalStateException("处理文件事件失败",
                new TaskRejectedException("Executor has been shut down"));
        assertTrue(WatchServiceMonitor.isShutdownRejection(wrapped));
    }

    @Test
    void 底层的_RejectedExecutionException_同样算() {
        assertTrue(WatchServiceMonitor.isShutdownRejection(
                new RejectedExecutionException("Task rejected: pool terminated")));
    }

    @Test
    void 队列满了是真故障_不许降级() {
        // 同一个异常类型、完全不同的处置方向：这条必须留在 ERROR 上被看见
        assertFalse(WatchServiceMonitor.isShutdownRejection(new RejectedExecutionException(
                "Task rejected from ThreadPoolExecutor[Running, pool size = 8, queue size = 10000]")));
    }

    @Test
    void 其它异常一律不降级() {
        assertFalse(WatchServiceMonitor.isShutdownRejection(new java.io.IOException("磁盘满了")));
        assertFalse(WatchServiceMonitor.isShutdownRejection(new NullPointerException()));
    }

    @Test
    void 消息为空不炸() {
        assertFalse(WatchServiceMonitor.isShutdownRejection(new RejectedExecutionException()));
    }

    @Test
    void cause_自引用不死循环() {
        // 遍历异常链的代码只要少了这道防护就是一个挂起的线程，而不是一条错误日志
        Exception e = new RejectedExecutionException("boom") {
            @Override public synchronized Throwable getCause() { return this; }
        };
        assertFalse(WatchServiceMonitor.isShutdownRejection(e));
    }
}
