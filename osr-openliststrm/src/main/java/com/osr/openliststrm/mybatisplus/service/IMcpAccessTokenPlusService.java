package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.McpAccessTokenPlus;

/**
 * <p>
 * MCP 访问令牌 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-08-29
 */
public interface IMcpAccessTokenPlusService extends IService<McpAccessTokenPlus> {

    /**
     * 一次签发的结果：落库的记录 + <b>只在此刻存在一次</b>的明文。
     * <p>
     * 明文既不落库也不写日志，调用方把它放进响应之后就再也取不回来。
     * </p>
     */
    record IssuedToken(McpAccessTokenPlus record, String plaintext) {
    }

    /**
     * 签发一枚新令牌并落库。
     *
     * @param draft 只读取 name / ownerUserId / scope / expireTime / remark 五个字段，
     *              其余（哈希、前缀、启用位）由本方法填充
     */
    IssuedToken issue(McpAccessTokenPlus draft);

    /**
     * 校验明文令牌。
     *
     * @return 通过校验的记录；令牌不存在、已停用或已过期时返回 {@code null}
     */
    McpAccessTokenPlus verify(String plaintext);

    /**
     * 记一次使用时间。
     * <p>
     * <b>有节流</b>，不是每次调用都写库，见实现里的说明。
     * </p>
     */
    void touch(McpAccessTokenPlus token);
}
