package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddLogPlus;
import com.osr.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddLogPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtAutoAddRulePlusService;
import com.osr.openliststrm.pt.autoadd.AutoAddPopularService;
import com.osr.openliststrm.pt.autoadd.dto.AutoAddRunResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 热门自动订阅规则 REST API 控制器
 *
 * @author Jack
 * @date 2026-07-29
 */
@RestController
@RequestMapping("/api/openliststrm/pt-auto-add-rules")
public class PtAutoAddRuleRestController extends BaseCrudRestController<IPtAutoAddRulePlusService, PtAutoAddRulePlus> {

    @Autowired
    private AutoAddPopularService autoAddPopularService;

    @Autowired
    private IPtAutoAddLogPlusService logService;

    /**
     * 写操作限管理员。
     * <p>
     * 规则上的 {@code source_url} 与全局 RSSHub 地址一样是<b>用户可填的任意 URL，而后端会去 GET 它</b>——
     * 不限权等于给任何一个登录用户一个「让服务端往任意地址发请求」的入口（内网探测），
     * 与索引器/下载器的 {@code /test} 端点是同一类风险。读操作不拦：列表页要给所有人渲染。
     * </p>
     */
    @Override
    protected boolean adminOnlyWrite() {
        return true;
    }

    @Override
    protected Wrapper<PtAutoAddRulePlus> buildQueryWrapper(PtAutoAddRulePlus entity) {
        LambdaQueryWrapper<PtAutoAddRulePlus> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(entity.getName())) {
            wrapper.like(PtAutoAddRulePlus::getName, entity.getName());
        }
        if (StringUtils.isNotBlank(entity.getMediaType())) {
            wrapper.eq(PtAutoAddRulePlus::getMediaType, entity.getMediaType());
        }
        if (StringUtils.isNotBlank(entity.getEnabled())) {
            wrapper.eq(PtAutoAddRulePlus::getEnabled, entity.getEnabled());
        }
        wrapper.orderByDesc(PtAutoAddRulePlus::getId);
        return wrapper;
    }

    /**
     * 立即执行一次该规则，不受 interval_hours 到期限制。
     */
    @PostMapping("/{id}/run")
    public Result<AutoAddRunResult> run(@PathVariable("id") Integer id) {
        // 这个端点会按规则里的地址真的发出站请求、并真的建订阅推给下载器，
        // 与改规则本身同级，一并限管理员（继承来的三个写端点靠 adminOnlyWrite 拦，这个不经过那条路径）
        Result<AutoAddRunResult> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        PtAutoAddRulePlus rule = service.getById(id);
        if (rule == null) {
            return Result.error("规则不存在");
        }
        return Result.success(autoAddPopularService.runRule(rule));
    }

    /**
     * 查该规则最近的执行日志，按 id 倒序，最多取 100 条。
     */
    @GetMapping("/{id}/logs")
    public Result<List<PtAutoAddLogPlus>> logs(@PathVariable("id") Integer id) {
        List<PtAutoAddLogPlus> logs = logService.list(new LambdaQueryWrapper<PtAutoAddLogPlus>()
                .eq(PtAutoAddLogPlus::getRuleId, id)
                .orderByDesc(PtAutoAddLogPlus::getId)
                .last("limit 100"));
        return Result.success(logs);
    }
}
