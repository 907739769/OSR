package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import com.osr.openliststrm.mybatisplus.handler.EncryptedStringTypeHandler;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

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

    /** 上次被业务实际访问的时间；null 表示还没用到过 */
    @TableField("last_check_time")
    private Date lastCheckTime;

    /** 上次访问结果 1-连通 0-失败；null 表示还没用到过 */
    @TableField("last_check_ok")
    private String lastCheckOk;

    /** 上次访问失败的原因，连通时清空 */
    @TableField("last_check_error")
    private String lastCheckError;

    /**
     * 归一化后的基地址，去掉末尾斜杠，避免拼接出双斜杠。
     */
    public String baseUrl() {
        String value = url == null ? "" : url.trim();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 上次业务访问是否失败了。
     * <p>
     * <b>{@code null} 不算失败</b>：那表示这台服务器还没被用到过（刚加的、或者库里一条订阅都没有），
     * 与「用过且不通」是两回事。把两者混成一个 boolean 会让一台完全正常的新服务器在页面上显示成
     * 红色的「不可用」，也会让将来按连通性决定跳不跳过的卡死清扫在新装库上整体停摆。
     * </p>
     * <p>
     * 方法名刻意不叫 {@code isLastCheckOk()}——Lombok 已经为该字段生成了 {@code getLastCheckOk()}，
     * 再加一个返回 boolean 的 {@code isXxx()} 会让 MyBatis 把属性登记成 AmbiguousMethodInvoker，
     * 而且要到第一条 INSERT/UPDATE 才抛，编译与启动全都照过。见 PlusEntityReflectorTest。
     * </p>
     */
    public boolean lastCheckFailed() {
        return "0".equals(lastCheckOk);
    }
}
