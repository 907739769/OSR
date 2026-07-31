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
 * PT 种子/发布组手动黑名单
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Getter
@Setter
@TableName("pt_torrent_blacklist")
public class PtTorrentBlacklistPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 拉黑类型：按 GUID 精确拉黑单个种子 */
    public static final String TYPE_GUID = "GUID";
    /** 拉黑类型：按发布组整体拉黑 */
    public static final String TYPE_RELEASE_GROUP = "RELEASE_GROUP";

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 拉黑类型：GUID / RELEASE_GROUP */
    @TableField("type")
    private String type;

    /** 匹配键：GUID 类型存 guid 的 SHA-256 哈希，RELEASE_GROUP 类型存归一化(大写)的发布组名 */
    @TableField("value")
    private String value;

    /** 展示用原文，仅供管理页展示，不参与匹配 */
    @TableField("display_value")
    private String displayValue;

    /** 拉黑原因 */
    @TableField("reason")
    private String reason;
}
