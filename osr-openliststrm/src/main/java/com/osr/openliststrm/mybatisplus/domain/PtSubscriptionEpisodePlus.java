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

    /** 状态 MISSING/IN_FLIGHT/IN_LIBRARY/BLOCKED */
    @TableField("state")
    private String state;

    /** 连续失败次数，达到阈值后状态转 BLOCKED 停止自动重试，成功入库前不清零 */
    @TableField("fail_count")
    private Integer failCount;

    /** 已下载质量，为洗版预留 */
    @TableField("quality")
    private String quality;

    /** 关联的下载记录ID */
    @TableField("download_id")
    private Integer downloadId;
}
