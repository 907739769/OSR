package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;
import com.osr.openliststrm.mybatisplus.service.IRenameCategoryRulePlusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 重命名分类规则配置 REST API 控制器
 *
 * @author Jack
 * @date 2026-07-20
 */
@RestController
@RequestMapping("/api/openliststrm/rename-category-rules")
public class RenameCategoryRuleRestController {

    @Autowired
    private IRenameCategoryRulePlusService categoryRuleService;

    /**
     * 查询某个 media_type 下的当前规则列表（按 seq 升序）
     */
    @GetMapping
    public Result<List<RenameCategoryRulePlus>> list(@RequestParam("mediaType") String mediaType) {
        if (StringUtils.isEmpty(mediaType)) {
            return Result.error("mediaType不能为空");
        }
        return Result.success(categoryRuleService.listEnabledRules(mediaType));
    }

    /**
     * 整体替换某个 media_type 下的全部规则（前端一次性提交整份有序列表）
     */
    @PutMapping("/{mediaType}")
    public Result<Void> replaceAll(@PathVariable("mediaType") String mediaType, @RequestBody List<RenameCategoryRulePlus> rules) {
        try {
            categoryRuleService.replaceAll(mediaType, rules);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
