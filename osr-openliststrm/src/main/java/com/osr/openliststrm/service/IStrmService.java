package com.osr.openliststrm.service;

import java.util.List;

public interface IStrmService {

    void strmDir(String path);

    void strmOneFile(String path);

    /**
     * 批量删除网盘文件并更新记录
     */
    void batchRemoveNetDisk(List<String> idList);

    /**
     * 重试STRM任务
     */
    void retryStrm(List<String> idList);

    /**
     * 批量重试所有失败的 STRM 记录（最多重试最新 200 条）
     */
    RetryOutcome retryAllFailed();

    /**
     * @param retried   本次提交重试的记录数
     * @param remaining 超出 200 条上限、未处理的剩余失败记录数
     */
    record RetryOutcome(int retried, int remaining) {}

}
