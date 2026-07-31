package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import com.osr.openliststrm.mybatisplus.handler.EncryptedStringTypeHandler;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * PT 下载器配置
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
@Getter
@Setter
@TableName(value = "pt_downloader", autoResultMap = true)
public class PtDownloaderPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 下载器展示名 */
    @TableField("name")
    private String name;

    /** 下载器类型，QBITTORRENT / TRANSMISSION */
    @TableField("type")
    private String type;

    /** 主机名或IP，不含协议与端口 */
    @TableField("host")
    private String host;

    /** 端口 */
    @TableField("port")
    private Integer port;

    /** 是否使用 https 0-否 1-是 */
    @TableField("use_https")
    private String useHttps;

    /** 用户名 */
    @TableField("username")
    private String username;

    /** 密码，落库前经 {@link EncryptedStringTypeHandler} 透明加密，业务代码全程只接触明文 */
    @TableField(value = "password", typeHandler = EncryptedStringTypeHandler.class)
    private String password;

    /** 种子保存路径 */
    @TableField("save_path")
    private String savePath;

    /** 推送时打的标签 */
    @TableField("tag")
    private String tag;

    /** 同时处于 PUSHED/DOWNLOADING 状态的最大记录数，0 表示不限 */
    @TableField("max_concurrent")
    private Integer maxConcurrent;

    /** 是否启用 0-否 1-是 */
    @TableField("enabled")
    private String enabled;

    /**
     * 拼装下载器 Web UI 基地址，如 http://192.168.1.10:8080。
     * 末尾不带斜杠。
     */
    public String baseUrl() {
        String scheme = "1".equals(useHttps) ? "https" : "http";
        return scheme + "://" + cleanHost(host) + ":" + port;
    }

    /**
     * 清洗 host 用于拼接 URL：去首尾空白、去掉误填的 http(s):// 前缀、去掉末尾斜杠。
     * 仅在拼 URL 时清洗，不改写字段本身的值。
     */
    private static String cleanHost(String rawHost) {
        if (rawHost == null) {
            return null;
        }
        String cleaned = rawHost.trim();
        if (cleaned.regionMatches(true, 0, "https://", 0, 8)) {
            cleaned = cleaned.substring(8);
        } else if (cleaned.regionMatches(true, 0, "http://", 0, 7)) {
            cleaned = cleaned.substring(7);
        }
        while (cleaned.endsWith("/")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }
}
