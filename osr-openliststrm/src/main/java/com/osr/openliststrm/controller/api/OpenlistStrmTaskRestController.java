package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.core.text.Convert;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmTaskPlusService;
import com.osr.openliststrm.service.IStrmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * strm任务配置 REST API控制器
 *
 * @author Jack
 * @date 2025-07-18
 */
@RestController
@RequestMapping("/api/openliststrm/strm-tasks")
public class OpenlistStrmTaskRestController extends BaseController
{
    @Autowired
    private IOpenlistStrmTaskPlusService openlistStrmTaskPlusService;

    @Autowired
    private IStrmService strmService;

    /**
     * 查询strm任务配置列表（分页）- 支持 /strm-tasks 和 /strm-tasks/list
     */
    @GetMapping({ "", "/list" })
    public Result<PageResult<OpenlistStrmTaskPlus>> list(OpenlistStrmTaskPlus openlistStrmTask)
    {
        return Result.success(selectPage(openlistStrmTaskPlusService.getBaseMapper(), buildQueryWrapper(openlistStrmTask)));
    }

    /**
     * 根据ID获取strm任务配置
     */
    @GetMapping("/{id}")
    public Result<OpenlistStrmTaskPlus> getById(@PathVariable("id") Integer id)
    {
        OpenlistStrmTaskPlus task = openlistStrmTaskPlusService.getById(id);
        if (task == null)
        {
            return Result.error("任务不存在");
        }
        return Result.success(task);
    }

    /**
     * 新增strm任务配置
     */
    @PostMapping
    public Result<Void> add(@RequestBody OpenlistStrmTaskPlus openlistStrmTask)
    {
        boolean result = openlistStrmTaskPlusService.save(openlistStrmTask);
        if (result)
        {
            return Result.success();
        }
        return Result.error("新增失败");
    }

    /**
     * 修改strm任务配置
     */
    @PutMapping
    public Result<Void> edit(@RequestBody OpenlistStrmTaskPlus openlistStrmTask)
    {
        if (openlistStrmTask.getStrmTaskId() == null)
        {
            return Result.error("任务ID不能为空");
        }
        OpenlistStrmTaskPlus existing = openlistStrmTaskPlusService.getById(openlistStrmTask.getStrmTaskId());
        if (existing == null)
        {
            return Result.error("任务不存在");
        }
        boolean result = openlistStrmTaskPlusService.updateById(openlistStrmTask);
        if (result)
        {
            return Result.success();
        }
        return Result.error("修改失败");
    }

    /**
     * 删除strm任务配置
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Integer id)
    {
        OpenlistStrmTaskPlus existing = openlistStrmTaskPlusService.getById(id);
        if (existing == null)
        {
            return Result.error("任务不存在");
        }
        boolean result = openlistStrmTaskPlusService.removeById(id);
        if (result)
        {
            return Result.success();
        }
        return Result.error("删除失败");
    }

    /**
     * 批量删除strm任务配置
     */
    @PostMapping("/batchDelete")
    public Result<Void> batchDelete(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要删除的任务");
        }
        List<String> idList = Arrays.stream(Convert.toStrArray(ids)).collect(Collectors.toList());
        boolean result = openlistStrmTaskPlusService.removeByIds(idList);
        if (result)
        {
            return Result.success();
        }
        return Result.error("批量删除失败");
    }

    /**
     * 立即执行strm任务
     */
    @PostMapping("/execute/{id}")
    public Result<Void> execute(@PathVariable("id") Integer id)
    {
        OpenlistStrmTaskPlus task = openlistStrmTaskPlusService.getById(id);
        if (task == null)
        {
            return Result.error("任务不存在");
        }
        logger.info("开始执行strm任务，任务ID：{}", id);
        AsyncManager.me().execute(() -> strmService.strmDir(task.getStrmTaskPath()));
        return Result.success();
    }

    /**
     * 批量执行strm任务
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
            OpenlistStrmTaskPlus task = openlistStrmTaskPlusService.getById(id);
            if (task != null)
            {
                logger.info("开始执行strm任务，任务ID：{}", id);
                final OpenlistStrmTaskPlus taskCopy = task;
                AsyncManager.me().execute(() -> strmService.strmDir(taskCopy.getStrmTaskPath()));
            }
        }
        return Result.success();
    }

    /**
     * 构建查询条件
     */
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpenlistStrmTaskPlus> buildQueryWrapper(OpenlistStrmTaskPlus openlistStrmTask)
    {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpenlistStrmTaskPlus> wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (openlistStrmTask != null)
        {
            if (openlistStrmTask.getStrmTaskPath() != null && !openlistStrmTask.getStrmTaskPath().isEmpty())
            {
                wrapper.like(OpenlistStrmTaskPlus::getStrmTaskPath, openlistStrmTask.getStrmTaskPath());
            }
            if (openlistStrmTask.getStrmTaskStatus() != null && !openlistStrmTask.getStrmTaskStatus().isEmpty())
            {
                wrapper.eq(OpenlistStrmTaskPlus::getStrmTaskStatus, openlistStrmTask.getStrmTaskStatus());
            }
        }
        wrapper.last("ORDER BY create_time DESC");
        return wrapper;
    }
}
