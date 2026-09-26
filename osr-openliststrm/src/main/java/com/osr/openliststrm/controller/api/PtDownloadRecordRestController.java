package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.core.text.Convert;
import com.osr.common.utils.StringUtils;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.osr.openliststrm.pt.stats.PtStatsScope;
import com.osr.openliststrm.pt.subscription.dto.SupplementResult;
import com.osr.openliststrm.pt.task.DownloadRecordAdminService;
import com.osr.openliststrm.pt.task.DownloadRecordState;
import com.osr.openliststrm.pt.task.UnresolvedFailureSql;
import com.osr.openliststrm.pt.task.dto.BatchBlacklistResult;
import com.osr.openliststrm.pt.task.dto.BatchRetryResult;
import com.osr.openliststrm.pt.task.dto.DownloadRecordView;
import com.osr.openliststrm.pt.ws.PtStatusWebSocket;
import com.osr.openliststrm.req.BlacklistReq;
import com.osr.openliststrm.req.PtDownloadRecordQueryReq;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PT 下载记录 REST API 控制器：只读列表 + 失败重试 + 拉黑 + 按规则清理旧记录，
 * 不提供单条增删改（记录由下载追踪流程自动生成）。
 * <p>
 * <b>全部端点按订阅归属隔离</b>：下载记录没有自己的归属列，跟着订阅走，判据与订阅页、统计面板同一条
 * （见 {@link DownloadRecordAdminService#canAccess}）。此前这里裸奔——任何登录用户都能列出全站的
 * 下载记录（别人订阅的剧名、种子标题），也能对别人的记录发起重试和拉黑。
 * </p>
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/pt-download-records")
public class PtDownloadRecordRestController extends BaseController {

    /** 与单条拉黑 / 重试的「不存在」同一句：区分开就等于给了一个逐个 id 探测别人记录的接口 */
    private static final String DENIED = "下载记录不存在或无权访问";

    @Autowired
    private IPtDownloadRecordPlusService recordService;

    @Autowired
    private DownloadRecordAdminService adminService;

    @Autowired
    private IPtTorrentBlacklistPlusService blacklistService;

    @GetMapping({"", "/list"})
    public Result<PageResult<DownloadRecordView>> list(PtDownloadRecordQueryReq query) {
        QueryWrapper<PtDownloadRecordPlus> wrapper = new QueryWrapper<>();
        applyConditions(wrapper, query, true);
        wrapper.orderByDesc("id");
        PageResult<PtDownloadRecordPlus> page = selectPage(recordService.getBaseMapper(), wrapper);
        return Result.success(adminService.enrich(page));
    }

    /**
     * 顶部统计条：当前筛选条件下按状态分组计数，忽略状态这一项（点「失败 17」筛出来一定是 17 条）。
     * wrapper 只带筛选不带排序，原因见 {@link RecordStatusCounts}。
     */
    @GetMapping("/stats")
    public Result<Map<String, Long>> stats(PtDownloadRecordQueryReq query) {
        QueryWrapper<PtDownloadRecordPlus> conditions = new QueryWrapper<>();
        applyConditions(conditions, query, false);
        return Result.success(RecordStatusCounts.count(recordService.getBaseMapper(), conditions, "state"));
    }

    private void applyConditions(QueryWrapper<PtDownloadRecordPlus> wrapper, PtDownloadRecordQueryReq query,
                                 boolean withState) {
        String visibleSubs = scope().visibleSubIdSql();
        if (visibleSubs != null) {
            // 子查询而不是先查 id 列表：与统计面板同一种写法，订阅多时不会拼出上百个参数
            wrapper.inSql("sub_id", visibleSubs);
        }
        if (query == null) {
            return;
        }
        wrapper.eq(query.getSubId() != null, "sub_id", query.getSubId());
        wrapper.eq(withState && StringUtils.isNotBlank(query.getState()), "state", query.getState());
        wrapper.like(StringUtils.isNotBlank(query.getTitle()), "title", query.getTitle());
        wrapper.eq(StringUtils.isNotBlank(query.getFailReasonCode()), "fail_reason_code", query.getFailReasonCode());
        wrapper.eq(query.getIndexerId() != null, "indexer_id", query.getIndexerId());
        wrapper.eq(query.getDownloaderId() != null, "downloader_id", query.getDownloaderId());
        wrapper.eq(StringUtils.isNotBlank(query.getHrState()), "hr_state", query.getHrState());
        if (Boolean.TRUE.equals(query.getHideSuperseded())) {
            wrapper.and(w -> w.ne("state", DownloadRecordState.FAILED.value())
                    .or().apply(UnresolvedFailureSql.NOT_SUPERSEDED));
        }
        if (Boolean.TRUE.equals(query.getHideIgnored())) {
            wrapper.and(w -> w.ne("state", DownloadRecordState.FAILED.value())
                    .or().ne("fail_ignored", DownloadRecordAdminService.FAIL_IGNORED));
        }
        // 时间区间，开始 / 结束各自独立，只填一侧就是半开区间；格式不合法的一侧直接忽略
        String beginTime = QueryTimeRange.get(query.getParams(), "beginTime");
        String endTime = QueryTimeRange.get(query.getParams(), "endTime");
        if (beginTime == null && endTime == null) {
            return;
        }
        String column = dateColumn(query.getDateField());
        if ("update_time".equals(column)) {
            // 失败没有专属时间列，FAILED 行的 update_time 就是被判失败的那一刻（与统计仪表盘同一代理列），
            // 离开 FAILED 状态的行 update_time 就不再有这层含义了
            wrapper.eq("state", DownloadRecordState.FAILED.value());
        }
        wrapper.ge(beginTime != null, column, beginTime);
        wrapper.le(endTime != null, column, endTime);
    }

    /** 日期区间对应的列，白名单映射，未知值回退推送时间——不能把前端传来的字符串直接拼成列名 */
    static String dateColumn(String dateField) {
        if ("COMPLETED".equalsIgnoreCase(dateField)) {
            return "completed_time";
        }
        if ("FAILED".equalsIgnoreCase(dateField)) {
            return "update_time";
        }
        return "pushed_time";
    }

    /**
     * 当前请求的可见范围。每次现算，不缓存到字段上——Controller 是单例，请求级状态不能放实例字段。
     */
    private PtStatsScope scope() {
        return PtStatsScope.of(isAdmin(), getUserId());
    }

    private <R> Result<R> denyIfInaccessible(Integer id) {
        return adminService.canAccess(id, scope()) ? null : Result.error(DENIED);
    }

    /**
     * 立即重试一条失败的下载记录：按订阅标题+季/集号重新发起一次搜索补集。
     */
    @PostMapping("/{id}/retry")
    public Result<SupplementResult> retry(@PathVariable("id") Integer id) {
        Result<SupplementResult> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        try {
            return Result.success(adminService.retry(id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量重试选中的失败下载记录，<b>转后台执行、立即返回</b>。
     * <p>
     * 每条重试都要真的去打一轮索引器（季包走整季补搜），串行跑下来几条就超过前端的请求超时——
     * 原先同步执行时，页面报「请求超时」而后端还在继续推送，用户很可能再点一次。
     * 现在接口只返回本批的 {@code batchId} 与实际受理的条数，跑完后经 PT 状态 WebSocket
     * 推一条 {@code batchRetry} 事件，发起的页面按 {@code batchId} 认领并提示结果。
     * </p>
     */
    @PostMapping("/batchRetry")
    public Result<Map<String, Object>> batchRetry(@RequestParam("ids") String ids) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要重试的下载记录");
        }
        // 归属在请求线程里过滤：后台线程拿不到当前用户
        List<Integer> accessible = adminService.filterAccessible(parseIds(ids), scope());
        if (accessible.isEmpty()) {
            return Result.error(DENIED);
        }
        String batchId = UUID.randomUUID().toString();
        AsyncManager.me().execute(() -> {
            BatchRetryResult result = adminService.retryBatch(accessible);
            PtStatusWebSocket.pushBatchRetryEvent(batchId, result.getTotal(), result.getPushedCount(), result.getSkippedCount());
        });
        return Result.success(Map.of("batchId", batchId, "accepted", accessible.size()));
    }

    /**
     * 忽略 / 取消忽略选中的失败记录（非 FAILED 的不计），返回实际改动条数。
     * 忽略后不再计入首页待办，统计照算，订阅的集状态不受影响，见 {@link DownloadRecordAdminService#setFailIgnored}。
     */
    @PostMapping("/batchIgnore")
    public Result<Integer> batchIgnore(@RequestParam("ids") String ids,
                                       @RequestParam(value = "ignored", defaultValue = "true") boolean ignored) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要忽略的下载记录");
        }
        List<Integer> accessible = adminService.filterAccessible(parseIds(ids), scope());
        if (accessible.isEmpty()) {
            return Result.error(DENIED);
        }
        return Result.success(adminService.setFailIgnored(accessible, ignored));
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
        List<Integer> requested = parseIds(ids);
        BatchBlacklistResult result = blacklistService.blockRecordGuidBatch(
                adminService.filterAccessible(requested, scope()), req == null ? null : req.getReason());
        return Result.success(withInaccessibleCounted(result, requested.size()));
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
        List<Integer> requested = parseIds(ids);
        BatchBlacklistResult result = blacklistService.blockRecordReleaseGroupBatch(
                adminService.filterAccessible(requested, scope()), req == null ? null : req.getReason());
        return Result.success(withInaccessibleCounted(result, requested.size()));
    }

    /** 被归属过滤掉的条数并入「未能拉黑」，总数仍按用户选中的条数报，前端提示才对得上 */
    private BatchBlacklistResult withInaccessibleCounted(BatchBlacklistResult result, int requested) {
        int filtered = requested - result.getTotal();
        if (filtered <= 0) {
            return result;
        }
        return new BatchBlacklistResult(requested, result.getAddedCount(), result.getDuplicateCount(),
                result.getFailedCount() + filtered);
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
        Result<Boolean> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
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
        Result<Boolean> denied = denyIfInaccessible(id);
        if (denied != null) {
            return denied;
        }
        try {
            return Result.success(blacklistService.blockRecordReleaseGroup(id, req == null ? null : req.getReason()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 预览按规则清理会删掉多少条（仅管理员）。规则见 {@link DownloadRecordAdminService#cleanup}。
     */
    @GetMapping("/cleanup/preview")
    public Result<Long> cleanupPreview(@RequestParam("days") Integer days) {
        Result<Long> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        try {
            return Result.success(adminService.countCleanable(days));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 清理 N 天前已落定、不再被引用的下载记录（仅管理员：清的是全站的记录）。
     * 这是按规则清理，不是开放单条删除——单条删除对系统生成的记录仍然不开放。
     */
    @DeleteMapping("/cleanup")
    public Result<Integer> cleanup(@RequestParam("days") Integer days) {
        Result<Integer> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        try {
            return Result.success(adminService.cleanup(days));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
