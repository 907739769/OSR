package com.osr.openliststrm.service;

import java.util.List;

public interface ICopyService {

    //同步目录所有文件
    void syncFiles(String srcDir, String dstDir);

    //同步一个文件
    void syncOneFile(String srcDir, String dstDir, String relativePath);

    //同步指定子目录
    void syncFiles(String srcDir, String dstDir, String relativePath);

    /**
     * 增量同步：只同步 lastSyncTime 之后修改的文件
     * @param srcDir 源目录
     * @param dstDir 目标目录
     * @param lastSyncTime 上次同步时间（为 null 时走全量同步）
     */
    void syncFilesIncremental(String srcDir, String dstDir, java.util.Date lastSyncTime);

    /**
     * 批量删除网盘文件并更新记录
     */
    BatchRemoveOutcome batchRemoveNetDisk(List<String> idList);

    /**
     * 按记录重试复制任务。只重试失败与监控超时/任务丢失的记录，处理中与已成功的跳过。
     *
     * @return 实际提交重试的记录数；超过 20 条时在后台执行，返回时尚未跑完
     */
    int retryCopy(List<String> idList);

    /**
     * 批量重试所有失败、监控超时/任务丢失的复制记录（最多取最新 200 条）
     */
    RetryOutcome retryAllFailed();

    /**
     * @param retried   本次提交重试的记录数
     * @param remaining 超出 200 条上限、未处理的剩余失败记录数
     */
    record RetryOutcome(int retried, int remaining) {}

}
