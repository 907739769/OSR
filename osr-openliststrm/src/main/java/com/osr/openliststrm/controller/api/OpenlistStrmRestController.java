package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.core.text.Convert;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.helper.MediaExtensionProvider;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import com.osr.openliststrm.service.BatchRemoveOutcome;
import com.osr.openliststrm.service.IStrmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * strm生成 REST API
 * <p>
 * 生成记录由系统写入，<b>刻意不提供新增 / 修改 / 单条删除接口</b>。此前这里有一套从模板生成的
 * {@code POST} / {@code PUT} / {@code DELETE /{id}}，前端一处都没用到，而任何登录用户都能调用——
 * 能伪造一条「成功」记录让同路径的文件从此被当成已处理、再也不生成 .strm。删除记录走 {@code batchDelete}。
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/strm-records")
public class OpenlistStrmRestController extends BaseController
{
    /** 按文件类型筛选的取值 */
    static final String FILE_TYPE_VIDEO = "video";
    static final String FILE_TYPE_SUBTITLE = "subtitle";

    @Autowired
    private IOpenlistStrmPlusService openlistStrmPlusService;

    @Autowired
    private IStrmService strmService;

    @Autowired
    private MediaExtensionProvider mediaExtensions;

    /**
     * 查询strm生成列表 - 支持 /strm-records 和 /strm-records/list
     *
     * @param fileType video / subtitle，按当前的视频/字幕扩展名配置筛选；不传为全部
     */
    @GetMapping({ "", "/list" })
    public Result<PageResult<OpenlistStrmPlus>> list(OpenlistStrmPlus openlistStrm,
                                                     @RequestParam(value = "fileType", required = false) String fileType)
    {
        QueryWrapper<OpenlistStrmPlus> wrapper = new QueryWrapper<>();
        applyConditions(wrapper, openlistStrm, fileType);
        wrapper.orderByDesc("create_time");
        return Result.success(selectPage(openlistStrmPlusService.getBaseMapper(), wrapper));
    }

    /** MCP 工具层按实体筛选时用的重载，不带文件类型 */
    public Result<PageResult<OpenlistStrmPlus>> list(OpenlistStrmPlus openlistStrm)
    {
        return list(openlistStrm, null);
    }

    /**
     * 按状态分组计数，供页面顶部统计条使用。筛选条件与列表一致，但忽略状态这一项
     */
    @GetMapping("/stats")
    public Result<Map<String, Long>> stats(OpenlistStrmPlus query,
                                           @RequestParam(value = "fileType", required = false) String fileType)
    {
        if (query != null)
        {
            query.setStrmStatus(null);
        }
        QueryWrapper<OpenlistStrmPlus> conditions = new QueryWrapper<>();
        applyConditions(conditions, query, fileType);
        return Result.success(RecordStatusCounts.count(openlistStrmPlusService.getBaseMapper(), conditions, "strm_status"));
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
     * 重试全部失败的记录（最多取最新 200 条），与 TG 的「重试全部失败」同一套逻辑
     */
    @PostMapping("/retry-failed")
    public Result<IStrmService.RetryOutcome> retryAllFailed()
    {
        return Result.success(strmService.retryAllFailed());
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
        return Result.success(strmService.batchRemoveNetDisk(idList));
    }

    /**
     * 只加筛选条件、不加排序：统计接口要拿它做 GROUP BY，见 {@link RecordStatusCounts}
     */
    void applyConditions(QueryWrapper<OpenlistStrmPlus> wrapper, OpenlistStrmPlus openlistStrm, String fileType)
    {
        if (openlistStrm != null)
        {
            wrapper.like(StringUtils.isNotEmpty(openlistStrm.getStrmPath()), "strm_path", openlistStrm.getStrmPath());
            wrapper.like(StringUtils.isNotEmpty(openlistStrm.getStrmFileName()), "strm_file_name", openlistStrm.getStrmFileName());
            wrapper.eq(StringUtils.isNotEmpty(openlistStrm.getStrmStatus()), "strm_status", openlistStrm.getStrmStatus());
            // 开始 / 结束时间各自独立，只填一侧就是半开区间；格式不合法的一侧直接忽略
            String beginTime = QueryTimeRange.get(openlistStrm.getParams(), "beginTime");
            String endTime = QueryTimeRange.get(openlistStrm.getParams(), "endTime");
            wrapper.ge(beginTime != null, "create_time", beginTime);
            wrapper.le(endTime != null, "create_time", endTime);
        }
        Set<String> extensions = FILE_TYPE_VIDEO.equals(fileType) ? mediaExtensions.videoExtensions()
                : FILE_TYPE_SUBTITLE.equals(fileType) ? mediaExtensions.subtitleExtensions()
                : null;
        if (extensions == null)
        {
            return;
        }
        if (extensions.isEmpty())
        {
            // 扩展名配置为空：没有任何文件属于这一类。不能跳过条件——那会把「筛视频」变成「全部」
            wrapper.apply("1 = 0");
            return;
        }
        // 按扩展名后缀匹配，列的排序规则大小写不敏感，.MKV 与 .mkv 一并命中
        wrapper.and(w -> extensions.forEach(ext -> w.or().likeLeft("strm_file_name", "." + ext)));
    }
}
