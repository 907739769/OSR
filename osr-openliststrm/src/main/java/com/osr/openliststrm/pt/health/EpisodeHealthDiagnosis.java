package com.osr.openliststrm.pt.health;

/**
 * 「为什么这一集还缺着」的诊断码。
 * <p>
 * 体检的价值不在于报出「缺了 3 集」——订阅详情页本来就看得到，而在于回答
 * <b>接下来该做什么</b>。这些判据全部来自已经落库的字段（{@code pt_subscription.auto_search}、
 * {@code last_auto_search_no_result}、{@code last_auto_search_reject_sign}、
 * {@code pt_subscription_episode.state/file_confirmed}），不需要新增采集点，
 * 也不需要在体检时打任何外部请求。
 * </p>
 * <p>
 * 诊断挂在<b>集</b>上而不是订阅上：同一条订阅完全可能一部分集缺着、另一部分卡在上传，
 * 硬压成一个「主诊断」就得定一套武断的优先级，而那个优先级对用户没有意义。
 * 订阅行上展示的是去重后的诊断集合。
 * </p>
 *
 * @author Jack
 */
public enum EpisodeHealthDiagnosis {

    /**
     * 这条订阅根本没开自动补搜——体检要抓的头号问题。
     * <p>
     * {@code auto_search} 的库默认值是 {@code '0'}，建订阅时也不会自动打开（那是刻意的：
     * 每条开着的订阅每轮都要向每个索引器打满一整份检索计划，全量打开会让追完的老剧空转）。
     * 代价是用户必须逐条手动开，而「哪几条该开」在改造前无处可看。
     * </p>
     */
    AUTO_SEARCH_OFF("未开启自动补搜", "缺集不会被自动搜索。可在本页一键开启，或先「立即补搜」试一次"),

    /** 连续失败达到熔断阈值，自动链路已放弃 */
    BLOCKED("已熔断", "连续失败已达阈值，自动重试已停止。到订阅详情页重置该集，或换个资源"),

    /**
     * 补搜跑过且落空，原因是索引器上压根没搜到候选。
     * <p>
     * 与 {@link #SEARCH_ALL_REJECTED} 分开是整个诊断里最重要的一次区分：一个要去改关键词
     * 和索引器，一个要去松过滤规则，方向完全相反。这个区分在 {@code last_auto_search_reject_sign}
     * 里已经用 {@code NO_CANDIDATE} 这个显式取值表达过了，这里只是把它读出来。
     * </p>
     */
    SEARCH_NO_CANDIDATE("补搜落空·未搜到候选", "索引器上没有任何候选。检查订阅的标题/季号、索引器是否可用、该资源是否真的存在"),

    /** 补搜跑过且落空，原因是候选全被过滤规则淘汰 */
    SEARCH_ALL_REJECTED("补搜落空·候选被过滤", "搜到了候选但全被过滤规则淘汰。按右侧的淘汰原因放宽对应规则"),

    /**
     * 文件已在下载器里确认存在，卡的是上传网盘或 STRM/刮削这一段。
     * <p>
     * 这一档<b>不要建议重下</b>：本地文件本来就在，重下改变不了任何事，只会白费带宽、
     * 多背一份 H&amp;R 保种义务。判据来自 {@code file_confirmed}，与
     * {@code StuckEpisodeSweepService} 用的是同一个字段、同一套语义。
     * </p>
     */
    UPLOAD_PENDING("已下好·等待入库", "种子里确实有这个文件，卡住的是上传或刮削。到「复制记录」页看有没有失败任务，不需要重下"),

    /** 已推送下载器，正在下载 */
    DOWNLOADING("下载中", "已推送下载器。若长时间不动，到「下载记录」页看这条种子的状态"),

    /** 补搜开着、还没落空过，等下一轮到期 */
    SEARCHING("等待下一轮补搜", "自动补搜已开启且尚未落空，下次到期时会再搜一次");

    private final String label;
    private final String advice;

    EpisodeHealthDiagnosis(String label, String advice) {
        this.label = label;
        this.advice = advice;
    }

    /** 页面展示名 */
    public String getLabel() {
        return label;
    }

    /** 处置建议，前端在诊断徽章的 tooltip 里展示 */
    public String getAdvice() {
        return advice;
    }
}
