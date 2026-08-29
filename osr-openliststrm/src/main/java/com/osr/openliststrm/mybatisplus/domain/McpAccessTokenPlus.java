package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * <p>
 * MCP 访问令牌。供本地助理以工具形式连接 OSR 的长期凭据。
 * </p>
 * <p>
 * <b>明文令牌不在这个实体上</b>——它只在签发接口的响应里出现一次，之后任何地方都取不回来。
 * 库里存的是 {@link #tokenHash}（SHA-256），校验走「把来的明文哈希一遍、按唯一索引查表」。
 * </p>
 *
 * @author Jack
 * @since 2026-08-29
 */
@Getter
@Setter
@TableName("mcp_access_token")
public class McpAccessTokenPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 令牌名称，用于在列表里认出这是发给哪个助理/哪台机器的 */
    @TableField("name")
    private String name;

    /** SHA-256(明文令牌) 的十六进制小写 */
    @TableField("token_hash")
    private String tokenHash;

    /** 明文的前若干位，仅供列表展示与核对 */
    @TableField("token_prefix")
    private String tokenPrefix;

    /** 令牌以这个用户的身份行动 */
    @TableField("owner_user_id")
    private Long ownerUserId;

    /** 权限档：read / write / admin，见 {@code McpScope} */
    @TableField("scope")
    private String scope;

    /** 是否启用 0-否 1-是 */
    @TableField("enabled")
    private String enabled;

    /** 过期时间，NULL 表示长期有效 */
    @TableField("expire_time")
    private Date expireTime;

    /** 最后一次成功调用的时间 */
    @TableField("last_used_time")
    private Date lastUsedTime;

    /** 备注 */
    @TableField("remark")
    private String remark;

    /**
     * 是否启用。
     * <p>
     * <b>名字刻意不叫 {@code isEnabled()}</b>：Lombok 已经为 {@code String enabled} 生成了
     * {@code getEnabled()}，再加一个返回 boolean 的 {@code isEnabled()}，MyBatis 会认为属性
     * {@code enabled} 有两个类型不一致的 getter，把它登记成 AmbiguousMethodInvoker——
     * <b>启动、装配、单测全都照过</b>，直到第一条 INSERT/UPDATE 才抛
     * {@code Illegal overloaded getter method with ambiguous type}。
     * 参考 {@code PtCleanRulePlus#enabledOn()}、{@code PtDownloaderPlus#autoDeleteOn()}。
     * </p>
     */
    public boolean enabledOn() {
        return "1".equals(enabled);
    }

    /** 是否已过期（{@code expireTime} 为 null 表示长期有效） */
    public boolean expiredAt(Date now) {
        return expireTime != null && expireTime.before(now);
    }
}
