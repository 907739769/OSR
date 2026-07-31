package com.osr.openliststrm.pt.task;

/**
 * 下载失败原因的结构化分类，落库到 {@code pt_download_record.fail_reason_code}，
 * 供前端下载记录页展示分类标签、未来按维度筛选/统计使用。
 * 风格与 {@link DownloadRecordState}/{@link com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState} 一致。
 *
 * @author Jack
 */
public enum FailReasonCode {

    /** 下载器里已经找不到对应种子（可能被删除，或磁力元数据解析失败） */
    TORRENT_NOT_FOUND("TORRENT_NOT_FOUND"),
    /** 种子仍在下载器里但超过僵尸超时仍未完成 */
    ZOMBIE_TIMEOUT("ZOMBIE_TIMEOUT"),
    /** 兜底分类：当前代码里没有其他失败路径会产生 FAILED 记录，为将来的失败路径（如推送失败落记录）预留 */
    OTHER("OTHER");

    private final String value;

    FailReasonCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
