package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;
import com.osr.openliststrm.mybatisplus.domain.RenameTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameCategoryRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService;
import com.osr.openliststrm.rename.*;
import com.osr.openliststrm.rename.config.IRenameTemplateConfigService;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.openliststrm.rename.rule.CategoryClassifier;
import com.osr.openliststrm.rename.rule.CategoryPlacement;
import com.osr.openliststrm.rename.rule.CategoryRuleConverter;
import com.osr.openliststrm.tmdb.TMDbClient;
import com.osr.openliststrm.openai.OpenAIClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 重命名任务配置 REST API控制器
 *
 * @author Jack
 * @date 2025-10-10
 */
@RestController
@RequestMapping("/api/openliststrm/rename-tasks")
public class RenameTaskRestController extends BaseCrudRestController<IRenameTaskPlusService, RenameTaskPlus>
{
    @Autowired
    private RenameTaskManager renameTaskManager;

    @Autowired
    private RenameClientProvider renameClientProvider;

    @Autowired
    private OpenlistConfig config;

    @Autowired
    private IRenameTemplateConfigService templateConfigService;

    @Autowired
    private IRenameCategoryRulePlusService categoryRuleService;

    /**
     * 批量删除重命名任务配置
     */
    @PostMapping("/batchDelete")
    public Result<Void> batchDelete(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要删除的任务");
        }
        List<String> idList = Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        boolean result = service.removeByIds(idList);
        if (result)
        {
            return Result.success();
        }
        return Result.error("批量删除失败");
    }

    /**
     * 立即执行重命名任务
     */
    @PostMapping("/execute/{id}")
    public Result<Void> execute(@PathVariable("id") Integer id)
    {
        RenameTaskPlus task = service.getById(id);
        if (task == null)
        {
            return Result.error("任务不存在");
        }
        logger.info("开始执行重命名任务，任务ID：{}", id);
        AsyncManager.me().execute(() -> renameTaskManager.executeTaskNow(id));
        return Result.success();
    }

    /**
     * 批量执行重命名任务
     */
    @PostMapping("/execute")
    public Result<Void> batchExecute(@RequestBody List<Integer> ids)
    {
        if (ids == null || ids.isEmpty())
        {
            return Result.error("请选择要执行的任务");
        }
        for (Integer id : ids)
        {
            RenameTaskPlus task = service.getById(id);
            if (task != null)
            {
                logger.info("开始执行重命名任务，任务ID：{}", id);
                final int taskId = id;
                AsyncManager.me().execute(() -> renameTaskManager.executeTaskNow(taskId));
            }
        }
        return Result.success();
    }

    /**
     * 测试重命名解析（预览）
     */
    @PostMapping("/test/{id}")
    public Result<Map<String, Object>> test(@PathVariable("id") Integer id,
                                            @RequestParam("filename") String filename,
                                            @RequestParam(value = "template", required = false) String template)
    {
        if (StringUtils.isEmpty(filename))
        {
            return Result.error("文件名不能为空");
        }

        RenameTaskPlus task = service.getById(id);
        if (task == null)
        {
            return Result.error("任务不存在");
        }

        renameClientProvider.refresh(config);
        TMDbClient tmdbClient = renameClientProvider.tmdb();
        OpenAIClient openAIClient = renameClientProvider.openAI();

        MediaParser parser = new MediaParser(tmdbClient, openAIClient);

        try
        {
            MediaInfo info = parser.parse(filename);

            String renderTemplate = StringUtils.isEmpty(template) ? templateConfigService.getTemplate() : template;
            String renamed = parser.render(info, renderTemplate);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("info", info);
            result.put("renamed", renamed);
            result.put("template", renderTemplate);
            result.putAll(placement(info, filename, renamed));
            return Result.success(result);
        }
        catch (Exception e)
        {
            return Result.error("解析失败: " + e.getMessage());
        }
    }

    /**
     * 通用测试重命名解析（不依赖具体任务）
     */
    @PostMapping("/test/parse")
    public Result<Map<String, Object>> testParse(@RequestParam("filename") String filename,
                                                 @RequestParam(value = "template", required = false) String template)
    {
        if (StringUtils.isEmpty(filename))
        {
            return Result.error("文件名不能为空");
        }

        renameClientProvider.refresh(config);
        TMDbClient tmdbClient = renameClientProvider.tmdb();
        OpenAIClient openAIClient = renameClientProvider.openAI();

        MediaParser parser = new MediaParser(tmdbClient, openAIClient);

        try
        {
            MediaInfo info = parser.parse(filename);

            String renderTemplate = StringUtils.isEmpty(template) ? templateConfigService.getTemplate() : template;
            String renamed = parser.render(info, renderTemplate);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("info", info);
            result.put("renamed", renamed);
            result.put("template", renderTemplate);
            result.putAll(placement(info, filename, renamed));
            return Result.success(result);
        }
        catch (Exception e)
        {
            return Result.error("解析失败: " + e.getMessage());
        }
    }

    /**
     * 试跑分类规则：这个文件最后会落到哪个目录。
     * <p>
     * 「重命名规则设置」页三个 tab 里有两个在配分类目录，而试跑只输出文件名的话，
     * 用户最想验证的那件事恰恰验证不了。判定与路径拼装都走真正重命名用的同一份
     * {@link CategoryClassifier} / {@link CategoryPlacement}，不在这里另写一套，
     * 否则预览出来的路径与实际落盘的路径会各自漂移——而那正是这个预览存在的全部意义。
     * <p>
     * mediaType 按有无季号推断：真正执行时它来自任务配置，测试接口拿不到。
     */
    private Map<String, Object> placement(MediaInfo info, String filename, String renamed)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        String mediaType = CategoryPlacement.inferMediaType(info.getSeason(), info.getEpisode());
        out.put("mediaType", mediaType);

        List<RenameCategoryRulePlus> rows = categoryRuleService.listEnabledRules(mediaType);
        List<CategoryRule> rules = CategoryRuleConverter.toCategoryRules(rows);
        int hit = CategoryClassifier.classifyIndex(rules, info);

        out.put("ruleCount", rows.size());
        out.put("matchedRuleSeq", hit < 0 ? null : hit + 1);
        out.put("matchedRuleFallback", hit >= 0 && "1".equals(rows.get(hit).getIsFallback()));

        String category = CategoryPlacement.categoryOrDefault(hit < 0 ? null : rules.get(hit).getName());
        out.put("category", category);

        // 与 MediaRenameProcessor#buildDestPath 一致：渲染结果为空时退回原文件名，
        // 渲染结果里的反斜杠按目录分隔符处理（模板本来就可以渲染出多级目录）
        String leaf = StringUtils.isEmpty(renamed) ? filename : renamed.trim().replace('\\', '/');
        out.put("destPath", CategoryPlacement.topLevelOf(mediaType) + "/" + category + "/" + leaf);
        return out;
    }

    /**
     * 构建查询条件
     */
    @Override
    protected com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RenameTaskPlus> buildQueryWrapper(RenameTaskPlus renameTask)
    {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RenameTaskPlus> wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (renameTask != null)
        {
            if (renameTask.getSourceFolder() != null && !renameTask.getSourceFolder().isEmpty())
            {
                wrapper.like(RenameTaskPlus::getSourceFolder, renameTask.getSourceFolder());
            }
            if (renameTask.getTargetRoot() != null && !renameTask.getTargetRoot().isEmpty())
            {
                wrapper.like(RenameTaskPlus::getTargetRoot, renameTask.getTargetRoot());
            }
            if (renameTask.getStatus() != null && !renameTask.getStatus().isEmpty())
            {
                wrapper.eq(RenameTaskPlus::getStatus, renameTask.getStatus());
            }
        }
        wrapper.last("ORDER BY create_time DESC");
        return wrapper;
    }
}
