package com.osr.openliststrm.pt.subscription;

/**
 * 一次推送尝试的结果：推没推成，没推成是因为什么。
 * <p>
 * {@code SubscriptionEngine#handleGroup} 有八条返回失败的路径（无可占位缺集、候选都已推送过、
 * 过滤规则全清、没有可用下载器、下载器并发已满、被并发轮询抢占、落库失败、推送到下载器失败），
 * 每一条本来就算出了准确的原因交给 {@code SearchLogService#recordSummary} 落进搜索日志——
 * 信息一直是现成的，只是没有回给调用方。于是自动路径靠翻日志排查，手动推送则只能得到
 * 「推送失败，可能该集已无可用缺额或下载器不可用」这样一句<b>把两种不相干原因并列的猜测</b>，
 * 而真实原因很可能是第三种（比如候选被过滤规则清光、或该种子有一条不可重试的失败记录）。
 * </p>
 * <p>
 * 自动补搜通过结构化 code 区分候选不可用与容量/外部故障，决定扩搜或下轮重试。
 * {@code SubscriptionEngine#pushBest}/{@code #pushUpgrade} 仍保留 boolean 兼容入口。
 * </p>
 *
 * @param pushed 是否成功推送
 * @param reason 未推送的原因，可直接展示给用户；{@code pushed} 为 true 时恒为 null
 * @author Jack
 */
public record PushOutcome(boolean pushed, String reason, Code code) {

    public enum Code {
        SUCCESS, NO_TARGET, NO_CANDIDATE, ALREADY_RECORDED, FILTERED, IDENTITY_CONFLICT,
        NO_DOWNLOADER, CAPACITY, CLAIMED, PERSISTENCE_FAILED, DOWNLOAD_FAILED, UNSPECIFIED
    }

    public PushOutcome(boolean pushed, String reason) {
        this(pushed, reason, pushed ? Code.SUCCESS : Code.UNSPECIFIED);
    }

    private static final PushOutcome OK = new PushOutcome(true, null);

    public static PushOutcome ok() {
        return OK;
    }

    public static PushOutcome fail(String reason) {
        return new PushOutcome(false, reason);
    }

    public static PushOutcome fail(Code code, String reason) {
        return new PushOutcome(false, reason, code);
    }

    /** 只有候选不可用时才扩大搜索；容量、状态及外部故障不应靠重复搜索解决。 */
    public boolean searchMore() {
        return code == Code.NO_CANDIDATE || code == Code.ALREADY_RECORDED || code == Code.FILTERED;
    }

    public boolean retrySoon() {
        return code == Code.NO_DOWNLOADER || code == Code.CAPACITY || code == Code.PERSISTENCE_FAILED
                || code == Code.DOWNLOAD_FAILED || code == Code.CLAIMED || code == Code.NO_TARGET;
    }
}
