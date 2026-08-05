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
 * 企业微信成员与 OSR 用户的绑定关系。
 * 订阅归属与通知定向都靠这张表，把「企微里发消息的这个人」翻译成「OSR 里的这个账号」。
 * </p>
 *
 * @author Jack
 * @since 2026-08-05
 */
@Getter
@Setter
@TableName("wecom_user")
public class WecomUserPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 企业微信成员 UserId，在企微管理后台「通讯录」成员详情页查看 */
    @TableField("wecom_userid")
    private String wecomUserid;

    /** 绑定的 OSR 用户ID(sys_user.user_id) */
    @TableField("sys_user_id")
    private Long sysUserId;

    /** OSR 登录名冗余，仅列表展示用，不参与任何判定 */
    @TableField("sys_user_name")
    private String sysUserName;

    /** 状态 0-正常 1-停用。停用后该成员的指令被拒绝，也不再收到定向通知 */
    @TableField("status")
    private String status;

    /** 备注 */
    @TableField("remark")
    private String remark;

    /** 绑定是否可用 */
    public boolean isEnabled() {
        return !"1".equals(status);
    }
}
