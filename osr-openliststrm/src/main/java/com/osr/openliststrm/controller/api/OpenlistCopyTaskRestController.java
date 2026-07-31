package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.core.text.Convert;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyTaskPlusService;
import com.osr.openliststrm.service.ICopyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 文件同步任务 REST API控制器
 *
 * @author Jack
 * @date 2025-07-17
 */
@RestController
@RequestMapping("/api/openliststrm/copy-tasks")
public class OpenlistCopyTaskRestController extends BaseController
{
    @Autowired
    private IOpenlistCopyTaskPlusService openlistCopyTaskPlusService;

    @Autowired
    private ICopyService copyService;

    /**
     * 查询文件同步任务列表（分页）- 支持 /copy-tasks 和 /copy-tasks/list
     */
    @GetMapping({ "", "/list" })
    public Result<PageResult<OpenlistCopyTaskPlus>> list(OpenlistCopyTaskPlus openlistCopyTask)
    {
        return Result.success(selectPage(openlistCopyTaskPlusService.getBaseMapper(), buildQueryWrapper(openlistCopyTask)));
    }

    /**
     * 根据ID获取文件同步任务
     */
    @GetMapping("/{id}")
    public Result<OpenlistCopyTaskPlus> getById(@PathVariable("id") Integer id)
    {
        OpenlistCopyTaskPlus task = openlistCopyTaskPlusService.getById(id);
        if (task == null)
        {
            return Result.error("任务不存在");
        }
        return Result.success(task);
    }

    /**
     * 新增文件同步任务
     */
    @PostMapping
    public Result<Void> add(@RequestBody OpenlistCopyTaskPlus openlistCopyTask)
    {
        boolean result = openlistCopyTaskPlusService.save(openlistCopyTask);
        if (result)
        {
            return Result.success();
        }
        return Result.error("新增失败");
    }

    /**
     * 修改文件同步任务
     */
    @PutMapping
    public Result<Void> edit(@RequestBody OpenlistCopyTaskPlus openlistCopyTask)
    {
        if (openlistCopyTask.getCopyTaskId() == null)
        {
            return Result.error("任务ID不能为空");
        }
        OpenlistCopyTaskPlus existing = openlistCopyTaskPlusService.getById(openlistCopyTask.getCopyTaskId());
        if (existing == null)
        {
            return Result.error("任务不存在");
        }
        boolean result = openlistCopyTaskPlusService.updateById(openlistCopyTask);
        if (result)
        {
            return Result.success();
        }
        return Result.error("修改失败");
    }

    /**
     * 删除文件同步任务
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Integer id)
    {
        OpenlistCopyTaskPlus existing = openlistCopyTaskPlusService.getById(id);
        if (existing == null)
        {
            return Result.error("任务不存在");
        }
        boolean result = openlistCopyTaskPlusService.removeById(id);
        if (result)
        {
            return Result.success();
        }
        return Result.error("删除失败");
    }

    /**
     * 批量删除文件同步任务
     */
    @PostMapping("/batchDelete")
    public Result<Void> batchDelete(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要删除的任务");
        }
        List<String> idList = Arrays.stream(Convert.toStrArray(ids)).collect(Collectors.toList());
        boolean result = openlistCopyTaskPlusService.removeByIds(idList);
        if (result)
        {
            return Result.success();
        }
        return Result.error("批量删除失败");
    }

    /**
     * 立即执行文件同步任务
     */
    @PostMapping("/execute/{id}")
    public Result<Void> execute(@PathVariable("id") Integer id)
    {
        OpenlistCopyTaskPlus task = openlistCopyTaskPlusService.getById(id);
        if (task == null)
        {
            return Result.error("任务不存在");
        }
        logger.info("开始执行复制任务，任务ID：{}", id);
        AsyncManager.me().execute(() -> copyService.syncFiles(task.getCopyTaskSrc(), task.getCopyTaskDst()));
        return Result.success();
    }

    /**
     * 批量执行文件同步任务
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
            OpenlistCopyTaskPlus task = openlistCopyTaskPlusService.getById(id);
            if (task != null)
            {
                logger.info("开始执行复制任务，任务ID：{}", id);
                final OpenlistCopyTaskPlus taskCopy = task;
                AsyncManager.me().execute(() -> copyService.syncFiles(taskCopy.getCopyTaskSrc(), taskCopy.getCopyTaskDst()));
            }
        }
        return Result.success();
    }

    /**
     * 构建查询条件
     */
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpenlistCopyTaskPlus> buildQueryWrapper(OpenlistCopyTaskPlus openlistCopyTask)
    {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpenlistCopyTaskPlus> wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (openlistCopyTask != null)
        {
            if (openlistCopyTask.getCopyTaskSrc() != null && !openlistCopyTask.getCopyTaskSrc().isEmpty())
            {
                wrapper.like(OpenlistCopyTaskPlus::getCopyTaskSrc, openlistCopyTask.getCopyTaskSrc());
            }
            if (openlistCopyTask.getCopyTaskDst() != null && !openlistCopyTask.getCopyTaskDst().isEmpty())
            {
                wrapper.like(OpenlistCopyTaskPlus::getCopyTaskDst, openlistCopyTask.getCopyTaskDst());
            }
            if (openlistCopyTask.getCopyTaskStatus() != null && !openlistCopyTask.getCopyTaskStatus().isEmpty())
            {
                wrapper.eq(OpenlistCopyTaskPlus::getCopyTaskStatus, openlistCopyTask.getCopyTaskStatus());
            }
        }
        wrapper.last("ORDER BY create_time DESC");
        return wrapper;
    }
}
