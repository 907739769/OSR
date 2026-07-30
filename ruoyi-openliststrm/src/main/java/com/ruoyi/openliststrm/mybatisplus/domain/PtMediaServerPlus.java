package com.ruoyi.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.mybatisplus.BaseEntity;
import com.ruoyi.openliststrm.mybatisplus.handler.EncryptedStringTypeHandler;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * PT 媒体服务器配置（Emby/Jellyfin）
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
@Getter
@Setter
@TableName(value = "pt_media_server", autoResultMap = true)
public class PtMediaServerPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 媒体服务器展示名 */
    @TableField("name")
    private String name;

    /** 类型 EMBY / JELLYFIN */
    @TableField("type")
    private String type;

    /** 服务器地址，形如 http://emby:8096 */
    @TableField("url")
    private String url;

    /** API Key，落库前经 {@link EncryptedStringTypeHandler} 透明加密，业务代码全程只接触明文 */
    @TableField(value = "api_key", typeHandler = EncryptedStringTypeHandler.class)
    private String apiKey;

    /** 用户ID，可空 */
    @TableField("user_id")
    private String userId;

    /** 是否启用 0-否 1-是 */
    @TableField("enabled")
    private String enabled;

    /**
     * 归一化后的基地址，去掉末尾斜杠，避免拼接出双斜杠。
     */
    public String baseUrl() {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
