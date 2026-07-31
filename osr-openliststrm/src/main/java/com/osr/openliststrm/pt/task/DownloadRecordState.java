package com.osr.openliststrm.pt.task;

/**
 * pt_download_record.state 取值的唯一权威定义。之前 {@code SubscriptionEngine}、
 * {@code DownloadTrackService}、{@code DownloadRecordAdminService} 里各自维护一份同值的
 * 私有字符串常量（{@code SubscriptionEngine} 甚至直接硬编码 "PUSHED" 字面量），统一到这里后
 * 其余类只引用 {@link #value()}。
 *
 * @author Jack
 */
public enum DownloadRecordState {

    /** 已推送给下载器 */
    PUSHED("PUSHED"),
    /** 下载器已确认收到，正在下载 */
    DOWNLOADING("DOWNLOADING"),
    /** 下载完成 */
    COMPLETED("COMPLETED"),
    /** 推送/下载失败 */
    FAILED("FAILED");

    private final String value;

    DownloadRecordState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
