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
 * 通知路由：某个通知类型在某个渠道上的投递配置。
 * </p>
 * <p>
 * 注意本类不要添加 {@code isXxx()} 形式的辅助方法——Lombok 已为字段生成 {@code getXxx()}，
 * 再加同名属性的 boolean getter 会让 MyBatis 在第一条 INSERT/UPDATE 时抛
 * {@code Illegal overloaded getter method with ambiguous type}，而启动和单测都发现不了。
 * 判定方法一律命名成 {@code xxxOn()}（见 {@link #enabledOn()}）。
 * </p>
 *
 * @author Jack
 */
@Getter
@Setter
@TableName("notify_route")
public class NotifyRoutePlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 只发渠道的默认接收人 */
    public static final String SCOPE_ADMIN = "ADMIN";
    /** 只发订阅归属人，无归属时回退默认接收人 */
    public static final String SCOPE_OWNER = "OWNER";
    /** 归属人 + 默认接收人 */
    public static final String SCOPE_BOTH = "BOTH";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 通知类型，取值见 {@code NotificationType} */
    @TableField("notification_type")
    private String notificationType;

    /** 渠道标识，取值见 {@code INotifier#channelKey()} */
    @TableField("channel")
    private String channel;

    /** 0-关闭 1-开启 */
    @TableField("enabled")
    private String enabled;

    /** 收件人范围 ADMIN/OWNER/BOTH，仅对支持分人投递的渠道生效 */
    @TableField("recipient_scope")
    private String recipientScope;

    public boolean enabledOn() {
        return "1".equals(enabled);
    }
}
