package com.osr.openliststrm.pt.task;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FailReasonCode} 的可重试语义。这个判断决定一条失败记录会不会把对应种子
 * 对该索引器永久封死（见 {@code SubscriptionEngine#excludeAlreadyRecorded}），
 * 判错的代价是"某一集再也补不回来"，因此每个取值都单独钉死。
 *
 * @author Jack
 */
class FailReasonCodeTest {

    @Test
    void 下载器里找不到种子_可重试() {
        // 下载器重启丢任务、用户手动清任务、元数据解析超时——都不是种子本身的问题
        assertTrue(FailReasonCode.TORRENT_NOT_FOUND.retryable());
        assertTrue(FailReasonCode.isRetryable("TORRENT_NOT_FOUND"));
    }

    @Test
    void 僵尸超时_不可重试() {
        assertFalse(FailReasonCode.ZOMBIE_TIMEOUT.retryable());
        assertFalse(FailReasonCode.isRetryable("ZOMBIE_TIMEOUT"));
    }

    @Test
    void 兜底分类_不可重试() {
        // 分类未知时保持"失败即不再选"的既有行为，是最保守的默认值
        assertFalse(FailReasonCode.OTHER.retryable());
        assertFalse(FailReasonCode.isRetryable("OTHER"));
    }

    @Test
    void 空值与空白_按不可重试处理() {
        // fail_reason_code 是 20260738 迁移才加的列，更早的失败记录该列为空；
        // 当成可重试会让一批陈年失败种子在升级后突然重新涌入候选池
        assertFalse(FailReasonCode.isRetryable(null));
        assertFalse(FailReasonCode.isRetryable(""));
        assertFalse(FailReasonCode.isRetryable("   "));
    }

    @Test
    void 无法识别的取值_按不可重试处理() {
        // 手工改库、或未来版本回滚留下的未知取值，不能因为"没匹配上"就放行
        assertFalse(FailReasonCode.isRetryable("SOMETHING_ELSE"));
        assertFalse(FailReasonCode.isRetryable("torrent_not_found"));
    }

    @Test
    void value与枚举名一致_落库值不随重命名漂移() {
        for (FailReasonCode code : FailReasonCode.values()) {
            org.junit.jupiter.api.Assertions.assertEquals(code.name(), code.value());
        }
    }
}
