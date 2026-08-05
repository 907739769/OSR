package com.osr.openliststrm.wecom;

import com.osr.common.core.domain.entity.SysUser;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;
import com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService;
import com.osr.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 企微成员自动开号：首次发指令的成员没有 OSR 账号时，就地建一个并完成绑定。
 * <p>
 * <b>为什么要有这东西</b>：订阅归属、通知定向都以 {@code sys_user.user_id} 为主体。
 * 若坚持「先由管理员建 OSR 账号再绑定」，等于要求一群只在企微里用、永远不会登网页端的人
 * 先去后台开户，本末倒置。这里把开户变成隐式的：企微成员发第一条指令时自动完成，
 * 管理员零操作，而归属模型仍然只有一种主体，权限/隔离逻辑一行都不用改。
 * <p>
 * <b>影子账号是登不进网页端的</b>：{@code status='1'}(停用)，
 * {@link com.osr.framework.security.SecurityUserDetailsService} 会直接抛 UserBlockedException；
 * 密码另存一个随机不可解的串做第二道保险。想让某人也用网页端，管理员到用户管理里
 * 启用该账号并设密码即可，绑定关系不受影响。
 * <p>
 * <b>准入边界不在这里</b>：能给应用发消息的人本来就由企微「可见范围」限定。
 * 不想要自动开号的，把 {@code openlist.wecom.autocreate} 置 0 退回审批制。
 *
 * @author Jack
 */
@Slf4j
@Service
public class WeComUserProvisioner {

    /** sys_user.login_name 列宽 30，生成的登录名不能超 */
    private static final int LOGIN_NAME_MAX = 30;

    /** 影子账号登录名前缀，让管理员一眼看出这批账号的来源 */
    private static final String LOGIN_NAME_PREFIX = "wx_";

    @Autowired
    private ISysUserService sysUserService;

    @Autowired
    private IWecomUserPlusService wecomUserService;

    @Autowired
    private WeComApiClient apiClient;

    /**
     * 为企微成员建账号并绑定。
     * <p>
     * 整个方法在一个事务里：绑定表的唯一索引是并发下的最后一道闸，
     * 同一成员连发两条消息时后一个事务会撞索引回滚，连带把多建的 sys_user 也撤掉，
     * 不会留下没人用的孤儿账号。调用方在捕获异常后重查一次即可拿到先到者建好的绑定。
     *
     * @return 建好的绑定；建号失败返回 null（调用方据此提示用户联系管理员）
     */
    @Transactional
    public WecomUserPlus provision(String wecomUserId) {
        if (StringUtils.isBlank(wecomUserId)) {
            return null;
        }
        String displayName = resolveDisplayName(wecomUserId);
        String loginName = generateLoginName(wecomUserId);

        SysUser user = new SysUser();
        user.setLoginName(loginName);
        user.setUserName(displayName);
        // status=1(停用)：这个账号只是订阅归属的载体，不该能登录网页端
        user.setStatus("1");
        user.setDelFlag("0");
        // 登录已被 status 挡死，这里再存一个随机不可解的串，避免任何「空密码可登录」的边角情况
        user.setPassword(UUID.randomUUID().toString().replace("-", ""));
        user.setSalt(UUID.randomUUID().toString().substring(0, 20));
        user.setRemark("企业微信成员 " + wecomUserId + " 首次使用时自动创建");

        boolean created = sysUserService.registerUser(user);
        if (!created || user.getUserId() == null) {
            log.warn("为企微成员[{}]自动创建 OSR 账号失败", wecomUserId);
            return null;
        }

        WecomUserPlus bind = new WecomUserPlus();
        bind.setWecomUserid(wecomUserId);
        bind.setSysUserId(user.getUserId());
        bind.setSysUserName(loginName);
        bind.setStatus("0");
        bind.setRemark("首次使用时自动绑定");
        wecomUserService.save(bind);

        log.info("已为企微成员[{}]自动创建 OSR 账号[{}] userId={}", wecomUserId, loginName, user.getUserId());
        return bind;
    }

    /** 拿不到企微姓名就用 UserId 兜底，昵称只影响展示 */
    private String resolveDisplayName(String wecomUserId) {
        String name = null;
        try {
            name = apiClient.getMemberName(wecomUserId);
        } catch (Exception e) {
            log.debug("查询企微成员[{}]姓名异常（不影响开号）：{}", wecomUserId, e.getMessage());
        }
        String display = StringUtils.isNotBlank(name) ? name : wecomUserId;
        // user_name 列宽 30
        return StringUtils.abbreviate(display, 30);
    }

    /**
     * 生成不与现有账号冲突的登录名。
     * <p>
     * 企微 UserId 最长 64 而 login_name 只有 30，装不下时改用其 SHA-256 前缀——
     * 直接截断会让两个前缀相同的长 UserId 撞在一起。
     */
    private String generateLoginName(String wecomUserId) {
        String candidate = LOGIN_NAME_PREFIX + wecomUserId;
        if (candidate.length() > LOGIN_NAME_MAX) {
            candidate = LOGIN_NAME_PREFIX + sha256Hex(wecomUserId).substring(0, LOGIN_NAME_MAX - LOGIN_NAME_PREFIX.length());
        }
        if (sysUserService.selectUserByLoginName(candidate) == null) {
            return candidate;
        }
        // 撞名（企微 UserId 恰好与某个已有账号同名，或哈希前缀碰撞）：追加序号再试
        String base = StringUtils.abbreviate(candidate, LOGIN_NAME_MAX - 3);
        for (int i = 2; i < 100; i++) {
            String next = base + "_" + i;
            if (sysUserService.selectUserByLoginName(next) == null) {
                return next;
            }
        }
        // 100 次都撞不出空位基本不可能，兜底用随机串保证不会静默复用别人的账号
        return LOGIN_NAME_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, LOGIN_NAME_MAX - LOGIN_NAME_PREFIX.length());
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 失败", e);
        }
    }
}
