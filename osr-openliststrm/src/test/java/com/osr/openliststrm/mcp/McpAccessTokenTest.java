package com.osr.openliststrm.mcp;

import com.osr.openliststrm.mybatisplus.domain.McpAccessTokenPlus;
import com.osr.openliststrm.mybatisplus.service.impl.McpAccessTokenPlusServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 令牌的准入判据与哈希。
 * <p>
 * {@code usable} 是整个 MCP 层唯一的准入闸门：它说「能用」，随后的一切就都以令牌归属人的
 * 身份发生。四条边界（不存在、已停用、已过期、正常）各钉一条用例。
 * </p>
 *
 * @author Jack
 */
class McpAccessTokenTest {

    private McpAccessTokenPlus token(String enabled, Date expireTime) {
        McpAccessTokenPlus token = new McpAccessTokenPlus();
        token.setEnabled(enabled);
        token.setExpireTime(expireTime);
        return token;
    }

    private Date daysFromNow(int days) {
        return new Date(System.currentTimeMillis() + days * 86_400_000L);
    }

    @Test
    void 正常令牌可用() {
        assertTrue(McpAccessTokenPlusServiceImpl.usable(token("1", null), new Date()));
        assertTrue(McpAccessTokenPlusServiceImpl.usable(token("1", daysFromNow(1)), new Date()));
    }

    @Test
    void 查不到的令牌不可用() {
        assertFalse(McpAccessTokenPlusServiceImpl.usable(null, new Date()));
    }

    @Test
    void 停用的令牌立刻不可用() {
        assertFalse(McpAccessTokenPlusServiceImpl.usable(token("0", null), new Date()),
                "停用是用户按下的紧急阀门，必须即刻生效——校验没有任何缓存就是为了这个");
    }

    @Test
    void 过期的令牌不可用() {
        assertFalse(McpAccessTokenPlusServiceImpl.usable(token("1", daysFromNow(-1)), new Date()));
    }

    @Test
    void 没有过期时间表示长期有效() {
        assertFalse(token("1", null).expiredAt(new Date()));
    }

    @Test
    void 哈希是确定的且不同输入不同() {
        String a = McpAccessTokenPlusServiceImpl.sha256Hex("osr_mcp_aaa");
        assertEquals(a, McpAccessTokenPlusServiceImpl.sha256Hex("osr_mcp_aaa"));
        assertNotEquals(a, McpAccessTokenPlusServiceImpl.sha256Hex("osr_mcp_aab"));
        assertEquals(64, a.length(), "SHA-256 十六进制固定 64 字符，建表时的 char(64) 依赖这一点");
        assertTrue(a.matches("[0-9a-f]{64}"), "必须是小写十六进制，大小写混用会让唯一索引查不中");
    }

    @Test
    void 权限档解析认不出时退到最低档() {
        assertEquals(McpScope.READ, McpScope.parse(null));
        assertEquals(McpScope.READ, McpScope.parse("胡写的"));
        assertEquals(McpScope.WRITE, McpScope.parse("WRITE"), "大小写不敏感");
        assertEquals(McpScope.ADMIN, McpScope.parse(" admin "));
    }

    @Test
    void 权限档是递进关系() {
        assertTrue(McpScope.ADMIN.covers(McpScope.READ));
        assertTrue(McpScope.ADMIN.covers(McpScope.WRITE));
        assertTrue(McpScope.WRITE.covers(McpScope.READ));
        assertFalse(McpScope.WRITE.covers(McpScope.ADMIN));
        assertFalse(McpScope.READ.covers(McpScope.WRITE));
    }
}
