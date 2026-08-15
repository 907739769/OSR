package com.osr.openliststrm.pt.transfer;

import lombok.Getter;
import lombok.Setter;

/**
 * 一条规则跑完一轮的结果。
 *
 * @author Jack
 */
@Getter
@Setter
public class TransferSummary {

    /** 规则名，用于日志与通知 */
    private String ruleName;

    /** 本轮扫描到的种子数（源下载器上的全部种子） */
    private int scanned;

    /** 本轮新发起的转移数（已加到目标端并开始校验） */
    private int started;

    /** 本轮完成的转移数（含上一轮发起、这一轮才校验完的） */
    private int completed;

    /** 本轮失败的转移数 */
    private int failed;

    /** 目标端已存在、直接跳过的数量 */
    private int skipped;

    /** 源下载器不支持导出种子文件时为 true，此时本规则整轮不做任何事 */
    private boolean exportUnsupported;

    /** 本轮完成的转移共搬走多少字节，仅用于通知里的展示 */
    private long completedBytes;

    public TransferSummary(String ruleName) {
        this.ruleName = ruleName;
    }

    /** 有没有值得告诉用户的事情发生。全是 0 时静默——多数轮次如此，逐轮发通知等于刷屏 */
    public boolean worthNotifying() {
        return started > 0 || completed > 0 || failed > 0;
    }

    public String completedSizeText() {
        double gb = completedBytes / (1024.0 * 1024 * 1024);
        return String.format("%.2f GB", gb);
    }
}
