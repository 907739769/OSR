package com.osr.openliststrm.mcp;

import com.osr.common.core.domain.entity.SysUser;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 一次 MCP 调用背后的身份。
 * <p>
 * 由 {@link McpAuthFilter} 在校验令牌时组装，经 servlet request attribute 交给传输层的
 * {@code contextExtractor}，最终随 {@code McpTransportContext} 到达工具处理函数。
 * </p>
 * <p>
 * <b>为什么要同时带 {@link #user} 和 {@link #userDetails}</b>：
 * {@code BaseController#isAdmin()} 有两条判据取或——{@code userId == 1}（读的是
 * {@code CurrentUserContext} 里的 SysUser）与 {@code ROLE_admin}（读的是 SecurityContext 里的
 * 权限集合）。两条的失效方式不同，OSR 刻意都留着；MCP 侧要让它<b>原样成立</b>，
 * 就得把这两样都准备好，否则「按角色而不是按 user_id 当管理员」的那个人，
 * 通过 MCP 会莫名其妙地变成普通用户。
 * </p>
 *
 * @param user       令牌归属的用户
 * @param userDetails 该用户的 Spring Security 身份（含角色权限）
 * @param scope      令牌自身的权限上限
 * @param tokenId    令牌主键，仅用于审计日志
 * @param tokenName  令牌名称，仅用于审计日志
 * @author Jack
 */
public record McpPrincipal(SysUser user,
                           UserDetails userDetails,
                           McpScope scope,
                           Integer tokenId,
                           String tokenName)
{
    /** 审计日志里用来指代这次调用发起方的说法 */
    public String describe()
    {
        return "令牌[" + tokenName + "#" + tokenId + "] 用户=" + (user != null ? user.getLoginName() : "?")
                + " 档=" + scope.code();
    }
}
