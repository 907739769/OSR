package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import com.osr.openliststrm.enums.PtDownloaderRoleEnum;
import com.osr.openliststrm.mybatisplus.handler.EncryptedStringTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

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

    /** 保存路径智能分类级别，见 {@link com.osr.openliststrm.enums.PtSmartClassifyLevelEnum}，默认 NONE */
    @TableField("smart_classify_level")
    private String smartClassifyLevel;

    /** 分工，见 {@link com.osr.openliststrm.enums.PtDownloaderRoleEnum}，默认 DOWNLOAD */
    @TableField("role")
    private String role;

    /** 自动删种总开关 0-否 1-是 */
    @TableField("auto_delete_enabled")
    private String autoDeleteEnabled;

    /** 自动删种排除标签，逗号分隔；种子带其中任一标签则整组永不删除 */
    @TableField("auto_delete_exclude_tags")
    private String autoDeleteExcludeTags;

    /** 单轮最多删除多少个辅种组，0 表示不限 */
    @TableField("auto_delete_max_per_round")
    private Integer autoDeleteMaxPerRound;

    /**
     * 剩余空间告警线（GB），低于它时进首页待办并发通知；null 不告警。
     * <p>
     * 这两列是 {@code ALWAYS}：「清空」本身有含义（不告警 / 不看空间），而默认的 NOT_NULL 会让清空静默存不进去——
     * 页面提示保存成功、刷新后值又回来了。下载器只在编辑表单里整实体更新，不存在拿半个实体 updateById 的调用点。
     * </p>
     */
    @TableField(value = "free_space_warn_gb", updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal freeSpaceWarnGb;

    /** 只在剩余空间低于此值（GB）时自动删种，删够即停；null 不看空间、照旧按规则删 */
    @TableField(value = "auto_delete_free_below_gb", updateStrategy = FieldStrategy.ALWAYS)
    private BigDecimal autoDeleteFreeBelowGb;

    /**
     * 是否参与订阅下载的负载均衡。
     * <p>
     * {@code SEED_ONLY} 的下载器只接收 IYUU 转移/辅种过来的种子，绝不能被订阅推送选中。
     * </p>
     */
    public boolean participatesInDownload() {
        return PtDownloaderRoleEnum.getByCode(role) == PtDownloaderRoleEnum.DOWNLOAD;
    }

    /** 自动删种是否已开启 */
    public boolean autoDeleteOn() {
        return "1".equals(autoDeleteEnabled);
    }

    /** 告警线换算成字节；未配置或非正数返回 null（= 不告警）。不叫 getXxx，理由见 PlusEntityReflectorTest */
    public Long freeSpaceWarnBytes() {
        return gbToBytes(freeSpaceWarnGb);
    }

    /** 按空间删种的线换算成字节；未配置或非正数返回 null（= 不看空间） */
    public Long autoDeleteFreeBelowBytes() {
        return gbToBytes(autoDeleteFreeBelowGb);
    }

    private static Long gbToBytes(BigDecimal gb) {
        if (gb == null || gb.signum() <= 0) {
            return null;
        }
        return gb.multiply(BigDecimal.valueOf(1024L * 1024 * 1024)).longValue();
    }

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
