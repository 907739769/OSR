package com.osr.framework.security;

import jakarta.servlet.Filter;

/**
 * 业务模块自带的认证过滤器的标记接口，由 {@code SecurityConfig} 统一插进 Spring Security
 * 的过滤链（排在 {@code UsernamePasswordAuthenticationFilter} 之前）。
 * <p>
 * <b>为什么必须插进 Security 链、不能只当成一个排在 Security 之前的普通 servlet 过滤器</b>：
 * 会话策略是 {@code STATELESS}，Security 链最前面的 {@code SecurityContextHolderFilter}
 * 会用（空的）仓库内容<b>覆盖</b> SecurityContextHolder——排在它之前设置的认证信息会被原样擦掉。
 * 这个坑不报错、不告警，表现是「过滤器明明认证成功了，接口还是 401」。
 * {@code JwtAuthenticationFilter} 一直是用 {@code addFilterBefore} 插进链里的，同一个道理。
 * </p>
 * <p>
 * 之所以做成标记接口而不是让 {@code SecurityConfig} 直接引用具体过滤器：{@code SecurityConfig}
 * 在 osr-framework，而业务模块（osr-openliststrm）反过来依赖 osr-framework，直接引用会形成模块环。
 * </p>
 *
 * @author Jack
 */
public interface ModuleAuthenticationFilter extends Filter
{
}
