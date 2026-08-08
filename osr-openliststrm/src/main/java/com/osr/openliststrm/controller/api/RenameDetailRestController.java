package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.rename.RenameTaskManager;
import com.osr.openliststrm.rename.cleanup.RenameCleanupService;
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

    @Autowired
    private RenameCleanupService cleanupService;

    /**
     * 批量删除重命名明细（只删数据库记录，磁盘上的产物原样保留）。
     * <p>
     * 这是"失忆"操作，前端必须把后果讲清楚：删完之后一致性检查会失去这条记录这个入口、
     * 刮削共享文件的兄弟计数会失真、手动执行任务时源文件会被当成没处理过而重新复制一份。
     * 多数场景该用 {@link #purge} 而不是它。
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
     * 预览清理：返回这批记录名下磁盘上真实存在、将被删除的文件清单。只读。
     */
    @PostMapping("/purge/preview")
    public Result<List<String>> purgePreview(@RequestParam("ids") String ids)
    {
        List<RenameDetailPlus> details = loadDetails(ids);
        if (details.isEmpty())
        {
            return Result.success(List.of());
        }
        return Result.success(cleanupService.preview(details));
    }

    /**
     * 清理重命名产物：删目标库里的主文件（STRM / 视频副本）+ 刮削文件 + 回收空目录，
     * 可选连数据库记录一起删。
     * <p>
     * 只动目标库副本，不碰源文件——源目录是网盘挂载或下载器保种目录。
     *
     * @param deleteRecord 是否连 rename_detail 行一起删；false 时记录保留，
     *                     一致性检查下一轮会把它标成 local_missing，仍在用户视野里
     */
    @PostMapping("/purge")
    public Result<String> purge(@RequestParam("ids") String ids,
                                @RequestParam(value = "deleteRecord", defaultValue = "true") boolean deleteRecord)
    {
        List<RenameDetailPlus> details = loadDetails(ids);
        if (details.isEmpty())
        {
            return Result.error("请选择要清理的记录");
        }
        logger.info("开始清理重命名产物，记录数：{}，同时删除记录：{}", details.size(), deleteRecord);
        RenameCleanupService.PurgeResult result = cleanupService.purge(details, deleteRecord);
        return Result.success(String.format("已删除 主文件 %d、刮削文件 %d，回收空目录 %d，删除记录 %d",
                result.mainFiles(), result.scrapeFiles(), result.dirs(), result.records()));
    }

    private List<RenameDetailPlus> loadDetails(String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return List.of();
        }
        List<Integer> idList = Arrays.stream(ids.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> {
                    try
                    {
                        return Integer.valueOf(s);
                    }
                    catch (NumberFormatException e)
                    {
                        logger.warn("忽略非法的记录 ID：{}", s);
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (idList.isEmpty())
        {
            return List.of();
        }
        return service.listByIds(idList);
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
            // 开始 / 结束时间各自独立，只填一侧就是半开区间；格式不合法的一侧直接忽略
            String beginTime = QueryTimeRange.get(renameDetail.getParams(), "beginTime");
            String endTime = QueryTimeRange.get(renameDetail.getParams(), "endTime");
            if (beginTime != null)
            {
                wrapper.ge("create_time", beginTime);
            }
            if (endTime != null)
            {
                wrapper.le("create_time", endTime);
            }
        }
        wrapper.orderByDesc("create_time");
        return wrapper;
    }
}
