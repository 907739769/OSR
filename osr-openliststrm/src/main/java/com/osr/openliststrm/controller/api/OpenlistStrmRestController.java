package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.core.text.Convert;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import com.osr.openliststrm.service.IStrmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * strm生成 REST API
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/strm-records")
public class OpenlistStrmRestController extends BaseController
{
    @Autowired
    private IOpenlistStrmPlusService openlistStrmPlusService;

    @Autowired
    private IStrmService strmService;

    /**
     * 查询strm生成列表 - 支持 /strm-records 和 /strm-records/list
     */
    @GetMapping({ "", "/list" })
    public Result<PageResult<OpenlistStrmPlus>> list(OpenlistStrmPlus openlistStrm)
    {
        return Result.success(selectPage(openlistStrmPlusService.getBaseMapper(), buildWrapper(openlistStrm)));
    }

    /**
     * 获取strm生成详细信息
     */
    @GetMapping("/{strmId}")
    public Result<OpenlistStrmPlus> getInfo(@PathVariable("strmId") Integer strmId)
    {
        OpenlistStrmPlus openlistStrm = openlistStrmPlusService.getById(strmId);
        return Result.success(openlistStrm);
    }

    /**
     * 新增strm生成
     */
    @PostMapping
    public Result<Void> add(@RequestBody OpenlistStrmPlus openlistStrm)
    {
        boolean result = openlistStrmPlusService.save(openlistStrm);
        return result ? Result.success() : Result.error("新增失败");
    }

    /**
     * 修改strm生成
     */
    @PutMapping
    public Result<Void> edit(@RequestBody OpenlistStrmPlus openlistStrm)
    {
        boolean result = openlistStrmPlusService.updateById(openlistStrm);
        return result ? Result.success() : Result.error("修改失败");
    }

    /**
     * 删除strm生成
     */
    @DeleteMapping("/{strmId}")
    public Result<Void> remove(@PathVariable("strmId") Integer strmId)
    {
        boolean result = openlistStrmPlusService.removeById(strmId);
        return result ? Result.success() : Result.error("删除失败");
    }

    /**
     * 批量删除strm生成
     */
    @PostMapping("/batchDelete")
    public Result<Void> batchRemove(@RequestParam("ids") String ids)
    {
        List<String> idList = Arrays.stream(Convert.toStrArray(ids)).collect(Collectors.toList());
        List<Integer> idIntList = idList.stream().map(Integer::parseInt).collect(Collectors.toList());
        boolean result = openlistStrmPlusService.removeByIds(idIntList);
        return result ? Result.success() : Result.error("批量删除失败");
    }

    /**
     * 重试strm任务
     */
    @PostMapping("/retry/{strmId}")
    public Result<Void> retry(@PathVariable("strmId") Integer strmId)
    {
        List<String> idList = Arrays.asList(String.valueOf(strmId));
        strmService.retryStrm(idList);
        return Result.success();
    }

    /**
     * 批量重试strm任务
     */
    @PostMapping("/retry")
    public Result<Void> batchRetry(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要重试的记录");
        }
        List<String> idList = Arrays.stream(Convert.toStrArray(ids)).collect(Collectors.toList());
        List<Integer> idIntList = idList.stream().map(Integer::parseInt).collect(Collectors.toList());
        strmService.retryStrm(idIntList.stream().map(String::valueOf).collect(Collectors.toList()));
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
        strmService.batchRemoveNetDisk(idList);
        return Result.success();
    }

    private com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpenlistStrmPlus> buildWrapper(OpenlistStrmPlus openlistStrm)
    {
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OpenlistStrmPlus> wrapper = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        if (openlistStrm != null)
        {
            if (StringUtils.isNotEmpty(openlistStrm.getStrmPath()))
            {
                wrapper.like("strm_path", openlistStrm.getStrmPath());
            }
            if (StringUtils.isNotEmpty(openlistStrm.getStrmFileName()))
            {
                wrapper.like("strm_file_name", openlistStrm.getStrmFileName());
            }
            if (StringUtils.isNotEmpty(openlistStrm.getStrmStatus()))
            {
                wrapper.eq("strm_status", openlistStrm.getStrmStatus());
            }
            // 开始 / 结束时间各自独立，只填一侧就是半开区间；格式不合法的一侧直接忽略
            String beginTime = QueryTimeRange.get(openlistStrm.getParams(), "beginTime");
            String endTime = QueryTimeRange.get(openlistStrm.getParams(), "endTime");
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
