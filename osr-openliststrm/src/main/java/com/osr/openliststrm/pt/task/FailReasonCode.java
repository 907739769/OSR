package com.osr.openliststrm.pt.task;

/**
 * 下载失败原因的结构化分类，落库到 {@code pt_download_record.fail_reason_code}，
 * 供前端下载记录页展示分类标签、未来按维度筛选/统计使用。
 * 风格与 {@link DownloadRecordState}/{@link com.osr.openliststrm.pt.subscription.SubscriptionEpisodeState} 一致。
 * <p>
 * 每个取值还带一个 {@link #retryable} 语义，决定这条失败记录是否允许该种子被重新选中，
 * 见 {@link com.osr.openliststrm.pt.subscription.SubscriptionEngine} 的候选排除逻辑。
 * </p>
 *
 * @author Jack
 */
public enum FailReasonCode {

    /**
     * 下载器里已经找不到对应种子（可能被删除，或磁力元数据解析失败）。
     * <p>
     * <b>可重试。</b>这类失败说的是"下载器这边出了状况"而不是"这个种子是坏的"——
     * qB 重启丢任务、用户在下载器里手动清了任务、元数据解析恰好超时，种子本身可能完好无损。
     * 判成不可重试会把该集当前最优的那个种子永久烧掉，若该集只此一个资源，往后再也补不回来。
     * </p>
     */
    TORRENT_NOT_FOUND("TORRENT_NOT_FOUND", true, "种子丢失"),
    /**
     * 种子仍在下载器里但超过僵尸超时仍未完成。
     * <p>
     * <b>不可重试。</b>躺了十几个小时还没下完，基本等于死种/无人做种，再选它就是重复踩同一个坑。
     * 用户仍可在下载记录页手动重试，那条路径会重新搜索并挑别的资源。
     * </p>
     */
    ZOMBIE_TIMEOUT("ZOMBIE_TIMEOUT", false, "下载超时"),
    /**
     * 种子的文件列表里一个目标集都没有（季包实际只含别的段落，或包内那几集早已入库）。
     * <p>
     * <b>不可重试。</b>这不是"下载器出了状况"也不是"没做种"，而是这个包与这条订阅当前
     * 要补的集<b>确实无关</b>——判据来自下载器给出的真实文件列表，是全流程中最精确的一次。
     * 允许重试等于让同一个包被反复选中、每次都空跑一轮 30 秒轮询再中止。
     * </p>
     */
    NO_TARGET_EPISODE("NO_TARGET_EPISODE", false, "无目标集"),
    /**
     * 种子在下载器里，但迟迟解析不出文件列表（种子损坏、磁力无人做种）。
     * <p>
     * <b>不可重试。</b>多集包是以暂停态推送的，只有拿到文件列表选完目标集才会被启动——
     * 元数据都拿不到的种子连开始下载的机会都没有，再选它一次也是同样的结局。
     * 与 {@code ZOMBIE_TIMEOUT} 的区别在于那是"下载不动"，这是"根本没能开始"。
     * </p>
     */
    METADATA_TIMEOUT("METADATA_TIMEOUT", false, "种子无响应"),
    /**
     * 兜底分类：为将来的失败路径（如推送失败落记录）预留。
     * <p>
     * <b>不可重试</b>——分类未知时保持"失败即不再选"的既有行为，是最保守的默认值；
     * 新增失败路径时应显式给出自己的取值和 retryable 判断，而不是复用本项。
     * </p>
     */
    OTHER("OTHER", false, "其他原因");

    private final String value;
    private final boolean retryable;
    private final String label;

    FailReasonCode(String value, boolean retryable, String label) {
        this.value = value;
        this.retryable = retryable;
        this.label = label;
    }

    public String value() {
        return value;
    }

    /**
     * 中文短标签，供统计面板按分类聚合时展示。
     * <p>
     * 用词与前端下载记录页的 {@code failReasonCodeLabel} 保持一致——同一条失败记录在
     * 两个页面上叫不同的名字，用户会以为是两回事。
     * </p>
     */
    public String label() {
        return label;
    }

    /**
     * 按落库字符串取中文标签，写法与 {@link com.osr.openliststrm.pt.filter.RejectCode#labelOf} 一致。
     * <p>
     * 无法识别（历史数据、拼写错误）时原样返回入参而不是塌成「其他原因」：统计面板上
     * 显示一个陌生取值，比显示一个错误的分类更诚实。null/空串走 {@link #OTHER}——
     * {@code fail_reason_code} 是 20260738 才加的列，更早的失败记录该列为空。
     * </p>
     */
    public static String labelOf(String code) {
        if (code == null || code.isBlank()) {
            return OTHER.label();
        }
        for (FailReasonCode c : values()) {
            if (c.value.equals(code)) {
                return c.label();
            }
        }
        return code;
    }

    /** 这类失败是否允许该种子被重新选中 */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 按落库的字符串判断是否可重试。
     * <p>
     * {@code null}、空串、以及无法识别的取值一律返回 {@code false}：{@code fail_reason_code}
     * 列是 20260738 迁移才加的，更早的失败记录该列为空，把它们当成"可重试"会让一批陈年
     * 失败种子在升级后突然重新涌入候选池。保守判定使这些历史数据维持升级前的行为。
     * </p>
     */
    public static boolean isRetryable(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (FailReasonCode code : values()) {
            if (code.value.equals(value)) {
                return code.retryable;
            }
        }
        return false;
    }
}
