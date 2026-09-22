package com.osr.openliststrm.pt.autoadd.dto;

import lombok.Data;

/**
 * 一轮热门自动订阅规则的执行结果统计。
 *
 * @author Jack
 */
@Data
public class AutoAddRunResult {

    /** 新增订阅数 */
    private int addedCount;

    /** 因过滤/已存在/已删除/未匹配跳过数 */
    private int skippedCount;

    /** 建订阅失败数 */
    private int failedCount;

    /**
     * 拉取榜单失败时的原因，成功时为 null。
     * 不单独给出的话，拉榜失败与「榜单上没有新东西」都是 0/0/0，手动执行的人分不出来
     */
    private String fetchError;

    public AutoAddRunResult(int addedCount, int skippedCount, int failedCount) {
        this.addedCount = addedCount;
        this.skippedCount = skippedCount;
        this.failedCount = failedCount;
    }

    public static AutoAddRunResult fetchFailed(String reason) {
        AutoAddRunResult result = new AutoAddRunResult(0, 0, 0);
        result.setFetchError(reason);
        return result;
    }
}
