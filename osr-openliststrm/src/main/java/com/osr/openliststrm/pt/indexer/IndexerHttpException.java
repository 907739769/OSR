package com.osr.openliststrm.pt.indexer;

import java.io.IOException;

/**
 * 索引器返回非 2xx 时抛出，携带原始状态码与 {@code Retry-After} 秒数。
 * <p>
 * 之前所有非 2xx 一律被压成 {@code IOException("索引器返回HTTP " + code)}，状态码到了上层
 * 只剩一个字符串，导致 429（限流）/503（indexer 暂时不可用）与 500（配置错）、401（apikey 失效）
 * 走完全相同的"失败 +1"处置路径。对 PT 场景这是致命的：429 恰恰意味着"你打太快了"，
 * 而当时的反应是继续按原节奏（甚至更快，见 {@code RssPollService#pollOne} 的退避逻辑）重试。
 * </p>
 *
 * @author Jack
 */
public class IndexerHttpException extends IOException {

    private static final long serialVersionUID = 1L;

    /** HTTP 状态码 */
    private final int statusCode;

    /**
     * 响应 {@code Retry-After} 头解析出的秒数；响应未给出或格式非法时为 {@code null}。
     * 只支持 delta-seconds 形式（{@code Retry-After: 120}）——HTTP-date 形式在索引器实现中
     * 几乎不出现，解析失败按"未给出"处理即可，调用方本就有自己的默认冷却时长兜底。
     */
    private final Integer retryAfterSeconds;

    public IndexerHttpException(int statusCode, Integer retryAfterSeconds) {
        super("索引器返回HTTP " + statusCode
                + (retryAfterSeconds == null ? "" : "（Retry-After " + retryAfterSeconds + "秒）"));
        this.statusCode = statusCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Integer getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * 是否属于"对方让你慢下来"而非"对方坏了"。
     * <p>
     * 429 Too Many Requests 与 503 Service Unavailable 都是暂时性信号：索引器/站点本身没有
     * 配置问题，继续累加失败次数最终把它自动停用是误伤。这类状态只该触发冷却，不该计入 fail_count。
     * </p>
     */
    public boolean isThrottled() {
        return statusCode == 429 || statusCode == 503;
    }
}
