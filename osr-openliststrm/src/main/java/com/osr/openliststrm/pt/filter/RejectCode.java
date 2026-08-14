package com.osr.openliststrm.pt.filter;

/**
 * 候选种子被过滤规则淘汰的结构化分类，落库到 {@code pt_search_log.reason_code}。
 * <p>
 * 风格与下载失败侧的 {@code com.osr.openliststrm.pt.task.FailReasonCode} 一致，补齐的正是
 * 那一侧早就有、搜索侧一直缺的对称物：下载失败有码、有统计面板，而"候选为什么被淘汰"
 * 此前只有一句带具体数值的自由文本（{@code "做种数 3 低于下限 5"}），既没法稳定聚合，
 * 也没法做跨订阅统计。
 * </p>
 * <p>
 * <b>为什么必须是码而不是文案</b>：淘汰原因文案里嵌着实际值（做种数、体积、标签名），
 * 同一条规则会产出上百个互不相同的字符串，按文案 GROUP BY 得到的是一堆计数为 1 的碎片。
 * 用户真正想知道的是"我这 103 个候选主要卡在哪一条规则上"，那必须按规则本身聚合。
 * </p>
 *
 * @author Jack
 */
public enum RejectCode {

    /** 用户手动拉黑了这个具体种子（按 GUID） */
    BLACKLISTED_GUID("种子已拉黑"),
    /** 做种数低于配置下限 */
    LOW_SEEDERS("做种数不足"),
    /** 体积低于下限（开启按每集判定时比的是折算后的每集体积） */
    SIZE_BELOW_MIN("体积低于下限"),
    /** 体积超过上限（同上） */
    SIZE_ABOVE_MAX("体积超过上限"),
    /** 非免费种，而配置为仅要免费。索引器不返回 downloadvolumefactor 时默认按正常计量处理，容易整站命中 */
    NOT_FREE("非免费种"),
    /** 来源站点有 H&R 考核，而配置为规避 */
    HIT_AND_RUN("规避 H&R 站点"),
    /** 分辨率不在白名单内。<b>含"解析不出分辨率"</b>——白名单非空时解析不出一律淘汰 */
    RESOLUTION_NOT_ALLOWED("分辨率不在白名单"),
    /** 媒介来源不在白名单内。与分辨率同构，同样含"解析不出即淘汰" */
    SOURCE_NOT_ALLOWED("媒介来源不在白名单"),
    /** 标题为空，无法做任何基于标题的判定 */
    BLANK_TITLE("标题为空"),
    /** 用户手动拉黑了这个发布组 */
    BLACKLISTED_GROUP("发布组已拉黑"),
    /** 命中排除标签 */
    EXCLUDED_TAG("命中排除标签"),
    /** 缺少必需标签（AND 语义，配了几个就要几个都有） */
    MISSING_REQUIRED_TAG("缺少必需标签"),
    /** 标题命中排除词 */
    EXCLUDED_KEYWORD("命中排除词"),
    /**
     * 种子描述命中排除词。与 {@link #EXCLUDED_KEYWORD} 分开成两个码而不是合并：
     * 命中的是标题还是描述，决定了用户该去改哪一个输入框——聚合到一起就分不出来了。
     */
    EXCLUDED_DESCRIPTION_KEYWORD("命中描述排除词"),
    /** 标题未命中任何包含词（OR 语义） */
    NO_INCLUDE_KEYWORD("未命中包含词"),
    /** 外语片但标题/描述里检测不到中文字幕标识。索引器不返回 description 时容易整片命中 */
    NO_CHINESE_SUBTITLE("外语片无中文字幕"),
    /**
     * 兜底分类。新增淘汰规则时应显式给出自己的取值，不要复用本项——
     * 复用会让统计面板上出现一堆"其它"，等于没有统计。
     */
    OTHER("其它");

    private final String label;

    RejectCode(String label) {
        this.label = label;
    }

    /** 落库值，直接用枚举名 */
    public String value() {
        return name();
    }

    /** 中文短标签，供日志摘要、通知与统计面板展示 */
    public String label() {
        return label;
    }

    /**
     * 按落库字符串取中文标签。无法识别（历史数据、拼写错误）时原样返回入参，
     * 不抛异常也不塌成"其它"——统计面板上显示一个陌生取值，比显示一个错误的分类更诚实。
     *
     * @param code 落库的 reason_code，允许为 null/空
     */
    public static String labelOf(String code) {
        if (code == null || code.isBlank()) {
            return OTHER.label();
        }
        for (RejectCode c : values()) {
            if (c.name().equals(code)) {
                return c.label();
            }
        }
        return code;
    }
}
