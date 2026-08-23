package com.osr.common.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogOnce} 的行为约束。
 *
 * @author Jack
 */
class LogOnceTest {

    @Test
    void 首次放行之后一律拦下() {
        LogOnce once = new LogOnce();
        assertTrue(once.firstTime("Call Me By Fire S01E14"));
        for (int i = 0; i < 100; i++) {
            assertFalse(once.firstTime("Call Me By Fire S01E14"));
        }
        assertEquals(1, once.size());
    }

    @Test
    void 不同键互不影响() {
        LogOnce once = new LogOnce();
        assertTrue(once.firstTime("A"));
        assertTrue(once.firstTime("B"));
        assertFalse(once.firstTime("A"));
        assertEquals(2, once.size());
    }

    @Test
    void 超出容量后淘汰最久未访问的键() {
        LogOnce once = new LogOnce(2);
        once.firstTime("A");
        once.firstTime("B");
        once.firstTime("C");          // 挤掉 A
        assertEquals(2, once.size());
        assertTrue(once.firstTime("A"), "A 已被淘汰，应重新判为首次");
        assertFalse(once.firstTime("C"), "C 还在，不应重打");
    }

    /**
     * 这条钉住 accessOrder=true。改成插入序 LRU 的话，稳态下最活跃的那批键会被反复驱逐、
     * 反复重打，正好把去重的效果抵消掉——而单看「首次放行」那条用例是发现不了的。
     */
    @Test
    void 反复出现的键会留在热端不被淘汰() {
        LogOnce once = new LogOnce(2);
        once.firstTime("hot");
        once.firstTime("x");
        for (int i = 0; i < 10; i++) {
            assertFalse(once.firstTime("hot"), "hot 每轮都出现，不该被判成首次");
            once.firstTime("filler" + i);   // 每轮来一个新键，把容量顶满
        }
        assertFalse(once.firstTime("hot"));
    }

    @Test
    void 键为空时放行而不是吞掉() {
        LogOnce once = new LogOnce();
        assertTrue(once.firstTime(null));
        assertTrue(once.firstTime(null), "拿不到键就无从去重，宁可多打也不能静默丢日志");
        assertEquals(0, once.size());
    }

    @Test
    void forget与clear让键重新变成首次() {
        LogOnce once = new LogOnce();
        once.firstTime("A");
        once.firstTime("B");
        once.forget("A");
        assertTrue(once.firstTime("A"));
        assertFalse(once.firstTime("B"));
        once.clear();
        assertEquals(0, once.size());
        assertTrue(once.firstTime("B"));
    }

    @Test
    void 并发调用下同一个键只放行一次() throws Exception {
        LogOnce once = new LogOnce();
        int threads = 16;
        java.util.concurrent.atomic.AtomicInteger passed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.List<Thread> workers = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (once.firstTime("same")) {
                    passed.incrementAndGet();
                }
            });
            t.start();
            workers.add(t);
        }
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }
        assertEquals(1, passed.get());
    }
}
