package com.osr.common.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FaultThrottle} 的节流语义。
 */
class FaultThrottleTest {

    @Test
    @DisplayName("首次失败要说，紧接着的重复失败闭嘴")
    void firstFailureWarnsThenGoesQuiet() {
        FaultThrottle throttle = new FaultThrottle(120);

        assertTrue(throttle.onFailure("qb").shouldReport(), "第一次失败必须放行");
        for (int i = 2; i <= 119; i++) {
            assertFalse(throttle.onFailure("qb").shouldReport(), "第 " + i + " 次不该再喊");
        }
    }

    @Test
    @DisplayName("长期故障按间隔重提，不会彻底消失在日志里")
    void longRunningFaultIsRepeated() {
        // 彻底静默的代价是「故障两天了，日志里只有最初那一条」——排查的人往回翻不到，
        // 会以为它早就好了
        FaultThrottle throttle = new FaultThrottle(5);

        assertTrue(throttle.onFailure("qb").shouldReport());
        assertFalse(throttle.onFailure("qb").shouldReport());
        assertFalse(throttle.onFailure("qb").shouldReport());
        assertFalse(throttle.onFailure("qb").shouldReport());

        FaultThrottle.Decision fifth = throttle.onFailure("qb");
        assertTrue(fifth.shouldReport(), "第 5 次应当重提");
        assertEquals(5, fifth.consecutiveFailures(), "重提时要能说出累计次数，否则看不出持续了多久");
    }

    @Test
    @DisplayName("恢复只报一次，之后一直安静")
    void recoveryIsReportedExactlyOnce() {
        FaultThrottle throttle = new FaultThrottle(120);

        assertFalse(throttle.onSuccess("qb"), "从没失败过就不该报「已恢复」");

        throttle.onFailure("qb");
        assertTrue(throttle.onSuccess("qb"), "故障后的第一次成功要报恢复");
        assertFalse(throttle.onSuccess("qb"), "之后不该每轮都报「一切正常」");
        assertEquals(0, throttle.consecutiveFailures("qb"));
    }

    @Test
    @DisplayName("恢复后再次故障，重新从「首次」开始算")
    void faultAfterRecoveryWarnsAgain() {
        FaultThrottle throttle = new FaultThrottle(120);

        throttle.onFailure("qb");
        throttle.onFailure("qb");
        throttle.onSuccess("qb");

        FaultThrottle.Decision again = throttle.onFailure("qb");
        assertTrue(again.shouldReport(), "这是一次新的故障，必须重新告警");
        assertEquals(1, again.consecutiveFailures(), "计数要归零重来");
    }

    @Test
    @DisplayName("不同 key 互不影响")
    void keysAreIndependent() {
        // 一个下载器挂了不该让另一个下载器的故障被吞掉
        FaultThrottle throttle = new FaultThrottle(120);

        assertTrue(throttle.onFailure("qb").shouldReport());
        assertTrue(throttle.onFailure("tr").shouldReport(), "另一个故障源的首次失败照样要说");

        throttle.onSuccess("qb");
        assertEquals(1, throttle.consecutiveFailures("tr"), "恢复一个不该清掉另一个的计数");
    }
}
