package com.osr.openliststrm.pt.media;

import com.osr.common.utils.FaultThrottle;
import com.osr.common.utils.LogOnce;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 新文件落盘后通知媒体服务器按目录局部刷新。
 * <p>
 * 调用方（STRM 生成、重命名、刮削）只管 {@link #submitFile}/{@link #submitDir} 报一声「这里有新东西」，
 * 删除方（产物清理、刮削文件删除）报 {@link #submitDeleted}，都立即返回；本类攒一批、合并成目录，再对每台开了通知的服务器：
 * </p>
 * <ol>
 *   <li>按 {@code path_mapping} 把 OSR 路径映射成媒体服务器视角；</li>
 *   <li>用它<b>实际的媒体库目录</b>过滤——不在任何库下的目录不发。这一步让调用方不必知道「哪个目录才是媒体库」：
 *       不开重命名的用户媒体库扫的是 STRM 输出目录，开了的扫的是重命名目标目录，两边都报上来，由库目录决定；
 *       也挡住了另一个坑：Emby 收到库外路径（按接口语义）照样回成功、什么都不做，不预先过滤的话「配错了」在日志里一切正常；</li>
 *   <li>同一个库下目录太多时收缩成整库，一次全量 STRM 扫描不会变成对媒体服务器的压测。</li>
 * </ol>
 * <p>
 * <b>防抖</b>：第一次提交后静默 {@link #QUIET} 无新提交才发，最长攒 {@link #MAX_WAIT}。一季剧集通常在
 * 几秒内连着落盘，没有防抖就是逐集一次请求；有了也保证持续写入时（大目录扫描）每两分钟仍会发出一批。
 * 进程关闭时没发出去的这一批直接丢弃——媒体服务器自己的定时扫描会兜底。
 * </p>
 */
@Slf4j
@Component
public class LibraryRefreshNotifier {

    static final Duration QUIET = Duration.ofSeconds(30);
    static final Duration MAX_WAIT = Duration.ofMinutes(2);

    /** 一个库下这一批的目录超过这个数就改成刷新整库 */
    static final int MAX_PATHS_PER_LIBRARY = 20;

    /** 库目录缓存：库的增删很少，而每批都要用它过滤一遍 */
    static final long ROOTS_TTL_MILLIS = 10 * 60_000L;

    /** 日志里最多列出几处，其余写「等 N 处」 */
    private static final int LOG_PREVIEW = 3;

    private final IPtMediaServerPlusService serverService;
    private final MediaServerClientFactory clientFactory;
    private final TaskScheduler scheduler;

    /** 待通知的目录（OSR 视角、已规范化），并发写入 */
    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    /**
     * 有文件被删掉的目录。与 {@link #pending} 分开放，因为它们<b>发送时可能已经不存在</b>：
     * 产物清理删完文件紧接着回收空目录，一部剧删光时季目录、剧目录都会跟着没掉。发送前上溯到最近一个
     * 还存在的目录——Plex 的局部扫描只认存在的目录；Emby/Jellyfin 扫父目录时会把已不在磁盘上的条目移出库。
     */
    private final Set<String> pendingDeleted = ConcurrentHashMap.newKeySet();

    private final Object timerLock = new Object();
    /** 以下三个字段由 timerLock 保护 */
    private boolean timerScheduled;
    private long firstSubmitAt;
    private long lastSubmitAt;

    private record CachedRoots(String url, List<LibraryRoot> roots, long expiresAt) {
    }

    private final Map<Integer, CachedRoots> rootsCache = new ConcurrentHashMap<>();

    /** 按服务器 id 节流：媒体服务器宕机期间每一批都会失败，有信息量的只有开始与恢复 */
    private final FaultThrottle failures = new FaultThrottle();

    /** 「映射后不在任何库下」按服务器 + 规则只报一次：一条规则写错，命中它的每一集都会对不上 */
    private final LogOnce unmatchedByRule = new LogOnce();

    /** 没配映射、按原路径也对不上：按服务器只提示一次（这多半是正常的，比如 STRM 输出目录本就不是媒体库） */
    private final LogOnce unmatchedNoMapping = new LogOnce();

    /** 类型不支持通知刷新：配置不改就一直成立，按服务器只报一次 */
    private final LogOnce unsupported = new LogOnce();

    public LibraryRefreshNotifier(IPtMediaServerPlusService serverService,
                                  MediaServerClientFactory clientFactory,
                                  @Qualifier("virtualScheduledExecutor") TaskScheduler scheduler) {
        this.serverService = serverService;
        this.clientFactory = clientFactory;
        this.scheduler = scheduler;
    }

    /** 报告一个新写入（或改写）的文件；按它所在的目录通知 */
    public void submitFile(Path file) {
        if (file == null) {
            return;
        }
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            submitDir(parent);
        }
    }

    /** 报告一个有内容变化的目录 */
    public void submitDir(Path dir) {
        if (dir == null) {
            return;
        }
        pending.add(LibraryPathMapping.normalizeLocal(dir.toAbsolutePath().normalize().toString()));
        armTimer();
    }

    /**
     * 报告一个被删掉的文件；按它所在的目录（发送时若已被回收，则上溯到最近一个还存在的目录）通知。
     */
    public void submitDeleted(Path file) {
        if (file == null) {
            return;
        }
        Path parent = file.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            return;
        }
        pendingDeleted.add(LibraryPathMapping.normalizeLocal(parent.toString()));
        armTimer();
    }

    private void armTimer() {
        long now = System.currentTimeMillis();
        synchronized (timerLock) {
            lastSubmitAt = now;
            if (!timerScheduled) {
                timerScheduled = true;
                firstSubmitAt = now;
                schedule(now + QUIET.toMillis());
            }
        }
    }

    private void schedule(long atMillis) {
        scheduler.schedule(Threads.wrap(this::onTimer), Instant.ofEpochMilli(atMillis));
    }

    /** 到点检查：仍有新提交且没超过最长等待就顺延，否则发出这一批 */
    void onTimer() {
        long now = System.currentTimeMillis();
        synchronized (timerLock) {
            long due = Math.min(lastSubmitAt + QUIET.toMillis(), firstSubmitAt + MAX_WAIT.toMillis());
            if (now < due) {
                schedule(due);
                return;
            }
            timerScheduled = false;
        }
        try {
            flushNow();
        } catch (Exception e) {
            // 调度器吞掉未捕获异常，不在这里记下来就什么痕迹都没有
            log.error("通知媒体服务器刷新异常：{}", e.getMessage(), e);
        }
    }

    /** 立即发出当前攒下的全部目录 */
    void flushNow() {
        List<String> dirs = drain();
        if (dirs.isEmpty()) {
            return;
        }
        List<PtMediaServerPlus> servers;
        try {
            servers = serverService.listActive().stream().filter(PtMediaServerPlus::libraryNotifyOn).toList();
        } catch (Exception e) {
            log.warn("读取媒体服务器配置失败，本批 {} 个目录不通知刷新：{}", dirs.size(), e.getMessage(), e);
            return;
        }
        if (servers.isEmpty()) {
            // 没有一台开了通知是绝大多数部署的常态，不值得 INFO
            log.debug("没有开启「入库后通知刷新」的媒体服务器，跳过 {} 个目录", dirs.size());
            return;
        }
        for (PtMediaServerPlus server : servers) {
            notifyServer(server, dirs);
        }
    }

    private List<String> drain() {
        Set<String> dirs = new LinkedHashSet<>();
        for (Iterator<String> it = pending.iterator(); it.hasNext(); ) {
            dirs.add(it.next());
            it.remove();
        }
        for (Iterator<String> it = pendingDeleted.iterator(); it.hasNext(); ) {
            String existing = nearestExistingDir(it.next());
            it.remove();
            if (existing != null) {
                dirs.add(existing);
            }
        }
        List<String> sorted = new ArrayList<>(dirs);
        sorted.sort(null);
        return sorted;
    }

    /**
     * 从 {@code dir} 起向上找第一个还存在的目录；一路都不存在（整棵树被删掉）返回 null。
     * 上溯越过媒体库目录的话，下一步的库目录过滤自然会把它挡掉，这里不必判边界。
     */
    static String nearestExistingDir(String dir) {
        Path cur = Path.of(dir.isEmpty() ? "/" : dir);
        while (cur != null && !Files.isDirectory(cur)) {
            cur = cur.getParent();
        }
        return cur == null ? null : LibraryPathMapping.normalizeLocal(cur.toString());
    }

    /** 一台服务器的处理；失败只影响这一台 */
    private void notifyServer(PtMediaServerPlus server, List<String> dirs) {
        String key = String.valueOf(server.getId());
        try {
            IMediaServerClient client = clientFactory.get(server);
            List<LibraryRoot> roots = libraryRoots(server, client);
            if (roots == null) {
                if (unsupported.firstTime(String.valueOf(server.getId()))) {
                    log.warn("媒体服务器「{}」（{}）不支持通知刷新，已跳过", server.getName(), server.getType());
                }
                return;
            }
            Plan plan = plan(server, roots, dirs);
            reportUnmatched(server, plan);
            if (plan.targets().isEmpty()) {
                return;
            }
            client.refreshPaths(server, plan.targets());
            log.info("已通知媒体服务器「{}」刷新 {} 处：{}{}", server.getName(), plan.targets().size(),
                    describe(plan.targets()),
                    plan.unmatched().isEmpty() ? "" : "（另有 " + plan.unmatched().size() + " 个目录不在它的媒体库下，未通知）");
            if (failures.onSuccess(key)) {
                log.info("媒体服务器「{}」的刷新通知已恢复", server.getName());
            }
        } catch (Exception e) {
            // 发不出去时把库目录缓存一并作废：多半是服务器换了地址或重建过，下一批重新拉
            rootsCache.remove(server.getId());
            FaultThrottle.Decision decision = failures.onFailure(key);
            if (decision.shouldReport()) {
                log.warn("通知媒体服务器「{}」刷新失败（连续 {} 次），本批 {} 个目录未通知：{}", server.getName(),
                        decision.consecutiveFailures(), dirs.size(), e.getMessage(), e);
            }
        }
    }

    /**
     * 一台服务器的发送计划。
     *
     * @param unmatched 映射后不在任何库下的目录（OSR 视角 → 映射结果）
     */
    record Plan(List<RefreshTarget> targets, Map<String, LibraryPathMapping.Mapped> unmatched) {
    }

    /** 映射、按库目录过滤、收缩。纯逻辑，单测直接调 */
    static Plan plan(PtMediaServerPlus server, List<LibraryRoot> roots, List<String> dirs) {
        LibraryPathMapping mapping = LibraryPathMapping.parse(server.getPathMapping());
        Map<LibraryRoot, List<String>> byRoot = new LinkedHashMap<>();
        Map<String, LibraryPathMapping.Mapped> unmatched = new LinkedHashMap<>();
        for (String dir : dirs) {
            LibraryPathMapping.Mapped mapped = mapping.map(dir);
            LibraryRoot root = LibraryPathMapping.findRoot(roots, mapped.path());
            if (root == null) {
                unmatched.put(dir, mapped);
            } else {
                byRoot.computeIfAbsent(root, r -> new ArrayList<>()).add(mapped.path());
            }
        }
        List<RefreshTarget> targets = new ArrayList<>();
        byRoot.forEach((root, paths) -> targets.addAll(collapse(root, paths)));
        return new Plan(targets, unmatched);
    }

    /**
     * 同一个库下的目录去重：祖先目录在列时子目录不必再发；目录太多或其中就有库目录本身时改刷整库。
     */
    static List<RefreshTarget> collapse(LibraryRoot root, List<String> paths) {
        Set<String> distinct = new LinkedHashSet<>(paths);
        if (distinct.size() > MAX_PATHS_PER_LIBRARY
                || distinct.stream().anyMatch(p -> LibraryPathMapping.isUnder(root.path(), p))) {
            return List.of(new RefreshTarget(root, null));
        }
        List<String> kept = new ArrayList<>();
        for (String path : distinct) {
            boolean covered = false;
            for (String other : distinct) {
                if (!other.equals(path) && LibraryPathMapping.isUnder(path, other)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                kept.add(path);
            }
        }
        return kept.stream().map(p -> new RefreshTarget(root, p)).toList();
    }

    /**
     * 对不上库的目录怎么报：
     * <ul>
     *   <li>命中了映射规则却对不上——用户明确配过，多半写错了，按「服务器 + 规则」WARN 一次；</li>
     *   <li>没有规则命中、原路径也对不上——这常常是正常的（STRM 输出目录只是重命名的来源，本就不是媒体库），
     *       但也可能是忘了配映射，所以按服务器 INFO 一次并把两种可能都说清楚，其余走 DEBUG。</li>
     * </ul>
     */
    private void reportUnmatched(PtMediaServerPlus server, Plan plan) {
        plan.unmatched().forEach((dir, mapped) -> {
            if (mapped.rule() != null) {
                if (unmatchedByRule.firstTime(server.getId() + "|" + mapped.rule().from())) {
                    log.warn("媒体服务器「{}」：{} 按映射「{}」换成 {} 后不在它的任何媒体库下，未通知刷新。"
                                    + "请检查路径映射（配置页「检查路径映射」可逐条核对）",
                            server.getName(), dir, mapped.rule().describe(), mapped.path());
                }
            } else if (unmatchedNoMapping.firstTime(String.valueOf(server.getId()))) {
                log.info("媒体服务器「{}」：{} 不在它的任何媒体库下，未通知刷新（没有映射规则命中，按原路径比对）。"
                                + "若这是媒体库目录，请在配置页填写路径映射；若不是（例如它只是重命名的来源目录）可忽略。此提示每台服务器只出现一次",
                        server.getName(), dir);
            } else {
                log.debug("媒体服务器「{}」：{} 不在媒体库下，跳过", server.getName(), dir);
            }
        });
    }

    private List<LibraryRoot> libraryRoots(PtMediaServerPlus server, IMediaServerClient client) throws IOException {
        long now = System.currentTimeMillis();
        CachedRoots cached = rootsCache.get(server.getId());
        if (cached != null && cached.expiresAt() > now && cached.url().equals(server.baseUrl())) {
            return cached.roots();
        }
        List<LibraryRoot> roots = client.listLibraryRoots(server);
        if (roots != null) {
            rootsCache.put(server.getId(), new CachedRoots(server.baseUrl(), List.copyOf(roots), now + ROOTS_TTL_MILLIS));
        }
        return roots;
    }

    private static String describe(List<RefreshTarget> targets) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(LOG_PREVIEW, targets.size()); i++) {
            RefreshTarget t = targets.get(i);
            parts.add(t.path() == null ? "整个媒体库「" + t.root().name() + "」" : t.path());
        }
        String text = String.join("、", parts);
        return targets.size() > LOG_PREVIEW ? text + " 等 " + targets.size() + " 处" : text;
    }
}
