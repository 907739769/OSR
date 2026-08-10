package com.osr.openliststrm.pt.clean;

/**
 * 一个辅种组<b>没有</b>被清理的原因。
 * <p>
 * 自动删种是不可逆操作，用户第一时间想问的永远是"为什么它还在"或"为什么它没了"。
 * 把跳过原因做成枚举而不是散落的日志字符串，是为了让预览接口能逐组给出答案，
 * 也让"规则配错了"与"护栏拦住了"这两类完全不同的情况一眼可分。
 * </p>
 *
 * @author Jack
 */
public enum CleanSkipReason {

    /** 组内有种子尚未下载完成 */
    NOT_COMPLETED("尚未下载完成"),

    /** 组内有种子正在校验/移动/错误态，此刻动它不安全 */
    BUSY_STATE("种子处于校验/移动/错误状态"),

    /** 组内有种子带着排除标签 */
    EXCLUDED_TAG("带有排除标签"),

    /** 组内有种子仍在 OSR 的 H&R 保种考核中 */
    HIT_AND_RUN_PENDING("H&R 考核未达标"),

    /** 组内有种子对应的集还停在在途/洗版中，文件多半还没传完网盘 */
    UPLOAD_PENDING("关联集尚未入库（可能还在上传网盘）"),

    /** 组内有种子的体积落不进任何一条启用规则的区间 */
    NO_RULE_MATCHED("体积不匹配任何规则"),

    /** 组内有种子的做种时长还没达到命中规则的下限 */
    SEED_TIME_NOT_REACHED("做种时长未达标"),

    /** 本轮删除数量已达下载器配置的上限，留到下一轮 */
    ROUND_LIMIT("已达本轮删除上限");

    private final String desc;

    CleanSkipReason(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
