package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * PT 订阅每集状态。这张表是「缺集」的唯一真相来源：
 * Emby 查询结果与下载状态都往它上面收敛，前端进度展示直接查它。
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Getter
@Setter
@TableName("pt_subscription_episode")
public class PtSubscriptionEpisodePlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 订阅ID */
    @TableField("sub_id")
    private Integer subId;

    /** 集号，电影恒为 0 */
    @TableField("episode")
    private Integer episode;

    /** 状态 MISSING/IN_FLIGHT/IN_LIBRARY/UPGRADING/BLOCKED */
    @TableField("state")
    private String state;

    /** 连续失败次数，达到阈值后状态转 BLOCKED 停止自动重试，成功入库前不清零 */
    @TableField("fail_count")
    private Integer failCount;

    /**
     * 已入库版本的质量画像快照（JSON），洗版判定的基线。
     * 见 {@link com.osr.openliststrm.pt.upgrade.QualityProfile}。
     * <p>
     * 存快照而不是每次靠 {@link #downloadId} 反查下载记录：下载记录可能被清理，
     * 而且前端集列表要展示当前质量，反查等于 N+1 次查询。
     * </p>
     */
    @TableField("quality")
    private String quality;

    /** 洗版状态，取值见 {@link com.osr.openliststrm.pt.upgrade.UpgradeState}；null 表示尚未评估 */
    @TableField("upgrade_state")
    private String upgradeState;

    /** 关联的下载记录ID */
    @TableField("download_id")
    private Integer downloadId;

    /**
     * 该集的文件是否已在下载器的真实文件列表里确认存在 0-否 1-是。
     * <p>
     * 由 {@code DownloadTrackService} 读 {@code listFiles} 时顺带落下——那一刻是全流程里
     * 唯一能确切知道「这个种子到底含哪些集」的时机，{@code reconcileClaims} 本就在算它。
     * </p>
     * <p>
     * 存在的意义是让 {@code StuckEpisodeSweepService} 分得开两种长期在途：文件压根不在种子里
     * （季包多占，该退回重搜）vs 文件已下好只是还没传上网盘（该等，重下解决不了问题，
     * 反而白费带宽并多背一份 H&R 保种义务）。为 1 时清扫只告警、永不退回。
     * </p>
     */
    @TableField("file_confirmed")
    private String fileConfirmed;
}
