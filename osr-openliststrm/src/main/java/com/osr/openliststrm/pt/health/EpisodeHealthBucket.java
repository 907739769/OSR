package com.osr.openliststrm.pt.health;

/**
 * 缺集体检的分档。一集只属于一档，判定顺序见 {@code EpisodeHealthService#bucketOf}。
 * <p>
 * 分档的意义在于「处置方向」而不是「严重程度」：{@link #OVERDUE_MISSING} 要去看搜索链路，
 * {@link #OVERDUE_IN_FLIGHT} 要去看下载/上传链路，{@link #BLOCKED} 要人工介入，
 * {@link #NO_AIR_DATE} 则根本谈不上「逾期」——把它们混成一个「有问题的集」列表，
 * 用户看到的是一堆无从下手的条目。
 * </p>
 *
 * @author Jack
 */
public enum EpisodeHealthBucket {

    /**
     * 已播出超过阈值天数、仍未匹配到任何资源。
     * <p>
     * 这是体检真正要暴露的那一档：{@code StuckEpisodeSweepService} 管的是「下完了没入库」，
     * {@code AutoSearchService} 的落空通知只对<b>开着补搜</b>的订阅发——而开关默认是关的。
     * 于是「订阅建完就没人管、集一直缺着」这个最常见的场景在改造前一条提醒都没有。
     * </p>
     */
    OVERDUE_MISSING("逾期缺失"),

    /**
     * 已播出超过阈值天数、状态仍是在途（含洗版）。
     * <p>
     * 与 OVERDUE_MISSING 分开是因为处置完全不同：这批已经推给下载器了，用户要去看的是
     * 下载器和上传链路，再补搜一次不解决任何问题。这一档已有 {@code LIBRARY_STUCK} 通知覆盖
     * （见 {@code StuckEpisodeSweepService}），体检只展示、不重复发通知。
     * </p>
     */
    OVERDUE_IN_FLIGHT("在途逾期"),

    /** 连续失败达到熔断阈值，已停止自动重试，需人工处理 */
    BLOCKED("已熔断"),

    /**
     * 没有播出日期，因此算不出逾期天数。
     * <p>
     * 不能并进 {@link #OVERDUE_MISSING}：{@code air_date} 为 NULL 的成因有三种——未定档、
     * TMDb 未录入、存量行还没被 {@code EpisodeAirDateSyncTask} 扫到，把它们一律当成
     * 「逾期无穷天」会在升级后的第一天刷出一整屏假告警。电影订阅压根不参与日期同步，
     * 它的缺失也恒落在这一档。
     * </p>
     */
    NO_AIR_DATE("无播出日期");

    private final String label;

    EpisodeHealthBucket(String label) {
        this.label = label;
    }

    /** 页面展示名。放在枚举上而不是前端字典：新增分档时只改一处 */
    public String getLabel() {
        return label;
    }
}
