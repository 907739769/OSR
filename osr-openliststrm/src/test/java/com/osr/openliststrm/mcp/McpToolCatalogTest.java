package com.osr.openliststrm.mcp;

import com.osr.openliststrm.mcp.tool.DownloadTools;
import com.osr.openliststrm.mcp.tool.OpsTools;
import com.osr.openliststrm.mcp.tool.SubscriptionTools;
import com.osr.openliststrm.mcp.tool.TaskTools;
import com.osr.openliststrm.mcp.tool.TrackingTools;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具清单本身的守卫。
 * <p>
 * <b>这是整个 MCP 层最要紧的一个测试</b>：暴露出去的工具集合就是「助理能对这套系统做什么」的
 * 完整定义，而多暴露一个工具在代码审查里只是多了几行看起来很像其它工具的代码。
 * 把清单逐字钉死，任何增删都必须同时改这里——也就必须被想一遍。
 * </p>
 * <p>
 * 测试直接 {@code new} 出各工具组：{@code tools()} 只是<b>声明</b>，处理函数是 lambda，
 * 注入的 Controller 要到真正调用时才会被解引用，所以不需要 Spring 上下文。
 * </p>
 *
 * @author Jack
 */
class McpToolCatalogTest {

    /**
     * 当前暴露的全部工具。
     * <p>
     * 改这个集合前先回答一个问题：新工具做错了要花多大代价收回来？
     * 不可逆、或代价落在 OSR 之外（网盘文件、站点 H&amp;R、第三方凭据）的，
     * 一律不要加进来——见 {@link McpToolRegistry} 的类注释。
     * </p>
     */
    private static final Set<String> EXPECTED_TOOLS = new LinkedHashSet<>(List.of(
            // 订阅
            "list_subscriptions", "get_subscription", "get_subscription_progress",
            "get_subscription_episodes", "get_subscription_search_logs",
            "search_tmdb", "get_tmdb_season_episode_count", "create_subscription",
            "search_episode", "pause_subscription", "resume_subscription", "delete_subscription",
            // 追剧
            "get_calendar", "get_episode_health", "enable_auto_search", "set_health_ignored",
            "search_missing_episodes", "reset_episode",
            // 下载
            "list_download_records", "retry_download", "blacklist_torrent", "get_pt_stats",
            // 任务
            "list_strm_tasks", "run_strm_task", "list_copy_tasks", "run_copy_task",
            "list_rename_tasks", "run_rename_task", "generate_strm_for_path",
            // 运维
            "get_dashboard", "list_failed_strm_records", "retry_strm_record", "get_job_status"));

    /**
     * 这些字眼一旦出现在工具名里，多半意味着有人把一类不该暴露的能力接了进来。
     * <p>
     * 它拦不住起了别的名字的实现——真正的防线是上面那份逐字清单。留着它是为了让「顺手加一个」
     * 这种最常见的加法在最快的路径上就被挡下，而不是等到有人去读清单。
     * </p>
     */
    private static final List<String> FORBIDDEN_NAME_FRAGMENTS = List.of(
            "netdisk", "net_disk", "delete_file", "remove_file",
            "torrent_delete", "delete_torrent", "clean_rule", "transfer_rule",
            "indexer", "downloader", "media_server", "sys_config", "system_config",
            "user", "role", "menu");

    private List<McpToolSpec> allTools() {
        List<McpToolSpec> tools = new ArrayList<>();
        tools.addAll(new SubscriptionTools().tools());
        tools.addAll(new TrackingTools().tools());
        tools.addAll(new DownloadTools().tools());
        tools.addAll(new TaskTools().tools());
        tools.addAll(new OpsTools().tools());
        return tools;
    }

    @Test
    void 工具清单与预期逐字一致() {
        Set<String> actual = allTools().stream().map(McpToolSpec::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(EXPECTED_TOOLS, actual,
                "MCP 工具清单变了。这不是一次普通的重构——它改变了助理能对这套系统做什么，"
                        + "请确认新增的工具做错时是可撤销的，然后再更新本测试里的清单");
    }

    @Test
    void 工具名不重复() {
        List<String> names = allTools().stream().map(McpToolSpec::name).toList();
        assertEquals(names.size(), Set.copyOf(names).size(), "存在重名工具，后注册的会静默覆盖先注册的");
    }

    @Test
    void 没有工具触碰不该暴露的能力() {
        for (McpToolSpec spec : allTools()) {
            for (String fragment : FORBIDDEN_NAME_FRAGMENTS) {
                assertFalse(spec.name().contains(fragment),
                        "工具 " + spec.name() + " 的名字里出现了 " + fragment
                                + "：删网盘文件、删种、改第三方凭据、用户与角色管理都不应有对应工具，"
                                + "理由见 McpToolRegistry 的类注释");
            }
        }
    }

    @Test
    void 每个工具都有给模型看的说明() {
        for (McpToolSpec spec : allTools()) {
            assertTrue(spec.description() != null && spec.description().length() > 10,
                    "工具 " + spec.name() + " 的说明太短，模型将无从判断该不该调用它");
        }
    }

    @Test
    void 权限档与只读标记必须自洽() {
        for (McpToolSpec spec : allTools()) {
            if (spec.requiredScope() == McpScope.READ) {
                assertTrue(spec.readOnly(),
                        "工具 " + spec.name() + " 只要求 read 档却没标 readOnly，"
                                + "客户端会把它当成会改动数据的工具而多弹一次确认");
                assertFalse(spec.destructive(), "只读工具 " + spec.name() + " 不该标 destructive");
            } else {
                assertFalse(spec.readOnly(),
                        "工具 " + spec.name() + " 要求 " + spec.requiredScope().code()
                                + " 档却标了 readOnly——客户端据此跳过确认，用户会在毫不知情的情况下被改动数据");
            }
            if (spec.destructive()) {
                assertEquals(McpScope.ADMIN, spec.requiredScope(),
                        "标了 destructive 的工具 " + spec.name() + " 必须要求 admin 档");
            }
        }
    }

    @Test
    void 难以撤销的操作必须落在admin档() {
        // 逐条列出而不是靠命名规则推断：这几个的共同点是「做完之后要花力气才能恢复」，
        // 而那不是从名字上看得出来的
        List<String> mustBeAdmin = List.of("delete_subscription", "reset_episode", "blacklist_torrent");
        for (String name : mustBeAdmin) {
            McpToolSpec spec = allTools().stream().filter(t -> t.name().equals(name)).findFirst()
                    .orElseThrow(() -> new AssertionError("工具 " + name + " 不见了"));
            assertEquals(McpScope.ADMIN, spec.requiredScope(),
                    "工具 " + name + " 被降档了，只读/可写令牌会因此拿到一个难以撤销的操作");
            assertTrue(spec.destructive(), "工具 " + name + " 应标 destructive 以便客户端弹确认");
        }
    }
}
