package com.osr.openliststrm.helper;

import com.alibaba.fastjson2.JSONObject;
import org.apache.commons.lang3.StringUtils;

import java.time.Duration;

/**
 * 同步记录 {@code fail_reason} 的文案，内存监控链（{@code AsynHelper}）、重启兜底（{@code CopyRecoveryTask}）、
 * 按记录重试（{@code CopyServiceImpl}）三处共用。
 * <p>
 * 收口在一处是因为同一种故障会从不同链路落进来：OpenList 重启弄丢任务，内存链看到的是 404、
 * 兜底任务看到的是「状态不可考」——各写一份文案的话，用户在页面上看到两种说法，会以为是两件事。
 * 文案按「发生了什么 + 可能的原因」写，不写成错误码：用户拿到它要能直接判断下一步做什么。
 */
public final class CopyFailReason {

    /** 与 {@code fail_reason varchar(500)} 对齐，OpenList 回的错误信息偶尔带整段堆栈 */
    static final int MAX_LENGTH = 500;

    private CopyFailReason() {
    }

    /**
     * OpenList 复制任务失败（state=7）。原因取任务详情里的 {@code error} 字段，那是 OpenList 自己给的解释，
     * 最常见的是目标网盘报错（容量不足、限流、登录失效），比任何这一侧的猜测都准。
     */
    public static String taskFailed(JSONObject taskInfo) {
        JSONObject data = taskInfo == null ? null : taskInfo.getJSONObject("data");
        String error = data == null ? null : data.getString("error");
        return StringUtils.isBlank(error)
                ? "OpenList 复制任务失败（未返回具体原因）"
                : truncate("OpenList 复制任务失败：" + error.trim());
    }

    /** 任务已从 OpenList 的任务表消失：多半是 OpenList 重启过，文件可能已经复制完，只是没人收尾 */
    public static String taskLost() {
        return "OpenList 中已查不到该复制任务（OpenList 可能重启过），结果未知，请核对目标文件后重试";
    }

    /** 兜底任务的裁决：任务查不到、目标文件也不存在 */
    public static String taskLostAndDstMissing() {
        return "OpenList 中已查不到该复制任务，且目标文件不存在（OpenList 可能重启过），可直接重试";
    }

    /** 兜底任务的裁决：提交复制时 OpenList 没回任务 ID，只能看目标文件，而目标文件不存在 */
    public static String noTaskIdAndDstMissing() {
        return "提交复制时 OpenList 未返回任务 ID，且目标文件不存在，可直接重试";
    }

    /** 查询任务状态时 OpenList 无响应，内存监控就此停止 */
    public static String statusQueryFailed() {
        return "查询复制任务状态失败（OpenList 无响应），已停止监控，请核对目标文件后重试";
    }

    public static String monitorTimeout(Duration duration) {
        return "超过 " + duration.toMinutes() + " 分钟仍未结束，已停止监控，请到 OpenList 任务列表核查";
    }

    public static String sourceQueryFailed() {
        return "查询源文件失败（OpenList 无响应）";
    }

    public static String belowMinSize(long size, long minSize) {
        return "源文件大小 " + size + " 字节，低于同步阈值 " + minSize + " 字节，未复制";
    }

    /** 提交 fs/copy 失败，带上 OpenList 回的 message（如「object not found」「storage not found」） */
    public static String submitFailed(JSONObject resp) {
        String message = resp == null ? null : resp.getString("message");
        return StringUtils.isBlank(message)
                ? "提交复制任务失败（OpenList 无响应）"
                : truncate("提交复制任务失败：" + message.trim());
    }

    static String truncate(String reason) {
        return reason.length() <= MAX_LENGTH ? reason : reason.substring(0, MAX_LENGTH - 1) + "…";
    }
}
