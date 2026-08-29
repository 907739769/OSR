package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mcp.McpScope;
import com.osr.openliststrm.mybatisplus.domain.McpAccessTokenPlus;
import com.osr.openliststrm.mybatisplus.mapper.McpAccessTokenPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IMcpAccessTokenPlusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

/**
 * <p>
 * MCP 访问令牌 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-08-29
 */
@Slf4j
@Service
public class McpAccessTokenPlusServiceImpl extends ServiceImpl<McpAccessTokenPlusMapper, McpAccessTokenPlus>
        implements IMcpAccessTokenPlusService {

    /** 明文令牌的固定前缀。带上它，令牌被误粘到别处时一眼能认出是什么东西 */
    public static final String TOKEN_PREFIX = "osr_mcp_";

    /** 随机部分的字节数。24 字节 → Base64URL 32 字符，熵远超暴力枚举可行范围 */
    private static final int RANDOM_BYTES = 24;

    /** 列表里展示的前缀长度（含固定前缀），够用来核对是哪一把，又不足以还原令牌 */
    private static final int DISPLAY_PREFIX_LENGTH = 14;

    /**
     * {@code last_used_time} 的写入节流：距上次记录不足这个间隔就不写。
     * <p>
     * 这一列的用途是「这把钥匙还在不在用」，精确到分钟绰绰有余；而不节流的话
     * <b>每一次 tools/call 都要多一条 UPDATE</b>，一个循环调用工具的助理能把它变成
     * 这张表上最频繁的写入。
     * </p>
     */
    private static final long TOUCH_THROTTLE_MILLIS = 60_000L;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    public IssuedToken issue(McpAccessTokenPlus draft) {
        byte[] bytes = new byte[RANDOM_BYTES];
        RANDOM.nextBytes(bytes);
        String plaintext = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        McpAccessTokenPlus record = new McpAccessTokenPlus();
        record.setName(draft.getName());
        record.setOwnerUserId(draft.getOwnerUserId());
        record.setScope(McpScope.parse(draft.getScope()).code());
        record.setExpireTime(draft.getExpireTime());
        record.setRemark(draft.getRemark());
        record.setEnabled("1");
        record.setTokenHash(sha256Hex(plaintext));
        record.setTokenPrefix(plaintext.substring(0, DISPLAY_PREFIX_LENGTH));
        save(record);

        // 只记 id 与名称，绝不记明文，也不记哈希——哈希就是校验时的比对物，
        // 写进保留 7 天的日志文件等于把校验凭据抄了一份出来
        log.info("已签发 MCP 令牌[#{}] {}，归属用户={} 权限档={}",
                record.getId(), record.getName(), record.getOwnerUserId(), record.getScope());
        return new IssuedToken(record, plaintext);
    }

    @Override
    public McpAccessTokenPlus verify(String plaintext) {
        if (StringUtils.isBlank(plaintext)) {
            return null;
        }
        McpAccessTokenPlus token = lambdaQuery()
                .eq(McpAccessTokenPlus::getTokenHash, sha256Hex(plaintext))
                .one();
        return usable(token, new Date()) ? token : null;
    }

    /**
     * 「这枚令牌现在还能用吗」。
     * <p>
     * 单独拎出来是为了让这条判据能被单测直接钉住——它是整个 MCP 层唯一的准入闸门，
     * 而把它留在 {@code verify} 里的话，要测它就得先把 MyBatis-Plus 的 {@code lambdaQuery()}
     * 链路整个 mock 出来，那种测试测的是 mock 而不是判据。
     * </p>
     * <p>
     * 三条都是「不满足即拒绝」：记录不存在、已停用、已过期。停用与过期<b>不合并</b>成一个
     * enabled 位——过期是到点自动失效，停用是人为按下的，两者都要能独立表达。
     * </p>
     */
    public static boolean usable(McpAccessTokenPlus token, Date now) {
        return token != null && token.enabledOn() && !token.expiredAt(now);
    }

    @Override
    public void touch(McpAccessTokenPlus token) {
        Date now = new Date();
        Date last = token.getLastUsedTime();
        if (last != null && now.getTime() - last.getTime() < TOUCH_THROTTLE_MILLIS) {
            return;
        }
        // 只更新这一列。不能用 updateById(token)——MyBatis-Plus 默认的 NOT_NULL 策略会把
        // 实体上所有非 null 字段一并写回，而这个实例是校验时查出来的快照；期间用户在管理页
        // 改了名字或停用了它，这里就会把那次修改静默覆盖回去（同 updateAutoSearchMissState 那条）
        lambdaUpdate()
                .set(McpAccessTokenPlus::getLastUsedTime, now)
                .eq(McpAccessTokenPlus::getId, token.getId())
                .update();
        token.setLastUsedTime(now);
    }

    /** SHA-256 十六进制小写 */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制要求实现的算法，走到这里说明运行环境已经不是标准 JVM
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
