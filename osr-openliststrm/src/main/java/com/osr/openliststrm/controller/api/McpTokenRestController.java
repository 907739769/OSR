package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mcp.McpScope;
import com.osr.openliststrm.mybatisplus.domain.McpAccessTokenPlus;
import com.osr.openliststrm.mybatisplus.service.IMcpAccessTokenPlusService;
import com.osr.openliststrm.req.McpTokenIssueReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 访问令牌管理。
 * <p>
 * <b>刻意不继承 {@code BaseCrudRestController}</b>：这里的「新增」不是普通的 insert——
 * 它会生成一枚明文令牌，而那个明文<b>只在本次响应里出现一次</b>，之后连管理员也取不回来。
 * 套进通用 CRUD 的 add/edit 里，就得让实体上带一个「有时有值、有时没值」的明文字段，
 * 而那个字段随时可能被某次 list 顺手带出去。
 * </p>
 * <p>
 * <b>归属隔离与订阅同一套口径</b>：管理员看全部，其余人只看自己签发的。令牌等价于一把以
 * 归属人身份行动的钥匙，能列出别人的令牌就等于知道该去停用谁的访问。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@RestController
@RequestMapping("/api/openliststrm/mcp-tokens")
public class McpTokenRestController extends BaseController {

    @Autowired
    private IMcpAccessTokenPlusService tokenService;

    /** 当前用户能否看到/操作所有令牌 */
    private boolean canAccessAll() {
        return isAdmin();
    }

    /**
     * 单条操作前的归属校验。
     * <p>
     * 令牌不存在与无权访问返回<b>同一句</b>提示，理由同订阅：区分开就等于给了一个逐个 id
     * 试探、枚举出别人签了几枚令牌的接口。
     * </p>
     */
    private <R> Result<R> denyIfInaccessible(Integer id) {
        if (canAccessAll()) {
            return null;
        }
        McpAccessTokenPlus token = tokenService.getById(id);
        Long currentUserId = getUserId();
        boolean accessible = token != null && currentUserId != null
                && currentUserId.equals(token.getOwnerUserId());
        return accessible ? null : Result.error("令牌不存在或无权访问");
    }

    /**
     * 抹掉不该出图的字段。
     * <p>
     * {@code tokenHash} 是校验时的比对物：拿到它虽然还原不出明文，但它足以在能直接写库的场景下
     * 伪造一枚等价令牌，没有任何理由让它随接口下发。
     * </p>
     */
    private void mask(McpAccessTokenPlus token) {
        token.setTokenHash(null);
    }

    @GetMapping({"", "/list"})
    public Result<PageResult<McpAccessTokenPlus>> list(McpAccessTokenPlus query) {
        LambdaQueryWrapper<McpAccessTokenPlus> wrapper = new LambdaQueryWrapper<>();
        if (!canAccessAll()) {
            Long currentUserId = getUserId();
            if (currentUserId == null) {
                // 取不到当前用户时一条都不放行。这与订阅那侧「退回公共订阅」不同：
                // 令牌没有"公共"这个概念，身份不明时正确的默认是什么都看不到
                return Result.success(PageResult.of(java.util.List.of(), 0, 1, 10));
            }
            wrapper.eq(McpAccessTokenPlus::getOwnerUserId, currentUserId);
        }
        if (StringUtils.isNotBlank(query.getName())) {
            wrapper.like(McpAccessTokenPlus::getName, query.getName());
        }
        wrapper.orderByDesc(McpAccessTokenPlus::getId);
        PageResult<McpAccessTokenPlus> page = selectPage(tokenService.getBaseMapper(), wrapper);
        if (page.getRecords() != null) {
            page.getRecords().forEach(this::mask);
        }
        return Result.success(page);
    }

    /**
     * 签发一枚新令牌。
     *
     * @return 含 {@code token} 字段的明文令牌——<b>这是它唯一一次出现</b>，前端必须提示用户当场保存
     */
    @PostMapping
    public Result<Map<String, Object>> issue(@RequestBody McpTokenIssueReq request) {
        if (StringUtils.isBlank(request.getName())) {
            return Result.error("请填写令牌名称");
        }
        Long ownerUserId = getUserId();
        if (ownerUserId == null) {
            // 令牌必须绑一个真实用户，否则它调用工具时没有身份，订阅归属判定与管理员判定
            // 全都无从谈起。走到这里说明认证链路出了问题，直接拒绝而不是签一枚没有归属的令牌
            return Result.error("无法确定当前登录用户，请重新登录后再试");
        }

        McpAccessTokenPlus draft = new McpAccessTokenPlus();
        draft.setName(request.getName().trim());
        draft.setOwnerUserId(ownerUserId);
        draft.setScope(McpScope.parse(request.getScope()).code());
        draft.setRemark(request.getRemark());
        if (request.getExpireDays() != null && request.getExpireDays() > 0) {
            draft.setExpireTime(Date.from(Instant.now().plus(request.getExpireDays(), ChronoUnit.DAYS)));
        }

        IMcpAccessTokenPlusService.IssuedToken issued = tokenService.issue(draft);
        McpAccessTokenPlus record = issued.record();
        mask(record);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("record", record);
        view.put("token", issued.plaintext());
        return Result.success(view);
    }

    /** 启用/停用。停用即刻生效——校验走的是库里的 enabled 位，没有任何缓存 */
    @PostMapping("/{id}/enabled")
    public Result<Void> setEnabled(@PathVariable("id") Integer id,
                                   @RequestParam("enabled") boolean enabled) {
        Result<Void> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        boolean updated = tokenService.lambdaUpdate()
                .set(McpAccessTokenPlus::getEnabled, enabled ? "1" : "0")
                .eq(McpAccessTokenPlus::getId, id)
                .update();
        if (updated) {
            log.info("MCP 令牌[#{}] 已{}", id, enabled ? "启用" : "停用");
        }
        return updated ? Result.success() : Result.error("操作失败");
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Integer id) {
        Result<Void> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        boolean removed = tokenService.removeById(id);
        if (removed) {
            log.info("MCP 令牌[#{}] 已删除", id);
        }
        return removed ? Result.success() : Result.error("删除失败");
    }
}
