package com.osr.openliststrm.pt.task;

/**
 * 下载记录的 H&R（Hit and Run）保种状态，落库到 {@code pt_download_record.hr_state}。
 * <p>
 * 列为 {@code null} 表示"不适用"——来源站点没开 H&R 考核，或记录还没下载完成。
 * 风格与 {@link DownloadRecordState}/{@link FailReasonCode} 一致。
 * </p>
 *
 * @author Jack
 */
public enum HitAndRunState {

    /** 下载已完成，正在保种，尚未达到站点要求 */
    PENDING("PENDING"),
    /** 已满足站点的做种时长或分享率要求，可以安全删除了 */
    SATISFIED("SATISFIED"),
    /**
     * 达标前种子就从下载器里消失了，很可能已经产生了一次 H&R 记过。
     * <p>
     * 这是个<b>已经发生</b>的事实而不是待办：OSR 自己从不删种，能走到这个状态说明是用户手动删了，
     * 或下载器的自动管理/做种限额把它清掉了。置成该状态只为把事情捅到用户眼前，
     * 好让他去站点申诉或重新做种，系统不会也无法自动补救。
     * </p>
     */
    VIOLATED("VIOLATED");

    private final String value;

    HitAndRunState(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
