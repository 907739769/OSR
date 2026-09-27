package com.osr.openliststrm.pt.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 在下载记录页删除一个下载的结果，前端据此组织提示文案。
 *
 * @author Jack
 */
@Data
@AllArgsConstructor
public class DownloadDeleteResult {

    /** 是否连同已下载的文件一起删了 */
    private boolean filesDeleted;

    /**
     * 这条记录是否因此转成了失败（在途下载被删时为 true，关联集已退回缺失）。
     * 已完成 / 已失败的记录删种不改记录状态，为 false
     */
    private boolean recordFailed;
}
