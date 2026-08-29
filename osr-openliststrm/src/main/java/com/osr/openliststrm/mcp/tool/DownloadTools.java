package com.osr.openliststrm.mcp.tool;

import com.osr.openliststrm.controller.api.PtDownloadRecordRestController;
import com.osr.openliststrm.controller.api.PtStatsRestController;
import com.osr.openliststrm.mcp.McpCallContext;
import com.osr.openliststrm.mcp.McpJobService;
import com.osr.openliststrm.mcp.McpResults;
import com.osr.openliststrm.mcp.McpToolGroup;
import com.osr.openliststrm.mcp.McpToolSpec;
import com.osr.openliststrm.req.BlacklistReq;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 下载记录与 PT 统计相关工具。
 *
 * @author Jack
 */
@Component
public class DownloadTools implements McpToolGroup {

    @Autowired
    private PtDownloadRecordRestController records;

    @Autowired
    private PtStatsRestController stats;

    @Autowired
    private McpJobService jobService;

    @Override
    public List<McpToolSpec> tools() {
        return List.of(
                listDownloadRecords(),
                retryDownload(),
                blacklistTorrent(),
                getPtStats());
    }

    private McpToolSpec listDownloadRecords() {
        return McpToolSpec.named("list_download_records")
                .describe("""
                        列出 PT 下载记录：种子标题、所属订阅与集号、状态、索引器、下载器、失败原因。
                        排查「这一集下载失败了」时先用它拿到记录 id 与 failReason，\
                        再决定是重试（retry_download）还是拉黑这个资源（blacklist_torrent）。""")
                .param("subscriptionId", "integer", "只看某条订阅的记录")
                .enumParam("state", "记录状态",
                        List.of("PUSHED", "DOWNLOADING", "COMPLETED", "FAILED"))
                .param("title", "string", "按种子标题模糊筛选")
                .paging()
                .handle(args -> records.list(args.getInt("subscriptionId"),
                        args.getString("state"), args.getString("title")));
    }

    private McpToolSpec retryDownload() {
        return McpToolSpec.named("retry_download")
                .describe("""
                        重试一条失败的下载记录：按订阅标题与季/集号重新发起一次检索补集，\
                        推送新找到的最优资源（不是重新下载原来那个种子）。

                        【耗时较长】会真的去打索引器，因此<b>立刻返回一个 jobId</b>，\
                        请随后用 get_job_status 轮询。

                        注意「不可重试」的失败（如 NO_TARGET_EPISODE、种子已从下载器消失）\
                        重试也不会成功，先看 list_download_records 里的 failReason。""")
                .write()
                .requiredParam("id", "integer", "下载记录 id")
                .handle(args -> {
                    int id = args.requireInt("id");
                    String jobId = jobService.submit("retry_download", McpCallContext.requirePrincipal(),
                            () -> McpResults.describe(records.retry(id)));
                    return Map.of(
                            "jobId", jobId,
                            "state", "RUNNING",
                            "hint", "已在后台开始执行，请等待约 30 秒后调用 get_job_status 查询这个 jobId。");
                });
    }

    private McpToolSpec blacklistTorrent() {
        return McpToolSpec.named("blacklist_torrent")
                .describe("""
                        把一条下载记录对应的资源拉黑，之后不再被匹配到。两个维度：
                        - guid：只拉黑这一个种子（这份资源坏了/是假种）
                        - releaseGroup：拉黑整个发布组（这个组的压制我都不要）

                        <b>releaseGroup 的影响面大得多</b>——它会让该组今后发布的所有资源\
                        （包括还没发布的）都被排除，请确认用户确实是这个意思。
                        拉黑可以在网页端的「黑名单」页面撤销。""")
                .destructive()
                .requiredParam("id", "integer", "下载记录 id")
                .requiredEnumParam("by", "拉黑维度", List.of("guid", "releaseGroup"))
                .param("reason", "string", "拉黑原因，可选；会记进黑名单便于日后回看")
                .handle(args -> {
                    int id = args.requireInt("id");
                    BlacklistReq request = new BlacklistReq();
                    request.setReason(args.getString("reason"));
                    return "releaseGroup".equals(args.requireString("by"))
                            ? records.blacklistReleaseGroup(id, request)
                            : records.blacklistGuid(id, request);
                });
    }

    private McpToolSpec getPtStats() {
        return McpToolSpec.named("get_pt_stats")
                .describe("""
                        PT 模块的统计概览：总体计数、各索引器命中率、下载失败原因分布、候选被淘汰的原因分布。
                        回答「最近为什么老是下不到东西」这类问题时先看它——\
                        淘汰原因集中在某一条过滤规则上，比逐条翻搜索日志快得多。""")
                .param("days", "integer", "失败原因统计的回溯天数，默认由后端决定（通常 7 天）")
                .handle(args -> {
                    Integer days = args.getInt("days");
                    // 四个统计各是一个接口，但它们回答的是同一个问题的四个侧面，
                    // 拆成四个工具只会让模型多跑三轮往返，而这几个查询都很便宜
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("overview", McpResults.unwrapOrThrow(stats.overview()));
                    view.put("indexerHitRate", McpResults.unwrapOrThrow(stats.indexerHitRate()));
                    view.put("failReasons", McpResults.unwrapOrThrow(stats.failReasons(days)));
                    view.put("rejectReasons", McpResults.unwrapOrThrow(stats.rejectReasons()));
                    return view;
                });
    }
}
