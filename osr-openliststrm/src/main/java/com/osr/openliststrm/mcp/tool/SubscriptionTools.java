package com.osr.openliststrm.mcp.tool;

import com.osr.openliststrm.controller.api.PtSubscriptionRestController;
import com.osr.openliststrm.mcp.McpCallContext;
import com.osr.openliststrm.mcp.McpJobService;
import com.osr.openliststrm.mcp.McpResults;
import com.osr.openliststrm.mcp.McpToolGroup;
import com.osr.openliststrm.mcp.McpToolSpec;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.pt.subscription.dto.SearchRequest;
import com.osr.openliststrm.pt.subscription.dto.SubscribeRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * PT 订阅相关工具。
 * <p>
 * 全部实现都是<b>直接调用 {@link PtSubscriptionRestController} 的方法</b>——不走 HTTP，
 * 也不绕到 Service 层自己拼一份判断。归属隔离（{@code denyIfInaccessible}）、列表的
 * 归属过滤、进度计数的填充都长在那个 Controller 上，绕过它就等于在 MCP 侧复制一份，
 * 而复制出来的两份迟早漂移——漂移的表现是「网页上看不到的订阅，助理能看到」。
 * </p>
 *
 * @author Jack
 */
@Component
public class SubscriptionTools implements McpToolGroup {

    /** 手动搜索单集时的默认目标：-1 表示季包/整部，与 {@code SubscriptionMatcher.SEASON_PACK} 一致 */
    private static final int SEASON_PACK = -1;

    @Autowired
    private PtSubscriptionRestController subscriptions;

    @Autowired
    private McpJobService jobService;

    @Override
    public List<McpToolSpec> tools() {
        return List.of(
                listSubscriptions(),
                getSubscription(),
                getProgress(),
                getEpisodes(),
                getSearchLogs(),
                searchTmdb(),
                seasonEpisodeCount(),
                createSubscription(),
                searchEpisode(),
                pauseSubscription(),
                resumeSubscription(),
                deleteSubscription());
    }

    private McpToolSpec listSubscriptions() {
        return McpToolSpec.named("list_subscriptions")
                .describe("""
                        列出 PT 订阅。返回每条订阅的 id、标题、媒体类型、季号、状态，以及\
                        「已入库/在途/缺集」三个进度计数。只能看到令牌归属用户自己的订阅和无归属的公共订阅。
                        想知道某一集为什么还没到，用 get_subscription_progress 或 get_episode_health。""")
                .param("title", "string", "按剧名/片名模糊筛选")
                .enumParam("mediaType", "媒体类型", List.of("TV", "MOVIE"))
                .enumParam("status", "订阅状态", List.of("ACTIVE", "PAUSED", "COMPLETED"))
                .enumParam("sortBy", "排序方式", List.of("lastMatchTime", "lastSearchTime", "title"))
                .paging()
                .handle(args -> {
                    PtSubscriptionPlus query = new PtSubscriptionPlus();
                    query.setTitle(args.getString("title"));
                    query.setMediaType(args.getString("mediaType"));
                    query.setStatus(args.getString("status"));
                    query.setSortBy(args.getString("sortBy"));
                    return subscriptions.list(query);
                });
    }

    private McpToolSpec getSubscription() {
        return McpToolSpec.named("get_subscription")
                .describe("按 id 查一条订阅的完整配置。id 来自 list_subscriptions。")
                .requiredParam("id", "integer", "订阅 id")
                .handle(args -> subscriptions.getById(args.requireInt("id")));
    }

    private McpToolSpec getProgress() {
        return McpToolSpec.named("get_subscription_progress")
                .describe("""
                        查一条订阅的追剧进度：已入库集号、在途集号、缺失集号，以及其中哪些集尚未播出。
                        「缺集」里包含未播出的集，判断「该不该去搜」时要把 unairedEpisodes 减掉——\
                        对还没播的集发起检索必然落空。""")
                .requiredParam("id", "integer", "订阅 id")
                .handle(args -> subscriptions.progress(args.requireInt("id")));
    }

    private McpToolSpec getEpisodes() {
        return McpToolSpec.named("get_subscription_episodes")
                .describe("""
                        查一条订阅的每集明细：集号、状态、播出日期、对应的 TMDb 集号、关联的下载记录 id。
                        比 get_subscription_progress 详细，用于回答「第 N 集现在到哪一步了」。""")
                .requiredParam("id", "integer", "订阅 id")
                .handle(args -> subscriptions.episodes(args.requireInt("id")));
    }

    private McpToolSpec getSearchLogs() {
        return McpToolSpec.named("get_subscription_search_logs")
                .describe("""
                        查一条订阅最近的匹配/过滤日志，最多 100 条。用来回答\
                        「站上明明有资源，为什么没推给我」——日志里会写清候选是被哪一步、哪条规则淘汰的。""")
                .requiredParam("id", "integer", "订阅 id")
                .handle(args -> subscriptions.searchLogs(args.requireInt("id")));
    }

    private McpToolSpec searchTmdb() {
        return McpToolSpec.named("search_tmdb")
                .describe("""
                        在 TMDb 上按关键词搜作品，供建订阅时确认是哪一部。返回 tmdbId、标题、\
                        原名、首播/上映年份、简介。建订阅前<b>必须</b>先用它拿到 tmdbId。""")
                .requiredEnumParam("mediaType", "媒体类型", List.of("TV", "MOVIE"))
                .requiredParam("keyword", "string", "作品名关键词，中英文均可")
                .handle(args -> subscriptions.tmdbSearch(args.requireString("mediaType"),
                        args.requireString("keyword")));
    }

    private McpToolSpec seasonEpisodeCount() {
        return McpToolSpec.named("get_tmdb_season_episode_count")
                .describe("""
                        查 TMDb 上某部剧某一季共有多少集，供建订阅时确认季号选得对不对。
                        注意长篇动画在 TMDb 上可能用绝对集号（例如航海王第 23 季是第 1156~1181 集），
                        与 PT 站上的季内集号不是同一套编号，OSR 会自己对齐，你不需要换算。""")
                .requiredParam("tmdbId", "string", "TMDb 作品 id，来自 search_tmdb")
                .requiredParam("season", "integer", "季号")
                .handle(args -> subscriptions.seasonEpisodeCount(args.requireString("tmdbId"),
                        args.requireInt("season")));
    }

    private McpToolSpec createSubscription() {
        return McpToolSpec.named("create_subscription")
                .describe("""
                        新建一条 PT 订阅。tmdbId 必须来自 search_tmdb，不要凭印象填。
                        剧集必须指定 season（可先用 get_tmdb_season_episode_count 确认该季有多少集）；\
                        电影忽略 season。新订阅会自动触发一次补搜。
                        订阅归属于令牌所属的用户。""")
                .write()
                .requiredParam("tmdbId", "string", "TMDb 作品 id，来自 search_tmdb")
                .requiredEnumParam("mediaType", "媒体类型", List.of("TV", "MOVIE"))
                .param("season", "integer", "季号；剧集必填，电影忽略")
                .param("downloaderId", "integer", "指定下载器 id，不填则由系统在启用的下载器间负载均衡")
                .handle(args -> {
                    SubscribeRequest request = new SubscribeRequest();
                    request.setTmdbId(args.requireString("tmdbId"));
                    request.setMediaType(args.requireString("mediaType"));
                    request.setSeason(args.getInt("season"));
                    request.setDownloaderId(args.getInt("downloaderId"));
                    // ownerUserId 刻意不传：Controller 会用当前登录用户（也就是令牌归属人）覆盖它，
                    // 这里传什么都没用，传了反而让人以为可以指定别人
                    return subscriptions.subscribe(request);
                });
    }

    private McpToolSpec searchEpisode() {
        return McpToolSpec.named("search_episode")
                .describe("""
                        为某一集（或整季包）立刻检索并自动推送最优资源到下载器。

                        【耗时较长】会向每个启用的索引器发起多步检索，可达 1 分钟以上，因此<b>立刻返回一个 jobId</b>，\
                        请随后用 get_job_status 轮询，不要重复提交同一个请求。
                        结果里的 message 会说清是推送了几个资源，还是因为什么原因一个都没推——\
                        「候选全被过滤规则淘汰」与「压根没搜到候选」的处置方向完全相反。

                        想一次补齐整条订阅的所有缺集，用 search_missing_episodes 而不是逐集调用本工具。""")
                .write()
                .requiredParam("subscriptionId", "integer", "订阅 id")
                .param("episode", "integer", "目标集号；不填表示整季包/整部电影")
                .param("keyword", "string", "自定义检索关键词；不填则由系统按标题与季集号生成")
                .handle(args -> {
                    int id = args.requireInt("subscriptionId");
                    SearchRequest request = new SearchRequest();
                    request.setEpisode(args.has("episode") ? args.requireInt("episode") : SEASON_PACK);
                    request.setKeyword(args.getString("keyword"));
                    // 手动挑选模式返回的是候选列表，用户挑完还要把整个候选对象原样回传给推送接口
                    // （含 description，那里可能藏着集号，见 DescriptionEpisode）。让模型来转述
                    // 一个它并不理解的结构，出错是必然的而且没有任何一层拦得住——所以 MCP 只做自动推送
                    request.setManualSelect(false);
                    return submitJob("search_episode", () -> McpResults.describe(subscriptions.search(id, request)));
                });
    }

    private McpToolSpec pauseSubscription() {
        return McpToolSpec.named("pause_subscription")
                .describe("暂停一条订阅：停止 RSS 匹配与自动补搜，已在途的下载不受影响。")
                .write()
                .requiredParam("id", "integer", "订阅 id")
                .handle(args -> subscriptions.pause(args.requireInt("id")));
    }

    private McpToolSpec resumeSubscription() {
        return McpToolSpec.named("resume_subscription")
                .describe("恢复一条已暂停的订阅。")
                .write()
                .requiredParam("id", "integer", "订阅 id")
                .handle(args -> subscriptions.resume(args.requireInt("id")));
    }

    private McpToolSpec deleteSubscription() {
        return McpToolSpec.named("delete_subscription")
                .describe("""
                        删除一条订阅及其全部集记录。<b>不可撤销</b>，也不会删除已下载的文件或已入库的内容。
                        只是想让它停下来的话用 pause_subscription。""")
                .destructive()
                .requiredParam("id", "integer", "订阅 id")
                .handle(args -> subscriptions.delete(args.requireInt("id")));
    }

    /** 提交后台作业并返回给模型的说明。身份必须在<b>当前线程</b>取好再交给作业线程 */
    private Object submitJob(String tool, java.util.function.Supplier<Object> work) {
        String jobId = jobService.submit(tool, McpCallContext.requirePrincipal(), work);
        return java.util.Map.of(
                "jobId", jobId,
                "state", "RUNNING",
                "hint", "已在后台开始执行。请等待约 30 秒后调用 get_job_status 查询这个 jobId，"
                        + "未结束就再等一会儿；不要重复提交同一个请求。");
    }
}
