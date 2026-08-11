package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.core.text.Convert;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.subscription.dto.SupplementResult;
import com.osr.openliststrm.pt.task.DownloadRecordAdminService;
import com.osr.openliststrm.pt.task.dto.BatchBlacklistResult;
import com.osr.openliststrm.pt.task.dto.BatchRetryResult;
import com.osr.openliststrm.pt.task.dto.DownloadRecordView;
import com.osr.openliststrm.req.BlacklistReq;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * PT 下载记录 REST API 控制器：只读列表 + 失败重试，不提供增删改（记录由下载追踪流程自动生成）。
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/pt-download-records")
public class PtDownloadRecordRestController extends BaseController {

    @Autowired
    private IPtDownloadRecordPlusService recordService;

    @Autowired
    private DownloadRecordAdminService adminService;

    @Autowired
    private IPtTorrentBlacklistPlusService blacklistService;

    @GetMapping({"", "/list"})
    public Result<PageResult<DownloadRecordView>> list(@RequestParam(value = "subId", required = false) Integer subId,
                                                        @RequestParam(value = "state", required = false) String state,
                                                        @RequestParam(value = "title", required = false) String title) {
        LambdaQueryWrapper<PtDownloadRecordPlus> wrapper = new LambdaQueryWrapper<>();
        if (subId != null) {
            wrapper.eq(PtDownloadRecordPlus::getSubId, subId);
        }
        if (StringUtils.isNotBlank(state)) {
            wrapper.eq(PtDownloadRecordPlus::getState, state);
        }
        if (StringUtils.isNotBlank(title)) {
            wrapper.like(PtDownloadRecordPlus::getTitle, title);
        }
        wrapper.orderByDesc(PtDownloadRecordPlus::getId);
        PageResult<PtDownloadRecordPlus> page = selectPage(recordService.getBaseMapper(), wrapper);
        return Result.success(adminService.enrich(page));
    }

    /**
     * 立即重试一条失败的下载记录：按订阅标题+季/集号重新发起一次搜索补集。
     */
    @PostMapping("/{id}/retry")
    public Result<SupplementResult> retry(@PathVariable("id") Integer id) {
        try {
            return Result.success(adminService.retry(id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量重试选中的失败下载记录，单条失败（如已被并发处理成非 FAILED）不影响其余条目。
     */
    @PostMapping("/batchRetry")
    public Result<BatchRetryResult> batchRetry(@RequestParam("ids") String ids) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要重试的下载记录");
        }
        return Result.success(adminService.retryBatch(parseIds(ids)));
    }

    /**
     * 批量拉黑选中记录对应的种子（GUID 维度）。单条已拉黑或记录不存在都只计数，不影响其余条目。
     */
    @PostMapping("/batchBlacklistGuid")
    public Result<BatchBlacklistResult> batchBlacklistGuid(@RequestParam("ids") String ids,
                                                            @RequestBody(required = false) BlacklistReq req) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要拉黑的下载记录");
        }
        return Result.success(blacklistService.blockRecordGuidBatch(parseIds(ids), req == null ? null : req.getReason()));
    }

    /**
     * 批量拉黑选中记录标题解析出的发布组。选中的记录多来自同一发布组时只会真正落库一条。
     */
    @PostMapping("/batchBlacklistReleaseGroup")
    public Result<BatchBlacklistResult> batchBlacklistReleaseGroup(@RequestParam("ids") String ids,
                                                                    @RequestBody(required = false) BlacklistReq req) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要拉黑的下载记录");
        }
        return Result.success(blacklistService.blockRecordReleaseGroupBatch(parseIds(ids), req == null ? null : req.getReason()));
    }

    private List<Integer> parseIds(String ids) {
        return Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
    }

    /**
     * 拉黑该下载记录对应的种子（GUID 维度）。记录不存在时返回错误；已拉黑过时幂等返回 false。
     */
    @PostMapping("/{id}/blacklist-guid")
    public Result<Boolean> blacklistGuid(@PathVariable("id") Integer id,
                                          @RequestBody(required = false) BlacklistReq req) {
        try {
            return Result.success(blacklistService.blockRecordGuid(id, req == null ? null : req.getReason()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 拉黑该下载记录标题解析出的发布组。标题解析不出发布组时返回错误；已拉黑过时幂等返回 false。
     */
    @PostMapping("/{id}/blacklist-release-group")
    public Result<Boolean> blacklistReleaseGroup(@PathVariable("id") Integer id,
                                                  @RequestBody(required = false) BlacklistReq req) {
        try {
            return Result.success(blacklistService.blockRecordReleaseGroup(id, req == null ? null : req.getReason()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
