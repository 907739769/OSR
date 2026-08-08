package com.osr.openliststrm.controller.api;

import com.osr.common.core.domain.Result;
import com.osr.common.core.text.Convert;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.osr.openliststrm.service.ICopyService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件同步复制记录 REST API控制器
 *
 * @author Jack
 * @date 2025-07-21
 */
@RestController
@RequestMapping("/api/openliststrm/copy-records")
public class OpenlistCopyRestController extends BaseCrudRestController<IOpenlistCopyPlusService, OpenlistCopyPlus>
{
    @Autowired
    private ICopyService copyService;

    /**
     * 批量删除文件同步复制记录
     */
    @PostMapping("/batchDelete")
    public Result<Void> batchDelete(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要删除的记录");
        }
        List<String> idList = Arrays.stream(Convert.toStrArray(ids)).collect(Collectors.toList());
        boolean result = service.removeByIds(idList);
        if (result)
        {
            return Result.success();
        }
        return Result.error("批量删除失败");
    }

    /**
     * 重试失败的复制记录
     */
    @PostMapping("/retry/{id}")
    public Result<Void> retry(@PathVariable("id") Integer id)
    {
        var record = service.getById(id);
        if (record == null)
        {
            return Result.error("记录不存在");
        }
        logger.info("重试的复制记录：{}", id);
        List<String> idList = Collections.singletonList(String.valueOf(id));
        copyService.retryCopy(idList);
        return Result.success();
    }

    /**
     * 批量重试失败的复制记录
     */
    @PostMapping("/retry")
    public Result<Void> batchRetry(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要重试的记录");
        }
        List<String> idList = Arrays.stream(Convert.toStrArray(ids)).collect(Collectors.toList());
        copyService.retryCopy(idList);
        return Result.success();
    }

    /**
     * 批量删除网盘文件（从网盘删除实际文件）
     */
    @PostMapping("/batchRemoveNetDisk")
    public Result<Void> batchRemoveNetDisk(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要删除的记录");
        }
        List<String> idList = Arrays.stream(Convert.toStrArray(ids)).collect(Collectors.toList());
        copyService.batchRemoveNetDisk(idList);
        return Result.success();
    }

    /**
     * 构建查询条件
     */
    @Override
    protected com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpenlistCopyPlus> buildQueryWrapper(OpenlistCopyPlus openlistCopy)
    {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpenlistCopyPlus> wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        if (openlistCopy != null)
        {
            if (openlistCopy.getCopySrcPath() != null && !openlistCopy.getCopySrcPath().isEmpty())
            {
                wrapper.like(OpenlistCopyPlus::getCopySrcPath, openlistCopy.getCopySrcPath());
            }
            if (openlistCopy.getCopyDstPath() != null && !openlistCopy.getCopyDstPath().isEmpty())
            {
                wrapper.like(OpenlistCopyPlus::getCopyDstPath, openlistCopy.getCopyDstPath());
            }
            if (openlistCopy.getCopySrcFileName() != null && !openlistCopy.getCopySrcFileName().isEmpty())
            {
                wrapper.like(OpenlistCopyPlus::getCopySrcFileName, openlistCopy.getCopySrcFileName());
            }
            if (openlistCopy.getCopyDstFileName() != null && !openlistCopy.getCopyDstFileName().isEmpty())
            {
                wrapper.like(OpenlistCopyPlus::getCopyDstFileName, openlistCopy.getCopyDstFileName());
            }
            if (openlistCopy.getCopyTaskId() != null && !openlistCopy.getCopyTaskId().isEmpty())
            {
                wrapper.eq(OpenlistCopyPlus::getCopyTaskId, openlistCopy.getCopyTaskId());
            }
            if (openlistCopy.getCopyStatus() != null && !openlistCopy.getCopyStatus().isEmpty())
            {
                wrapper.eq(OpenlistCopyPlus::getCopyStatus, openlistCopy.getCopyStatus());
            }
            // 开始 / 结束时间各自独立，只填一侧就是半开区间；格式不合法的一侧直接忽略
            String beginTime = QueryTimeRange.get(openlistCopy.getParams(), "beginTime");
            String endTime = QueryTimeRange.get(openlistCopy.getParams(), "endTime");
            if (beginTime != null)
            {
                wrapper.ge(OpenlistCopyPlus::getCreateTime, beginTime);
            }
            if (endTime != null)
            {
                wrapper.le(OpenlistCopyPlus::getCreateTime, endTime);
            }
        }
        wrapper.last("ORDER BY create_time DESC");
        return wrapper;
    }
}
