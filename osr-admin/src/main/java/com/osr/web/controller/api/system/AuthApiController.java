package com.osr.web.controller.api.system;

import com.osr.common.config.JwtConfigProperties;
import com.osr.common.constant.Constants;
import com.osr.common.constant.UserConstants;
import com.osr.common.core.domain.JwtTokenDto;
import com.osr.common.core.domain.Result;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.entity.SysMenu;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.utils.*;
import com.osr.framework.manager.AsyncManager;
import com.osr.framework.security.LoginAttemptService;
import com.osr.framework.manager.factory.AsyncFactory;
import com.osr.common.annotation.Anonymous;
import com.osr.system.service.ISysConfigService;
import com.osr.system.service.ISysMenuService;
import com.osr.system.service.ISysRoleService;
import com.osr.system.service.ISysUserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;

@RestController
@RequestMapping("/api/auth")
@Anonymous
@CrossOrigin(origins = "*")
public class AuthApiController extends BaseController {

    @Autowired
    private ISysConfigService sysConfigService;

    @Autowired
    private ISysRoleService roleService;

    @Autowired
    private ISysMenuService menuService;

    @Autowired
    private ISysUserService userService;

    @Autowired
    private JwtConfigProperties jwtConfig;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Autowired
    private LoginAttemptService loginAttemptService;

    private static final BCryptPasswordEncoder bcryptEncoder = new BCryptPasswordEncoder();
    private static final String SALT_CHARS = "0123456789abcdef";
    private static final SecureRandom RANDOM = new SecureRandom();

    @PostMapping("/login")
    public Result<JwtTokenDto> login(@Validated @RequestBody LoginRequest request, HttpServletResponse response) {
        String username = request.getUsername();
        String password = request.getPassword();

        // 用户名或密码为空
        if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password)) {
            return Result.error(401, "用户名或密码不能为空");
        }

        // 密码长度校验
        if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH) {
            return Result.error(401, "密码长度必须在5到20个字符之间");
        }

        // 用户名长度校验
        if (username.length() < UserConstants.USERNAME_MIN_LENGTH
                || username.length() > UserConstants.USERNAME_MAX_LENGTH) {
            return Result.error(401, "用户名长度必须在2到20个字符之间");
        }

        String ip = ServletUtils.getRequest() != null ? IpUtils.getIpAddr(ServletUtils.getRequest()) : "0.0.0.0";

        // IP黑名单校验
        String blackStr = sysConfigService.selectConfigByKey("sys.login.blackIPList");
        if (StringUtils.isNotEmpty(blackStr)) {
            if (IpUtils.isMatchedIp(blackStr, ip)) {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, "IP被禁止登录"));
                return Result.error(403, "您的IP已被禁止登录");
            }
        }

        // 失败次数锁定校验。放在查库之前：锁定期内不该再为爆破流量付出一次用户查询
        long lockSeconds = loginAttemptService.lockedSecondsRemaining(username, ip);
        if (lockSeconds > 0) {
            long lockMinutes = (lockSeconds + 59) / 60;
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, "登录失败次数过多，已临时锁定"));
            return Result.error(429, "登录失败次数过多，请 " + lockMinutes + " 分钟后再试");
        }

        // 查询用户
        SysUser user = userService.selectUserByLoginName(username);
        if (user == null) {
            // 用户不存在同样计数：只对真实账号计数，等于给攻击者一个「这个用户名存在」的探针
            loginAttemptService.recordFailure(username, ip);
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, "用户不存在"));
            return Result.error(401, "用户名或密码错误");
        }

        if ("2".equals(user.getDelFlag())) {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, "账号已被删除"));
            return Result.error(401, "用户名或密码错误");
        }

        if ("1".equals(user.getStatus())) {
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, "账号已被停用"));
            return Result.error(401, "账号已被停用");
        }

        // 密码校验
        if (!matches(user, password)) {
            loginAttemptService.recordFailure(username, ip);
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_FAIL, "密码错误"));
            return Result.error(401, "用户名或密码错误");
        }

        loginAttemptService.recordSuccess(username, ip);
        AsyncManager.me().execute(AsyncFactory.recordLogininfor(username, Constants.LOGIN_SUCCESS, MessageUtils.message("user.login.success")));
        // 更新登录信息（IP + 登录时间）
        userService.updateLoginInfo(user.getUserId(), ip, DateUtils.getNowDate());

        Map<String, Object> claims = new HashMap<>();
        claims.put("loginName", user.getLoginName());

        Set<String> roles = roleService.selectRoleKeys(user.getUserId());
        Set<String> perms = menuService.selectPermsByUserId(user.getUserId());
        claims.put("roles", new ArrayList<>(roles));
        claims.put("permissions", new ArrayList<>(perms));

        String token = jwtTokenUtil.generateToken(user.getLoginName(), user.getUserId(), claims);
        String refreshToken = jwtTokenUtil.generateRefreshToken(user.getLoginName(), user.getUserId());

        JwtTokenDto dto = new JwtTokenDto();
        dto.setToken(token);
        dto.setUserId(user.getUserId());
        dto.setLoginName(user.getLoginName());
        dto.setUserName(user.getUserName());
        Map<String, Object> permissionMap = new HashMap<>();
        permissionMap.put("roles", roles);
        permissionMap.put("permissions", perms);
        dto.setPermissions(permissionMap);
        dto.setExpireTime(jwtConfig.getExpiration() + System.currentTimeMillis());
        dto.setRefreshToken(refreshToken);
        dto.setRefreshExpireTime(jwtConfig.getRefreshExpiration() + System.currentTimeMillis());

        return Result.success(dto);
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authHeader, HttpServletResponse response) {
        // 记录登出日志（含用户信息）
        String token = stripBearer(authHeader);
        if (StringUtils.isNotEmpty(token)) {
            try {
                String loginName = jwtTokenUtil.getUsernameFromToken(token);
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(loginName, Constants.LOGOUT, "用户登出"));
                logger.info("[JWT] 用户 {} 已登出，注意：JWT 未被服务端撤销，将在过期时间后自然失效（当前无 Redis 黑名单机制）", loginName);
            } catch (Exception e) {
                logger.debug("[JWT] 登出时解析 token 失败: {}", e.getMessage());
            }
        }
        // 清除 JWT cookie
        jakarta.servlet.http.Cookie[] cookies = ServletUtils.getRequest().getCookies();
        if (cookies != null) {
            for (jakarta.servlet.http.Cookie cookie : cookies) {
                if ("token".equals(cookie.getName()) || "refreshToken".equals(cookie.getName())) {
                    cookie.setMaxAge(0);
                    cookie.setPath("/");
                    response.addCookie(cookie);
                }
            }
        }
        return Result.success();
    }

    @PostMapping("/refresh")
    public Result<JwtTokenDto> refresh(@RequestBody RefreshRequest request) {
        String refreshToken = request.getRefreshToken();
        if (StringUtils.isEmpty(refreshToken)) {
            return Result.error(401, "刷新令牌不能为空");
        }

        try {
            Jws<Claims> jws = jwtTokenUtil.parseToken(refreshToken);
            Claims claims = jws.getPayload();

            Object type = claims.get("type");
            if (!"refresh".equals(type)) {
                return Result.error(401, "无效的刷新令牌");
            }

            String loginName = jwtTokenUtil.getUsernameFromToken(refreshToken);
            Long userId = jwtTokenUtil.getUserIdFromToken(refreshToken);

            SysUser user = userService.selectUserByLoginName(loginName);
            if (user == null) {
                return Result.error(401, "用户不存在");
            }
            // 刷新令牌必须和访问令牌一起被改密作废。漏掉这里的话整条加固就等于没做：
            // 攻击者手里那对令牌中，访问令牌确实用不了了，但他拿旧的刷新令牌换一对新的即可，
            // 而新令牌的 iat 是"现在"、永远在水位线之后——改密码反而成了一次续期
            if (jwtTokenUtil.isInvalidatedByPasswordChange(refreshToken, user.getPwdUpdateDate())) {
                return Result.error(401, "密码已变更，请重新登录");
            }

            Map<String, Object> claimsMap = new HashMap<>();
            claimsMap.put("loginName", loginName);
            Set<String> roles = roleService.selectRoleKeys(userId);
            Set<String> perms = menuService.selectPermsByUserId(userId);
            claimsMap.put("roles", new ArrayList<>(roles));
            claimsMap.put("permissions", new ArrayList<>(perms));

            String newAccessToken = jwtTokenUtil.generateToken(loginName, userId, claimsMap);
            String newRefreshToken = jwtTokenUtil.generateRefreshToken(loginName, userId);

            JwtTokenDto dto = new JwtTokenDto();
            dto.setToken(newAccessToken);
            dto.setUserId(userId);
            dto.setLoginName(loginName);
            dto.setUserName(user.getUserName());
            Map<String, Object> permissionMap = new HashMap<>();
            permissionMap.put("roles", roles);
            permissionMap.put("permissions", perms);
            dto.setPermissions(permissionMap);
            dto.setExpireTime(jwtConfig.getExpiration() + System.currentTimeMillis());
            dto.setRefreshToken(newRefreshToken);
            dto.setRefreshExpireTime(jwtConfig.getRefreshExpiration() + System.currentTimeMillis());

            return Result.success(dto);
        } catch (Exception e) {
            return Result.error(401, "刷新令牌无效或已过期");
        }
    }

    @PostMapping("/register")
    public Result<Void> register(@Validated @RequestBody SysUser user) {
        if (!("true".equals(sysConfigService.selectConfigByKey("sys.account.registerUser")))) {
            return Result.error(500, "当前系统没有开启注册功能！");
        }

        String loginName = user.getLoginName();
        String password = user.getPassword();
        String msg = "";

        if (StringUtils.isEmpty(loginName)) {
            msg = "用户名不能为空";
        } else if (StringUtils.isEmpty(password)) {
            msg = "用户密码不能为空";
        } else if (password.length() < UserConstants.PASSWORD_MIN_LENGTH
                || password.length() > UserConstants.PASSWORD_MAX_LENGTH) {
            msg = "密码长度必须在5到20个字符之间";
        } else if (loginName.length() < UserConstants.USERNAME_MIN_LENGTH
                || loginName.length() > UserConstants.USERNAME_MAX_LENGTH) {
            msg = "账户长度必须在2到20个字符之间";
        } else if (!userService.checkLoginNameUnique(user)) {
            msg = "保存用户'" + loginName + "'失败，注册账号已存在";
        } else {
            user.setPwdUpdateDate(DateUtils.getNowDate());
            user.setUserName(loginName);
            // 用 BCrypt，与 changePassword 保持同一格式。
            // 这里曾经写的是 encryptPassword（MD5(loginName+password+salt)，RuoYi 遗留方案），
            // 于是每个新注册用户的密码强度都退回到那一版——而改过一次密码的老用户反倒是 BCrypt 的。
            // matches() 仍保留 MD5 分支给存量行，但不该再产生新的 MD5 行。
            // salt 照旧生成：BCrypt 自带盐、这一列对新用户已无用，但留着值比留空更省事——
            // matches() 的 MD5 兜底分支拿它去拼串，null 会在那里抛 NPE 而不是干脆地不匹配。
            user.setSalt(generateSalt());
            user.setPassword(bcryptEncoder.encode(password));
            boolean regFlag = userService.registerUser(user);
            if (!regFlag) {
                msg = "注册失败,请联系系统管理人员";
            } else {
                AsyncManager.me().execute(AsyncFactory.recordLogininfor(loginName, Constants.REGISTER, MessageUtils.message("user.register.success")));
            }
        }

        if (StringUtils.isEmpty(msg)) {
            return Result.success();
        }
        return Result.error(500, msg);
    }

    @GetMapping("/info")
    public Result<Map<String, Object>> getUserInfo(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        SysUser user = extractValidatedUser(authHeader);
        if (user == null) {
            return Result.error(401, "未登录");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("user", user);
        data.put("roles", roleService.selectRoleKeys(user.getUserId()));
        data.put("permissions", menuService.selectPermsByUserId(user.getUserId()));
        return Result.success(data);
    }

    @GetMapping("/routers")
    public Result<List<Map<String, Object>>> getRouters(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        // 顺带修掉一处：这里原先拿 username 又查一次库，查不到时直接把 null 交给
        // selectMenusByUser，NPE 会以 500 的形式返回，而真实原因是"用户不存在"
        SysUser user = extractValidatedUser(authHeader);
        if (user == null) {
            return Result.error(401, "未登录");
        }
        List<SysMenu> menus = menuService.selectMenusByUser(user);
        List<Map<String, Object>> routerList = buildMenus(menus);
        return Result.success(routerList);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> buildMenus(List<SysMenu> menus) {
        List<Map<String, Object>> routerList = new ArrayList<>();
        for (SysMenu menu : menus) {
            Map<String, Object> router = new LinkedHashMap<>();
            String path = menu.getUrl();
            if ("#".equals(path) || StringUtils.isEmpty(path)) {
                path = derivePath(menu);
            }
            router.put("path", path != null ? path : "");
            router.put("name", menu.getMenuName() != null ? menu.getMenuName() : "");
            router.put("meta", menuMeta(menu));
            router.put("hidden", "1".equals(menu.getVisible()));

            if ("M".equals(menu.getMenuType())) {
                router.put("component", "Layout");
                List<SysMenu> children = menu.getChildren();
                if (children != null && children.size() > 0) {
                    router.put("redirect", "noRedirect");
                    router.put("children", buildMenus(children));
                }
            } else if ("C".equals(menu.getMenuType())) {
                String component = menu.getComponentPath();
                router.put("component", component != null ? component : "");
                List<SysMenu> children = menu.getChildren();
                if (children != null && children.size() > 0) {
                    router.put("children", buildMenus(children));
                }
            }
            routerList.add(router);
        }
        return routerList;
    }

    private String derivePath(SysMenu menu) {
        List<SysMenu> children = menu.getChildren();
        if (children != null && !children.isEmpty()) {
            String childPath = children.get(0).getUrl();
            if (StringUtils.isNotEmpty(childPath) && !"#".equals(childPath)) {
                String parentPath = childPath.substring(0, childPath.lastIndexOf("/"));
                return StringUtils.isEmpty(parentPath) ? "/" + menu.getMenuName().toLowerCase() : parentPath;
            }
        }
        return "/" + menu.getMenuName().toLowerCase();
    }

    private Map<String, Object> menuMeta(SysMenu menu) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("title", menu.getMenuName());
        meta.put("icon", menu.getIcon() != null ? menu.getIcon() : "");
        meta.put("alwaysShow", "M".equals(menu.getMenuType()) && menu.getChildren() != null && menu.getChildren().size() > 0);
        return meta;
    }

    /**
     * 从 Authorization 头中提取并验证令牌，返回对应的用户。
     * <p>
     * 本类整体是 {@code @Anonymous}（登录/注册/刷新必须匿名可达），所以 {@code /info}、
     * {@code /routers}、{@code /changePassword} 这几个<b>需要登录</b>的端点只能自己校验令牌，
     * 走不到 {@code JwtAuthenticationFilter} 那条路——这也是改密失效那道判定必须在这里
     * <b>再写一遍</b>的原因。两处的判据共用 {@link JwtTokenUtil#isInvalidatedByPasswordChange}，
     * 不要在任一侧另写。
     * </p>
     * <p>
     * 返回 {@code SysUser} 而不是用户名：三个调用方拿到用户名后本来都要再
     * {@code selectUserByLoginName} 一次，这里查完直接给回去，数据库往返数不变。
     * </p>
     *
     * @return 通过校验的用户；令牌缺失/无效/已被改密作废/用户不存在时返回 null
     */
    private SysUser extractValidatedUser(String authHeader) {
        String token = stripBearer(authHeader);
        if (StringUtils.isEmpty(token)) {
            return null;
        }
        String username;
        try {
            username = jwtTokenUtil.getUsernameFromToken(token);
        } catch (Exception e) {
            return null;
        }
        if (StringUtils.isEmpty(username) || !jwtTokenUtil.isTokenValid(token, username)) {
            return null;
        }
        SysUser user = userService.selectUserByLoginName(username);
        if (user == null || jwtTokenUtil.isInvalidatedByPasswordChange(token, user.getPwdUpdateDate())) {
            return null;
        }
        return user;
    }

    private String stripBearer(String authHeader) {
        if (StringUtils.isNotEmpty(authHeader) && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return authHeader;
    }

    @PostMapping("/changePassword")
    public Result<Void> changePassword(@RequestBody ChangePasswordRequest request,
                                        @RequestHeader(value = "Authorization", required = false) String authHeader) {
        SysUser user = extractValidatedUser(authHeader);
        if (user == null) {
            return Result.error(401, "未登录");
        }

        if (!matches(user, request.getOldPassword())) {
            return Result.error(400, "旧密码错误");
        }

        SysUser update = new SysUser();
        update.setUserId(user.getUserId());
        update.setPassword(bcryptEncoder.encode(request.getNewPassword()));
        // 原样带回旧 salt：resetUserPwd 的 SQL 是无条件 `salt = #{salt}`，不传的话这一列被写成
        // NULL，而 matches() 的 MD5 兜底分支会拿它去拼串。当前密码已是 BCrypt、走不到那条分支，
        // 但留一个 NULL 在那儿等于给以后埋一个 NPE
        update.setSalt(user.getSalt());
        // resetUserPwd 的 SQL 里带着 `pwd_update_date = sysdate()`，
        // 改密之后此前签发的全部令牌会被 isInvalidatedByPasswordChange 判为失效——
        // 这正是"密码可能泄露了，赶紧改一个"想要的效果，也是这次改动的目的
        if (userService.resetUserPwd(update) > 0) {
            return Result.success();
        }
        return Result.error(500, "修改密码失败");
    }

    // ========== 密码工具方法 ==========

    /**
     * 密码匹配校验
     */
    private boolean matches(SysUser user, String newPassword) {
        String storedPassword = user.getPassword();
        // 支持 BCrypt 格式密码
        if (storedPassword != null && (storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$"))) {
            return bcryptEncoder.matches(newPassword, storedPassword);
        }
        // 兼容旧版 MD5+salt 格式
        return storedPassword.equals(encryptPassword(user.getLoginName(), newPassword, user.getSalt()));
    }

    /**
     * 加密密码 (MD5 + salt)
     */
    private String encryptPassword(String loginName, String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest((loginName + password + salt).getBytes());
            return bytesToHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("MD5加密失败", e);
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 生成随机盐
     */
    private String generateSalt() {
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            sb.append(SALT_CHARS.charAt(RANDOM.nextInt(SALT_CHARS.length())));
        }
        return sb.toString();
    }

    public static class ChangePasswordRequest {
        @jakarta.validation.constraints.NotBlank(message = "旧密码不能为空")
        private String oldPassword;
        @jakarta.validation.constraints.NotBlank(message = "新密码不能为空")
        private String newPassword;

        public String getOldPassword() { return oldPassword; }
        public void setOldPassword(String oldPassword) { this.oldPassword = oldPassword; }
        public String getNewPassword() { return newPassword; }
        public void setNewPassword(String newPassword) { this.newPassword = newPassword; }
    }

    public static class LoginRequest {
        @jakarta.validation.constraints.NotBlank(message = "用户名不能为空")
        private String username;
        @jakarta.validation.constraints.NotBlank(message = "密码不能为空")
        private String password;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class RefreshRequest {
        @jakarta.validation.constraints.NotBlank(message = "刷新令牌不能为空")
        private String refreshToken;

        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    }
}
