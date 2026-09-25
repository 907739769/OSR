package com.osr.openliststrm.service;

import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmTaskPlus;

import java.util.List;

public interface IStrmService {

    void strmDir(String path);

    /**
     * 执行一个 STRM 任务。任务开了增量扫描时，定时执行（forceFull=false）在全量周期内走增量、
     * 到期走全量并重建快照；手动执行（forceFull=true）一律全量并重建快照。没开增量的任务等同 {@link #strmDir}。
     */
    void strmTask(OpenlistStrmTaskPlus task, boolean forceFull);

    void strmOneFile(String path);

    /**
     * 同 {@link #strmOneFile(String)}，调用方拿得到网盘文件大小时（复制完成触发、兜底恢复）一并记进生成记录。
     * 只处理视频文件，其余类型直接跳过。
     */
    void strmOneFile(String path, Long fileSize);

    /**
     * 批量删除网盘文件并更新记录
     */
    BatchRemoveOutcome batchRemoveNetDisk(List<String> idList);

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
