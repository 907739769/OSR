package com.osr.openliststrm.pt.transfer;

/**
 * 一次转移做种的状态。
 * <p>
 * 只有 {@link #VERIFYING} 是中间态，其余三个都是终态——记录不会从终态再动，
 * 同一个种子下一次转移是一条新记录。这样历史可追溯，而"这轮还要不要管它"
 * 的判断只需要看 state 是不是 VERIFYING。
 * </p>
 *
 * @author Jack
 */
public enum TransferState {

    /** 种子已加到目标下载器（暂停态）并触发了本地数据校验，等待校验结果 */
    VERIFYING("VERIFYING", "校验中"),

    /** 校验通过、目标端已启动做种；按规则配置源端种子可能已删除（文件永远保留） */
    COMPLETED("COMPLETED", "已完成"),

    /** 转移失败，失败原因见 fail_reason。目标端若已加入种子会被撤销（不删文件） */
    FAILED("FAILED", "失败"),

    /** 目标下载器里本来就有这个种子，本次不做任何事 */
    SKIPPED("SKIPPED", "已跳过");

    private final String value;
    private final String desc;

    TransferState(String value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public String value() {
        return value;
    }

    public String getDesc() {
        return desc;
    }
}
