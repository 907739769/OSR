package com.osr.openliststrm.backup;

import java.util.Arrays;
import java.util.Optional;

/**
 * 备份文件里的分区，<b>声明顺序就是恢复顺序</b>。
 * <p>
 * 顺序有依赖：删种/转移/热门自动订阅规则与订阅按名字引用下载器，得排在下载器后面，
 * 恢复时才查得到刚写进去的下载器 id；洗版规则的校验对照的是过滤规则，得排在过滤规则后面。
 *
 * @author Jack
 */
public enum BackupSection {

    SYS_CONFIG("sysConfig", "系统参数"),
    NOTIFY_ROUTE("notifyRoute", "通知路由"),
    STRM_TASK("strmTask", "STRM 任务"),
    COPY_TASK("copyTask", "同步任务"),
    RENAME_TASK("renameTask", "重命名任务"),
    RENAME_CATEGORY_RULE("renameCategoryRule", "重命名分类规则"),
    DOWNLOADER("downloader", "PT 下载器"),
    INDEXER("indexer", "PT 索引器"),
    MEDIA_SERVER("mediaServer", "媒体服务器"),
    FILTER_CONFIG("filterConfig", "过滤规则"),
    UPGRADE_CONFIG("upgradeConfig", "洗版规则"),
    CLEAN_RULE("cleanRule", "自动删种规则"),
    TRANSFER_RULE("transferRule", "转移做种规则"),
    AUTO_ADD_RULE("autoAddRule", "热门自动订阅规则"),
    TORRENT_BLACKLIST("torrentBlacklist", "种子黑名单"),
    WECOM_USER("wecomUser", "企业微信用户绑定"),
    /** 最后一个：它在后台逐条调 TMDb 重建，不进配置分区的那个事务 */
    SUBSCRIPTION("subscription", "PT 订阅");

    /** 备份文件里的键名，一旦发布就不能改，否则旧备份恢复不了 */
    private final String key;
    private final String label;

    BackupSection(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public static Optional<BackupSection> ofKey(String key) {
        return Arrays.stream(values()).filter(s -> s.key.equals(key)).findFirst();
    }
}
