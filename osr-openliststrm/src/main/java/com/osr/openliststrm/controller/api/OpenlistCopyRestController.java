package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.common.core.text.Convert;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.osr.openliststrm.service.BatchRemoveOutcome;
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
    /** 只有失败、监控超时/任务丢失的记录能重试；处理中的由监控或兜底任务收尾，已成功的无事可做 */
    private static final String NOTHING_TO_RETRY = "所选记录处于处理中或已成功，无需重试";

    @Autowired
    private ICopyService copyService;

    @Override
    protected boolean systemGeneratedRecords()
    {
        return true;
    }

    /**
     * 按状态分组计数，供页面顶部统计条使用。筛选条件与列表一致，但忽略状态这一项
     */
    @GetMapping("/stats")
    public Result<Map<String, Long>> stats(OpenlistCopyPlus query)
    {
        if (query != null)
        {
            query.setCopyStatus(null);
        }
        QueryWrapper<OpenlistCopyPlus> conditions = new QueryWrapper<>();
        applyConditions(conditions, query);
        return Result.success(RecordStatusCounts.count(service.getBaseMapper(), conditions, "copy_status"));
    }

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
    public Result<Integer> retry(@PathVariable("id") Integer id)
    {
        var record = service.getById(id);
        if (record == null)
        {
            return Result.error("记录不存在");
        }
        logger.info("重试的复制记录：{}", id);
        List<String> idList = Collections.singletonList(String.valueOf(id));
        int submitted = copyService.retryCopy(idList);
        if (submitted == 0)
        {
            return Result.error(NOTHING_TO_RETRY);
        }
        return Result.success(submitted);
    }

    /**
     * 批量重试失败的复制记录。返回实际提交的条数，选中的记录里处理中/已成功的会被跳过，
     * 前端据此告诉用户「提交了几条、跳过了几条」
     */
    @PostMapping("/retry")
    public Result<Integer> batchRetry(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要重试的记录");
        }
        List<String> idList = Arrays.stream(Convert.toStrArray(ids)).collect(Collectors.toList());
        int submitted = copyService.retryCopy(idList);
        if (submitted == 0)
        {
            return Result.error(NOTHING_TO_RETRY);
        }
        return Result.success(submitted);
    }

    /**
     * 重试全部失败与异常的记录（最多取最新 200 条），与 TG 的「重试全部失败」同一套逻辑
     */
    @PostMapping("/retry-failed")
    public Result<ICopyService.RetryOutcome> retryAllFailed()
    {
        return Result.success(copyService.retryAllFailed());
    }

    /**
     * 批量删除网盘文件（从网盘删除实际文件）
     */
    @PostMapping("/batchRemoveNetDisk")
    public Result<BatchRemoveOutcome> batchRemoveNetDisk(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要删除的记录");
        }
        List<String> idList = Arrays.stream(Convert.toStrArray(ids)).collect(Collectors.toList());
        return Result.success(copyService.batchRemoveNetDisk(idList));
    }

    @Override
    protected QueryWrapper<OpenlistCopyPlus> buildQueryWrapper(OpenlistCopyPlus openlistCopy)
    {
        QueryWrapper<OpenlistCopyPlus> wrapper = new QueryWrapper<>();
        applyConditions(wrapper, openlistCopy);
        wrapper.orderByDesc("create_time");
        return wrapper;
    }

    /**
     * 只加筛选条件、不加排序：统计接口要拿它做 GROUP BY，见 {@link RecordStatusCounts}
     */
    private void applyConditions(QueryWrapper<OpenlistCopyPlus> wrapper, OpenlistCopyPlus openlistCopy)
    {
        if (openlistCopy == null)
        {
            return;
        }
        wrapper.like(StringUtils.isNotEmpty(openlistCopy.getCopySrcPath()), "copy_src_path", openlistCopy.getCopySrcPath());
        wrapper.like(StringUtils.isNotEmpty(openlistCopy.getCopyDstPath()), "copy_dst_path", openlistCopy.getCopyDstPath());
        wrapper.like(StringUtils.isNotEmpty(openlistCopy.getCopySrcFileName()), "copy_src_file_name", openlistCopy.getCopySrcFileName());
        wrapper.like(StringUtils.isNotEmpty(openlistCopy.getCopyDstFileName()), "copy_dst_file_name", openlistCopy.getCopyDstFileName());
        wrapper.eq(StringUtils.isNotEmpty(openlistCopy.getCopyTaskId()), "copy_task_id", openlistCopy.getCopyTaskId());
        wrapper.eq(StringUtils.isNotEmpty(openlistCopy.getCopyStatus()), "copy_status", openlistCopy.getCopyStatus());
        // 开始 / 结束时间各自独立，只填一侧就是半开区间；格式不合法的一侧直接忽略
        String beginTime = QueryTimeRange.get(openlistCopy.getParams(), "beginTime");
        String endTime = QueryTimeRange.get(openlistCopy.getParams(), "endTime");
        wrapper.ge(beginTime != null, "create_time", beginTime);
        wrapper.le(endTime != null, "create_time", endTime);
    }
}
