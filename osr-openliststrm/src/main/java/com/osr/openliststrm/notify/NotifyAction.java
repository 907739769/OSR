package com.osr.openliststrm.notify;

/**
 * 通知上的一个快捷操作，本质是一条<b>聊天指令</b>（如「补搜 12」「重试下载 34」）。
 * <p>
 * 走聊天指令而不是另开一套回调协议：身份、归属校验、执行、回执全都复用
 * {@code chat/PtChatCommandService}，Telegram 点按钮与企业微信手打指令是同一条路。
 * 能用上它的只有能回指令的渠道——Telegram 渲染成按钮，企业微信渲染成「可直接回复」的提示，
 * Webhook / Bark / Gotify 忽略。所以<b>正文必须自给自足</b>，操作只是捷径。
 *
 * @param label   按钮文字
 * @param command 点下去等价于发送的指令文本
 * @author Jack
 */
public record NotifyAction(String label, String command) {

    /** 立即补搜某条订阅的全部缺集 */
    public static NotifyAction searchMissing(Integer subId) {
        return new NotifyAction("立即补搜", "补搜 " + subId);
    }

    /** 查看某条订阅的进度 */
    public static NotifyAction progress(Integer subId) {
        return new NotifyAction("查看进度", "进度 " + subId);
    }

    /** 重试一条失败的下载记录 */
    public static NotifyAction retryDownload(Integer recordId) {
        return new NotifyAction("重试下载", "重试下载 " + recordId);
    }

    /** 拉黑某条下载记录对应的种子 */
    public static NotifyAction blacklistTorrent(Integer recordId) {
        return new NotifyAction("拉黑此种子", "拉黑种子 " + recordId);
    }
}
