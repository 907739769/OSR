package com.osr.openliststrm.req;

import lombok.Data;

/**
 * 签发 MCP 令牌的入参。
 * <p>
 * 归属人<b>不在这里</b>：它一律取当前登录用户，不采信请求体——否则谁都能签一枚挂在别人名下的
 * 令牌，而那枚令牌能看到那个人的全部订阅（口径同 {@code SubscribeRequest#ownerUserId}）。
 * </p>
 *
 * @author Jack
 */
@Data
public class McpTokenIssueReq {

    /** 令牌名称，用于日后在列表里认出这是发给哪个助理/哪台机器的 */
    private String name;

    /** 权限档：read / write / admin，见 {@code McpScope} */
    private String scope;

    /** 有效天数；为空或 &lt;= 0 表示长期有效 */
    private Integer expireDays;

    /** 备注 */
    private String remark;
}
