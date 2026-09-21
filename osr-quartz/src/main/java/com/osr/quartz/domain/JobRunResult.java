package com.osr.quartz.domain;

/**
 * 手动执行定时任务的受理结果。
 *
 * 原先 run() 只返回 boolean，调用方拿到 false 一律提示「任务不存在或已过期」——
 * 而「上一轮还在跑」是最常见的那种 false，提示却完全对不上。
 *
 * @author osr
 */
public enum JobRunResult
{
    /** 已提交到后台执行（不代表已执行完，结果见执行记录） */
    TRIGGERED("已触发执行，结果请查看执行记录"),

    /** 库里查不到这个任务 */
    NOT_FOUND("任务不存在"),

    /** 该任务禁止并发，上一轮尚未结束 */
    ALREADY_RUNNING("该任务正在执行中，请等待本轮结束"),

    /** 任务不在调度器里，补注册也失败了 */
    REGISTER_FAILED("任务注册到调度器失败，请检查 cron 表达式与调用目标");

    private final String message;

    JobRunResult(String message)
    {
        this.message = message;
    }

    public String getMessage()
    {
        return message;
    }
}
