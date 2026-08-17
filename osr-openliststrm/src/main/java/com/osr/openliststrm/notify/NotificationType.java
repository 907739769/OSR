package com.osr.openliststrm.notify;

/**
 * 通知类型：路由的一维。「这个类型要不要走这个渠道、发给谁」由 {@code notify_route}
 * 决定（见 {@link NotifyRouteService}），渠道实现只管「怎么发」。
 * <p>
 * 新增取值是安全的：路由行缺失按「发送」处理，用户不会因为升级而静默丢通知。
 * 但<b>拆分</b>已有取值（把一部分通知挪到新类型下）会让用户原先对旧类型的关闭设置
 * 落空，因此每次拆分都要配一条迁移，把新类型的路由行按它拆自哪个旧类型复制一份。
 * </p>
 *
 * @author Jack
 */
public enum NotificationType {

    /** 未分类的历史通知（索引器告警、复制任务失败/超时等旧调用点），过渡期默认值 */
    GENERAL("系统告警"),
    /** 订阅命中候选种子，已推送下载器 */
    SUBSCRIPTION_HIT("订阅命中"),
    /** 下载完成 */
    DOWNLOAD_COMPLETE("下载完成"),
    /** 下载失败 */
    DOWNLOAD_FAILED("下载失败"),
    /** Emby/Jellyfin 对账检测到新集数入库 */
    EMBY_LIBRARY_SYNC("媒体库入库"),
    /**
     * H&amp;R 保种状态变化：达标可安全删除、或达标前种子就消失了。
     * <p>
     * 不并进 DOWNLOAD_COMPLETE / DOWNLOAD_FAILED：那样一来关掉「下载完成」的用户
     * 就再也收不到「可以安全删种了」，而这恰恰是最该收到的一条——它直接对应
     * 一块能腾出来的磁盘，以及一份能卸下的保种义务。
     * </p>
     */
    HR_STATE("H&R 保种"),
    /**
     * 补搜连续落空。
     * <p>
     * 不并进 GENERAL：那是索引器故障、复制超时一类的系统告警。补搜落空是<b>某条订阅</b>
     * 的事，处置方向也不同（去调过滤规则或关键词），混在一起时用户想单独关掉它做不到。
     * </p>
     */
    SUBSCRIPTION_SEARCH("补搜落空"),
    /**
     * 文件已下好、却迟迟没进媒体库。
     * <p>
     * 不并进 SUBSCRIPTION_HIT（原先的归属）：内容全是「卡住了 / 退回缺失 / 已熔断」，
     * 挂在「订阅命中」下语义正好相反。也不并进 DOWNLOAD_FAILED——下载本身是成功的，
     * 卡的是上传网盘或 STRM/刮削那一段，重下解决不了问题。
     * </p>
     */
    LIBRARY_STUCK("入库卡住"),
    /**
     * 有集播出多日仍未匹配到资源。
     * <p>
     * 不并进 SUBSCRIPTION_SEARCH（「补搜落空」）：那一条只对<b>开着</b> {@code auto_search}
     * 的订阅发，而这一条要覆盖的恰恰是没开开关、压根没人在搜的那批——两者的关系不是
     * "同一件事的详略两版"，而是"一个只在有人管的时候响，一个专门管没人管的情况"。
     * 合并的话，用户关掉「补搜落空」（那类通知确实容易嫌吵）就会连带把这条也关掉，
     * 而这条正是他最需要的。
     * </p>
     * <p>
     * 也不并进 LIBRARY_STUCK：那是"文件已经下好了、卡在入库"，处置方向是去看上传链路；
     * 这条是"根本没下到"，处置方向是去看搜索链路。
     * </p>
     */
    EPISODE_OVERDUE("缺集逾期");

    private final String label;

    NotificationType(String label) {
        this.label = label;
    }

    /** 页面展示名。放在枚举上而不是前端字典：新增类型时只改一处，前端自动跟上 */
    public String getLabel() {
        return label;
    }

    /**
     * 这条通知是不是「出事了」——渠道据此提高推送优先级（Gotify 的 priority、
     * Bark 的 level），让失败类通知能在锁屏上响一下，而例行的命中/入库不打扰。
     * <p>
     * 判据放在枚举上而不是各渠道自己列举：新增类型时只需在这里表态一次，
     * 漏了的默认按「不紧急」处理——多推一次不响的通知，好过把例行消息全弄成响铃。
     * </p>
     */
    public boolean urgent() {
        return this == GENERAL || this == DOWNLOAD_FAILED || this == LIBRARY_STUCK;
    }
}
