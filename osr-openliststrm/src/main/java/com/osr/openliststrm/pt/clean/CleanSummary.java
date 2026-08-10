package com.osr.openliststrm.pt.clean;

import lombok.Getter;
import lombok.Setter;

/**
 * 一次清理的执行结果汇总，供日志、通知与手动触发接口共用。
 *
 * @author Jack
 */
@Getter
@Setter
public class CleanSummary {

    /** 下载器名，手动触发时前端直接回显 */
    private String downloaderName;

    /** 本轮删除的辅种组数 */
    private int deletedGroups;

    /** 本轮删除的种子数（辅种组内有几个就算几个） */
    private int deletedTorrents;

    /** 本轮释放的字节数（按组去重，见 {@link CleanGroupDecision#sizeBytes()}） */
    private long freedBytes;

    /** 本轮扫描到的组数 */
    private int scannedGroups;

    /** 删除过程中失败的组数：失败的组会中止后续删除，文件不会被删掉 */
    private int failedGroups;

    /** 是否因为没有配置任何启用规则而整体跳过 */
    private boolean noRules;

    public void addDeleted(CleanGroupDecision decision) {
        deletedGroups++;
        deletedTorrents += decision.getTorrents().size();
        freedBytes += decision.sizeBytes();
    }

    /** 释放空间的人类可读表述，通知里用 */
    public String freedText() {
        return String.format("%.2f GB", freedBytes / (1024.0 * 1024 * 1024));
    }
}
