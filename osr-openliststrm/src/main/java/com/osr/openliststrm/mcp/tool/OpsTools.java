package com.osr.openliststrm.mcp.tool;

import com.osr.openliststrm.controller.api.OpenlistDashboardRestController;
import com.osr.openliststrm.controller.api.OpenlistStrmRestController;
import com.osr.openliststrm.mcp.McpCallContext;
import com.osr.openliststrm.mcp.McpJobService;
import com.osr.openliststrm.mcp.McpToolGroup;
import com.osr.openliststrm.mcp.McpToolSpec;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 运维视角的工具：总览、失败项、后台作业状态。
 *
 * <h2>关于「读日志」</h2>
 * <p>
 * 本组<b>没有</b>读取运行日志的工具，这是一次有意的取舍而不是遗漏：实时日志页那套按行解析
 * （{@code LogWebSocket.LineCodec}）与 {@code logback-spring.xml} 的 pattern 是一对隐性耦合，
 * 而它住在 osr-admin，本模块够不着。在这里另写一份解析等于制造第二个会与 pattern 漂移的
 * 消费者——那种漂移不报错、不告警，只是某天起所有行都落进「续行」分支。
 * 要读日志请开网页端的实时日志页。
 * </p>
 *
 * @author Jack
 */
@Component
public class OpsTools implements McpToolGroup {

    /** {@code openlist_strm.strm_status}：0-失败 1-成功 */
    private static final String STRM_STATUS_FAILED = "0";

    @Autowired
    private OpenlistDashboardRestController dashboard;

    @Autowired
    private OpenlistStrmRestController strmRecords;

    @Autowired
    private McpJobService jobService;

    @Override
    public List<McpToolSpec> tools() {
        return List.of(
                getDashboard(),
                listFailedStrmRecords(),
                retryStrmRecord(),
                getJobStatus());
    }

    private McpToolSpec getDashboard() {
        return McpToolSpec.named("get_dashboard")
                .describe("""
                        系统总览：同步、STRM 生成、重命名三条链路的累计与近期计数。
                        回答「系统整体运转正常吗」这类问题的第一站。""")
                .handle(args -> dashboard.getStats());
    }

    private McpToolSpec listFailedStrmRecords() {
        return McpToolSpec.named("list_failed_strm_records")
                .describe("""
                        列出生成失败的 STRM 记录，含记录 id、目标路径、文件名。
                        拿到 id 后可用 retry_strm_record 逐条重试。""")
                .param("path", "string", "按 STRM 目录模糊筛选")
                .paging()
                .handle(args -> {
                    OpenlistStrmPlus query = new OpenlistStrmPlus();
                    query.setStrmStatus(STRM_STATUS_FAILED);
                    query.setStrmPath(args.getString("path"));
                    return strmRecords.list(query);
                });
    }

    private McpToolSpec retryStrmRecord() {
        return McpToolSpec.named("retry_strm_record")
                .describe("""
                        重试一条失败的 STRM 生成记录。
                        <b>只重新生成 .strm 文件</b>，不会删除或改动网盘上的任何东西。""")
                .write()
                .requiredParam("id", "integer", "STRM 记录 id，来自 list_failed_strm_records")
                .handle(args -> strmRecords.retry(args.requireInt("id")));
    }

    private McpToolSpec getJobStatus() {
        return McpToolSpec.named("get_job_status")
                .describe("""
                        查一个后台作业的状态与结果。jobId 来自 search_episode、search_missing_episodes、
                        retry_download、generate_strm_for_path 这几个耗时工具的返回值。

                        state 为 RUNNING 表示还在跑，请再等一会儿重新查同一个 jobId，<b>不要</b>重新提交原来那个请求。
                        作业记录保留 2 小时。""")
                .requiredParam("jobId", "string", "后台作业 id")
                .handle(args -> jobService.status(args.requireString("jobId"),
                        McpCallContext.requirePrincipal()));
    }
}
