package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.rename.RenameTaskManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 重命名明细 REST API控制器
 *
 * @author Jack
 * @date 2025-09-30
 */
@RestController
@RequestMapping("/api/openliststrm/rename-details")
public class RenameDetailRestController extends BaseCrudRestController<IRenameDetailPlusService, RenameDetailPlus>
{
    @Autowired
    private RenameTaskManager renameTaskManager;

    /**
     * 批量删除重命名明细
     */
    @PostMapping("/batchDelete")
    public Result<Void> batchDelete(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要删除的重命名明细");
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
     * 执行重命名明细
     */
    @PostMapping("/execute/{id}")
    public Result<Void> execute(@PathVariable("id") Integer id,
                                @RequestParam(value = "title", required = false) String title,
                                @RequestParam(value = "year", required = false) String year,
                                @RequestParam(value = "season", required = false) String season,
                                @RequestParam(value = "episode", required = false) String episode)
    {
        if (id == null)
        {
            return Result.error("id 为空");
        }
        logger.info("开始执行重命名明细，ID：{}，title={}，year={}，season={}，episode={}", id, title, year, season, episode);
        AsyncManager.me().execute(() -> renameTaskManager.executeRenameDetails(id, title, year, season, episode));
        return Result.success();
    }

    /**
     * 批量执行重命名明细
     */
    @PostMapping("/execute")
    public Result<Void> batchExecute(@RequestParam("ids") String ids,
                                     @RequestParam(value = "title", required = false) String title,
                                     @RequestParam(value = "year", required = false) String year,
                                     @RequestParam(value = "season", required = false) String season,
                                     @RequestParam(value = "episode", required = false) String episode)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要执行的记录");
        }
        List<Integer> idList = Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Integer::parseInt).collect(Collectors.toList());
        for (Integer id : idList)
        {
            logger.info("开始执行重命名明细，ID：{}，title={}，year={}，season={}，episode={}", id, title, year, season, episode);
            final int detailId = id;
            final String t = title;
            final String y = year;
            final String s = season;
            final String e = episode;
            AsyncManager.me().execute(() -> renameTaskManager.executeRenameDetails(detailId, t, y, s, e));
        }
        return Result.success();
    }

    /**
     * 构建查询条件
     */
    @Override
    protected com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RenameDetailPlus> buildQueryWrapper(RenameDetailPlus renameDetail)
    {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RenameDetailPlus> wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        if (renameDetail != null)
        {
            if (StringUtils.isNotEmpty(renameDetail.getOriginalPath()))
            {
                wrapper.like("original_path", renameDetail.getOriginalPath());
            }
            if (StringUtils.isNotEmpty(renameDetail.getOriginalName()))
            {
                wrapper.like("original_name", renameDetail.getOriginalName());
            }
            if (StringUtils.isNotEmpty(renameDetail.getNewPath()))
            {
                wrapper.like("new_path", renameDetail.getNewPath());
            }
            if (StringUtils.isNotEmpty(renameDetail.getNewName()))
            {
                wrapper.like("new_name", renameDetail.getNewName());
            }
            if (StringUtils.isNotEmpty(renameDetail.getMediaType()))
            {
                wrapper.eq("media_type", renameDetail.getMediaType());
            }
            if (StringUtils.isNotEmpty(renameDetail.getTitle()))
            {
                wrapper.like("title", renameDetail.getTitle());
            }
            if (StringUtils.isNotEmpty(renameDetail.getYear()))
            {
                wrapper.eq("year", renameDetail.getYear());
            }
            if (StringUtils.isNotEmpty(renameDetail.getStatus()))
            {
                wrapper.eq("status", renameDetail.getStatus());
            }
            if (renameDetail.getParams() != null)
            {
                String beginTime = (String) renameDetail.getParams().get("beginTime");
                String endTime = (String) renameDetail.getParams().get("endTime");
                if (StringUtils.isNotEmpty(beginTime))
                {
                    wrapper.ge("create_time", beginTime);
                }
                if (StringUtils.isNotEmpty(endTime))
                {
                    wrapper.le("create_time", endTime);
                }
            }
        }
        wrapper.orderByDesc("create_time");
        return wrapper;
    }
}
