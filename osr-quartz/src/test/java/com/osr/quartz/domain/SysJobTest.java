package com.osr.quartz.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SysJob 上那些没有库表字段、只为前端算出来的属性。
 */
class SysJobTest
{
    @Test
    @DisplayName("合法 cron 能算出下次执行时间")
    void nextValidTimeForValidCron()
    {
        SysJob job = new SysJob();
        job.setCronExpression("0 0 3 * * ?");

        assertNotNull(job.getNextValidTime());
    }

    @Test
    @DisplayName("非法 cron 返回 null，而不是抛异常")
    void nextValidTimeSwallowsInvalidCron()
    {
        SysJob job = new SysJob();
        job.setCronExpression("每天三点");

        // 这个 getter 在列表接口序列化每一行时都会被调用：让它抛出去的话，
        // 库里一条被手工改坏的 cron 就能让整个定时任务列表返回 500，
        // 而不是那一行的「下次执行」显示不出来
        assertNull(assertDoesNotThrow(job::getNextValidTime));
    }

    @Test
    @DisplayName("cron 为空时返回 null")
    void nextValidTimeForEmptyCron()
    {
        assertNull(new SysJob().getNextValidTime());

        SysJob blank = new SysJob();
        blank.setCronExpression("");
        assertNull(blank.getNextValidTime());
    }
}
