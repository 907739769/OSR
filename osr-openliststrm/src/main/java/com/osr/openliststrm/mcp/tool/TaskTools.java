package com.osr.openliststrm.mcp.tool;

import com.osr.openliststrm.controller.api.OpenlistCopyTaskRestController;
import com.osr.openliststrm.controller.api.OpenlistStrmTaskRestController;
import com.osr.openliststrm.controller.api.RenameTaskRestController;
import com.osr.openliststrm.mcp.McpCallContext;
import com.osr.openliststrm.mcp.McpJobService;
import com.osr.openliststrm.mcp.McpToolGroup;
import com.osr.openliststrm.mcp.McpToolSpec;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyTaskPlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmTaskPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameTaskPlus;
import com.osr.openliststrm.service.IStrmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * STRM 生成、网盘同步、重命名三类任务的查询与触发。
 * <p>
 * 三个 {@code run_*} 工具都只是<b>触发</b>：现有接口本身就是异步的（{@code AsyncManager} 提交
 * 后立即返回），所以它们不走后台作业机制，返回「已触发」即止。进度去看对应的记录列表或仪表盘。
 * </p>
 * <p>
 * <b>这一组刻意没有「新建/修改/删除任务」</b>：任务配置里带着路径、覆盖参数这些一改就影响全局
 * 产出的东西，而模型对「用户的网盘目录结构」并没有可靠认识——填错一个路径，产出会静默地落到
 * 另一棵目录树上，而 STRM 一致性检查还会把原来那棵报成孤儿。配置去网页端改。
 * </p>
 *
 * @author Jack
 */
@Component
public class TaskTools implements McpToolGroup {

    @Autowired
    private OpenlistStrmTaskRestController strmTasks;

    @Autowired
    private OpenlistCopyTaskRestController copyTasks;

    @Autowired
    private RenameTaskRestController renameTasks;

    @Autowired
    private IStrmService strmService;

    @Autowired
    private McpJobService jobService;

    @Override
    public List<McpToolSpec> tools() {
        return List.of(
                listStrmTasks(),
                runStrmTask(),
                listCopyTasks(),
                runCopyTask(),
                listRenameTasks(),
                runRenameTask(),
                generateStrmForPath());
    }

    private McpToolSpec listStrmTasks() {
        return McpToolSpec.named("list_strm_tasks")
                .describe("列出已配置的 STRM 生成任务（每条对应一个网盘目录），含 id、路径、启用状态。")
                .paging()
                .handle(args -> strmTasks.list(new OpenlistStrmTaskPlus()));
    }

    private McpToolSpec runStrmTask() {
        return McpToolSpec.named("run_strm_task")
                .describe("""
                        立即执行一条 STRM 生成任务。异步触发，接口立刻返回；\
                        生成进度与结果去 list_failed_strm_records 或仪表盘看。""")
                .write()
                .requiredParam("id", "integer", "STRM 任务 id，来自 list_strm_tasks")
                .handle(args -> strmTasks.execute(args.requireInt("id")));
    }

    private McpToolSpec listCopyTasks() {
        return McpToolSpec.named("list_copy_tasks")
                .describe("列出已配置的网盘同步（复制）任务，含 id、源路径、目标路径、启用状态。")
                .paging()
                .handle(args -> copyTasks.list(new OpenlistCopyTaskPlus()));
    }

    private McpToolSpec runCopyTask() {
        return McpToolSpec.named("run_copy_task")
                .describe("立即执行一条网盘同步任务。异步触发，接口立刻返回。")
                .write()
                .requiredParam("id", "integer", "同步任务 id，来自 list_copy_tasks")
                .handle(args -> copyTasks.execute(args.requireInt("id")));
    }

    private McpToolSpec listRenameTasks() {
        return McpToolSpec.named("list_rename_tasks")
                .describe("列出已配置的影视文件重命名任务，含 id、源目录、目标目录、模板、启用状态。")
                .paging()
                .handle(args -> renameTasks.list(new RenameTaskPlus()));
    }

    private McpToolSpec runRenameTask() {
        return McpToolSpec.named("run_rename_task")
                .describe("""
                        立即执行一条重命名任务：扫描源目录、刮削、按模板重命名并复制到目标库。
                        异步触发，接口立刻返回。""")
                .write()
                .requiredParam("id", "integer", "重命名任务 id，来自 list_rename_tasks")
                .handle(args -> renameTasks.execute(args.requireInt("id")));
    }

    private McpToolSpec generateStrmForPath() {
        return McpToolSpec.named("generate_strm_for_path")
                .describe("""
                        为指定的网盘目录生成 STRM 文件（对应 Telegram 的 /strmdir 指令）。\
                        路径是 OpenList 里的绝对路径，例如 /115网盘/电视剧/某剧。
                        输出根目录、是否下载字幕、最小体积这些参数按该路径所属的 STRM 任务配置决定；\
                        路径不属于任何任务时用全局配置。

                        【耗时较长】要遍历整个目录树，大目录可达数分钟，因此<b>立刻返回一个 jobId</b>，\
                        请随后用 get_job_status 轮询。""")
                .write()
                .requiredParam("path", "string", "OpenList 里的绝对目录路径")
                .handle(args -> {
                    String path = args.requireString("path");
                    String jobId = jobService.submit("generate_strm_for_path",
                            McpCallContext.requirePrincipal(),
                            () -> {
                                strmService.strmDir(path);
                                return Map.of("success", true, "message", "已完成 " + path + " 的 STRM 生成，"
                                        + "具体产出与失败项请查 list_failed_strm_records");
                            });
                    return Map.of(
                            "jobId", jobId,
                            "state", "RUNNING",
                            "hint", "已在后台开始执行，请等待约 60 秒后调用 get_job_status 查询这个 jobId。");
                });
    }
}
