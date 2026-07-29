package com.ruoyi.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.core.domain.Result;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtAutoAddLogPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtAutoAddRulePlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtAutoAddLogPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtAutoAddRulePlusService;
import com.ruoyi.openliststrm.pt.autoadd.AutoAddPopularService;
import com.ruoyi.openliststrm.pt.autoadd.dto.AutoAddRunResult;
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
