package com.osr.openliststrm.mcp.tool;

import com.osr.openliststrm.controller.api.PtCalendarRestController;
import com.osr.openliststrm.controller.api.PtHealthRestController;
import com.osr.openliststrm.controller.api.PtSubscriptionRestController;
import com.osr.openliststrm.mcp.McpArgs;
import com.osr.openliststrm.mcp.McpCallContext;
import com.osr.openliststrm.mcp.McpJobService;
import com.osr.openliststrm.mcp.McpResults;
import com.osr.openliststrm.mcp.McpToolException;
import com.osr.openliststrm.mcp.McpToolGroup;
import com.osr.openliststrm.mcp.McpToolSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 追剧相关工具：排播日历、缺集体检、就地处置。
 * <p>
 * 这一组回答的是「我的剧齐了吗、哪一格为什么还是灰的」，与
 * {@link com.osr.openliststrm.mcp.tool.DownloadTools} 那组（「这个种子下得怎么样」）
 * 刻意分开——两者的处置方向完全不同。
 * </p>
 *
 * @author Jack
 */
@Component
public class TrackingTools implements McpToolGroup {

    @Autowired
    private PtCalendarRestController calendar;

    @Autowired
    private PtHealthRestController health;

    @Autowired
    private PtSubscriptionRestController subscriptions;

    @Autowired
    private McpJobService jobService;

    @Override
    public List<McpToolSpec> tools() {
        return List.of(
                getCalendar(),
                getEpisodeHealth(),
                enableAutoSearch(),
                ignoreInHealth(),
                searchMissingEpisodes(),
                resetEpisode());
    }

    private McpToolSpec getCalendar() {
        return McpToolSpec.named("get_calendar")
                .describe("""
                        按日期区间查排播日历：哪一天有哪些订阅的哪一集播出，以及那一集当前的状态。
                        日期用 YYYY-MM-DD。数据来自每 12 小时同步一次的 TMDb 播出日期，
                        没有播出日期的集不会出现在日历里。""")
                .requiredParam("start", "string", "起始日期，格式 YYYY-MM-DD（含）")
                .requiredParam("end", "string", "结束日期，格式 YYYY-MM-DD（含）")
                .handle(args -> calendar.query(parseDate(args, "start"), parseDate(args, "end")));
    }

    private McpToolSpec getEpisodeHealth() {
        return McpToolSpec.named("get_episode_health")
                .describe("""
                        缺集体检：列出播出多日仍未入库的集，并给出「为什么还缺」的诊断。
                        分档不是严重程度而是<b>处置方向</b>：
                        - OVERDUE_MISSING 该去看搜索链路（关键词、索引器、过滤规则）
                        - OVERDUE_IN_FLIGHT 该去看下载/上传链路
                        - BLOCKED 已熔断，需要人工介入
                        - NO_AIR_DATE 连「逾期几天」都算不出来，通常是未定档或 TMDb 还没录入
                        电影不参与体检（上映后几周内没有资源是正常状态，不是故障）。
                        对 OVERDUE_MISSING 的订阅，可以用 enable_auto_search 打开自动补搜，\
                        或用 search_missing_episodes 立刻搜一次。""")
                .param("includeIgnored", "boolean", "是否包含已被用户忽略的订阅，默认 false")
                .handle(args -> health.report(args.getBool("includeIgnored", false)));
    }

    private McpToolSpec enableAutoSearch() {
        return McpToolSpec.named("enable_auto_search")
                .describe("""
                        为若干条订阅打开「定期自动补搜」。打开后系统会按配置的周期自动检索缺集，\
                        不必每次手动触发。
                        自动补搜默认是关的——每条开着的订阅每轮都要向每个索引器打满一整份检索计划，\
                        全量开启会让追完的老剧长期空转。所以只对确实还在追、且确实缺集的订阅打开它。
                        返回实际生效的条数（无权操作的会被跳过）。""")
                .write()
                .requiredIntArrayParam("subscriptionIds", "订阅 id 列表")
                .handle(args -> health.enableAutoSearch(requireIds(args)));
    }

    private McpToolSpec ignoreInHealth() {
        return McpToolSpec.named("set_health_ignored")
                .describe("""
                        把若干条订阅从缺集体检里忽略（或取消忽略）。
                        <b>只影响体检页的可见性与逾期缺集提醒</b>，不影响 RSS 匹配、自动补搜、手动搜索——\
                        这是它与 pause_subscription 的根本区别。适用于「这部剧的资源确实找不到了，\
                        别再天天提醒我」。""")
                .write()
                .requiredIntArrayParam("subscriptionIds", "订阅 id 列表")
                .param("ignored", "boolean", "true=忽略（默认），false=取消忽略")
                .handle(args -> health.ignore(requireIds(args), args.getBool("ignored", true)));
    }

    private McpToolSpec searchMissingEpisodes() {
        return McpToolSpec.named("search_missing_episodes")
                .describe("""
                        对一条订阅立刻补搜它当前<b>所有</b>缺集，并自动推送最优资源到下载器。未播出的集不参与。

                        【耗时较长】季搜索每个索引器 30 秒软上限，其后还有一段单集补发（最多 5 集、180 秒），\
                        合计可达数分钟。因此<b>立刻返回一个 jobId</b>，请随后用 get_job_status 轮询，\
                        不要重复提交同一个请求，更不要在等待期间改用 search_episode 逐集再搜一遍。""")
                .write()
                .requiredParam("subscriptionId", "integer", "订阅 id")
                .handle(args -> {
                    int subId = args.requireInt("subscriptionId");
                    String jobId = jobService.submit("search_missing_episodes",
                            McpCallContext.requirePrincipal(),
                            () -> McpResults.describe(health.searchMissing(subId)));
                    return Map.of(
                            "jobId", jobId,
                            "state", "RUNNING",
                            "hint", "已在后台开始执行。请等待约 60 秒后调用 get_job_status 查询这个 jobId，"
                                    + "未结束就再等一会儿；不要重复提交同一个请求。");
                });
    }

    private McpToolSpec resetEpisode() {
        return McpToolSpec.named("reset_episode")
                .describe("""
                        把某一集重置为「缺失」，让系统重新去找它。
                        用于「这一集从媒体库里被误删了」或「想重新洗一版」。
                        <b>不会删除任何已有文件</b>，但会让这一集重新进入检索与下载流程，\
                        可能产生新的下载与保种义务。""")
                .destructive()
                .requiredParam("subscriptionId", "integer", "订阅 id")
                .requiredParam("episode", "integer", "集号（季内相对集号，与 get_subscription_episodes 返回的一致）")
                .handle(args -> subscriptions.resetEpisode(args.requireInt("subscriptionId"),
                        args.requireInt("episode")));
    }

    /** 取 id 列表并转成现有批量接口要的逗号分隔串；空列表直接拒绝 */
    private String requireIds(McpArgs args) {
        String ids = args.getIdsAsCsv("subscriptionIds");
        if (ids.isEmpty()) {
            throw new McpToolException("subscriptionIds 不能为空");
        }
        return ids;
    }

    private LocalDate parseDate(McpArgs args, String name) {
        String value = args.requireString(name);
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new McpToolException("参数 " + name + " 需要是 YYYY-MM-DD 格式的日期，收到的是：" + value);
        }
    }
}
