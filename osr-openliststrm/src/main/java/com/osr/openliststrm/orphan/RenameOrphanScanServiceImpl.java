package com.osr.openliststrm.orphan;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.utils.DateUtils;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameOrphanPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameOrphanPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService;
import com.osr.openliststrm.rename.cleanup.ArtifactPaths;
import com.osr.openliststrm.rename.cleanup.RenameCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RenameOrphanScanServiceImpl implements IRenameOrphanScanService {

    /**
     * 单轮反向扫描最多落库的发现数，文件级与目录级<b>各自独立</b>。首次在一个积压已久的库上跑，
     * 无主文件可能上万条，全量写进去只会让页面变成不可用的噪音墙。超出部分记入
     * {@code truncated} 并打日志——静默截断会读成"已经扫全了"，那比不扫更危险。
     * <p>
     * 两个额度必须分开：{@code visitFile} 在遍历顺序上永远排在 {@code postVisitDirectory} 前面，
     * 共用一个池子时文件级发现会把目录级发现结构性地饿死——而 metadata_only / empty_dir
     * 恰恰是这个方向最想抓的东西（只删记录留下的残骸、改标题重试留下的鬼剧集），
     * 数量级又天然小得多，凭什么跟成千上万的无主文件抢同一份额度。
     */
    private static final int MAX_FILE_FINDINGS = 1500;

    /** 目录级发现（metadata_only + empty_dir）的单轮落库上限，见 {@link #MAX_FILE_FINDINGS} */
    private static final int MAX_DIR_FINDINGS = 500;

    /**
     * 反向扫描判定目录级问题的最小深度（相对 {@code targetRoot/电影} 或 {@code targetRoot/电视剧}）。
     * 0=顶层锚点、1=分类目录，这两层空着是正常的骨架；2 起才是剧集/电影目录，空了才有意义。
     */
    private static final int MIN_DIR_DEPTH = 2;

    @Autowired
    IRenameDetailPlusService renameDetailService;

    @Autowired
    IRenameOrphanPlusService renameOrphanService;

    @Autowired
    OpenlistApi openListApi;

    @Autowired
    OpenlistConfig config;

    @Autowired
    RenameCleanupService cleanupService;

    @Autowired
    IRenameTaskPlusService renameTaskService;

    @Autowired
    OpenListHelper openListHelper;

    private record ScanCandidate(RenameDetailPlus detail, String sourcePath) {
    }

    /**
     * 反向扫描的一个起点：媒体锚点目录 + 这个目录「归重命名任务管」的起始时刻。
     *
     * @param anchor          {@code targetRoot/电影} 或 {@code targetRoot/电视剧}
     * @param baselineMillis  基线时刻，取自该 targetRoot 对应任务的 create_time；
     *                        0 表示没有基线（任务行的 create_time 为空），此时退化为全量判定
     */
    private record ScanRoot(Path anchor, long baselineMillis) {
    }

    @Override
    public ScanSummary scan() {
        List<RenameOrphanPlus> allOrphans = renameOrphanService.list();
        Date now = new Date();

        ForwardCounters forward = scanForward(allOrphans, now);
        ExtraCounters extra = scanExtras(allOrphans, now);

        ScanSummary summary = new ScanSummary(forward.localMissing, forward.sourceMissing,
                forward.resolved + extra.resolved, forward.unparsable,
                extra.localExtra, extra.metadataOnly, extra.emptyDir, extra.truncated(),
                extra.baselineSkipped);
        log.info("重命名一致性检查扫描完成: 本地丢失={}, 网盘源丢失={}, 无主文件={}, 仅元数据目录={}, 空目录={}, 已恢复正常={}, 无法解析跳过={}, 早于基线跳过={}, 超上限丢弃={}(文件级{}/目录级{})",
                summary.localMissing(), summary.sourceMissing(), summary.localExtra(), summary.metadataOnly(),
                summary.emptyDir(), summary.resolved(), summary.unparsable(), summary.baselineSkipped(),
                summary.truncated(), extra.truncatedFile, extra.truncatedDir);
        return summary;
    }

    // ------------------------------------------------------------------
    // 正向：记录 -> 文件
    // ------------------------------------------------------------------

    private static final class ForwardCounters {
        int localMissing;
        int sourceMissing;
        int resolved;
        int unparsable;
    }

    private ForwardCounters scanForward(List<RenameOrphanPlus> allOrphans, Date now) {
        ForwardCounters counters = new ForwardCounters();

        LambdaQueryWrapper<RenameDetailPlus> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RenameDetailPlus::getStatus, "1").isNotNull(RenameDetailPlus::getNewName);
        List<RenameDetailPlus> candidates = renameDetailService.list(wrapper);

        Map<Integer, RenameOrphanPlus> existingByDetailId = allOrphans.stream()
                .filter(o -> o.getDetailId() != null)
                .collect(Collectors.toMap(RenameOrphanPlus::getDetailId, o -> o, (a, b) -> a));

        String baseUrl = config.getOpenListUrl();
        boolean encoded = "1".equals(config.getOpenListStrmEncode());

        List<ScanCandidate> stage2 = new ArrayList<>();

        for (RenameDetailPlus detail : candidates) {
            Path file = Paths.get(detail.getNewPath(), detail.getNewName());
            if (!Files.exists(file)) {
                OrphanReconciler.Decision decision = OrphanReconciler.reconcile(detail, existingByDetailId.get(detail.getId()), OrphanReason.LOCAL_MISSING, now);
                if (decision.action() != OrphanReconciler.Action.SKIP) {
                    counters.localMissing++;
                }
                applyDecision(decision);
                continue;
            }
            // 网盘源核对只对 STRM 成立：真实视频副本没有指向网盘的内容可解析，
            // 本地文件还在就是一致的，到此为止
            if (!openListHelper.isStrm(detail.getNewName())) {
                OrphanReconciler.Decision decision = OrphanReconciler.reconcile(detail, existingByDetailId.get(detail.getId()), null, now);
                if (decision.action() == OrphanReconciler.Action.DELETE) {
                    counters.resolved++;
                }
                applyDecision(decision);
                continue;
            }
            String content;
            try {
                content = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("读取strm文件内容失败，跳过网盘源检测: {}", file, e);
                continue;
            }
            String sourcePath = StrmSourcePathResolver.resolve(content, baseUrl, encoded);
            if (sourcePath == null) {
                counters.unparsable++;
                continue;
            }
            stage2.add(new ScanCandidate(detail, sourcePath));
        }

        Map<String, List<ScanCandidate>> byDir = stage2.stream()
                .collect(Collectors.groupingBy(c -> parentDir(c.sourcePath())));

        if (!byDir.isEmpty()) {
            Semaphore semaphore = new Semaphore(config.getTraversalConcurrency());
            List<int[]> counts;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<int[]>> futures = byDir.entrySet().stream()
                        .map(entry -> CompletableFuture.supplyAsync(com.osr.common.utils.Threads.wrapSupplier(() -> {
                            try {
                                semaphore.acquire();
                                try {
                                    return checkDirGroup(entry.getKey(), entry.getValue(), existingByDetailId, now);
                                } finally {
                                    semaphore.release();
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return new int[]{0, 0};
                            }
                        }), executor))
                        .toList();
                counts = futures.stream().map(CompletableFuture::join).toList();
            }
            for (int[] c : counts) {
                counters.sourceMissing += c[0];
                counters.resolved += c[1];
            }
        }
        return counters;
    }

    /**
     * 核对单个网盘目录下一组候选文件是否仍然存在，返回 {source_missing数量, 已恢复正常数量}。
     */
    private int[] checkDirGroup(String dir, List<ScanCandidate> group, Map<Integer, RenameOrphanPlus> existingByDetailId, Date now) {
        JSONObject resp = openListApi.getOpenlist(dir, false);
        if (resp == null) {
            // API 调用失败（网络异常/超时/JSON解析失败，openListApi 内部重试3次后仍失败），
            // 无法确认网盘源是否真的消失，跳过本组，避免瞬时故障误判为 source_missing
            log.warn("核对网盘目录失败，跳过本轮该目录下 {} 条候选记录: {}", group.size(), dir);
            return new int[]{0, 0};
        }

        Set<String> existingNames;
        boolean dirGone = !Integer.valueOf(200).equals(resp.getInteger("code")) || resp.getJSONObject("data") == null;
        if (dirGone) {
            existingNames = Set.of();
        } else {
            JSONArray content = resp.getJSONObject("data").getJSONArray("content");
            existingNames = content == null ? Set.of() : content.stream()
                    .map(o -> ((JSONObject) o).getString("name"))
                    .collect(Collectors.toCollection(HashSet::new));
        }

        int sourceMissing = 0;
        int resolved = 0;
        for (ScanCandidate candidate : group) {
            String fileName = fileNameOf(candidate.sourcePath());
            RenameOrphanPlus existing = existingByDetailId.get(candidate.detail().getId());
            if (existingNames.contains(fileName)) {
                OrphanReconciler.Decision decision = OrphanReconciler.reconcile(candidate.detail(), existing, null, now);
                if (decision.action() == OrphanReconciler.Action.DELETE) {
                    resolved++;
                }
                applyDecision(decision);
            } else {
                OrphanReconciler.Decision decision = OrphanReconciler.reconcile(candidate.detail(), existing, OrphanReason.SOURCE_MISSING, now);
                if (decision.action() != OrphanReconciler.Action.SKIP) {
                    sourceMissing++;
                }
                applyDecision(decision);
            }
        }
        return new int[]{sourceMissing, resolved};
    }

    // ------------------------------------------------------------------
    // 反向：文件 -> 记录
    // ------------------------------------------------------------------

    private static final class ExtraCounters {
        int localExtra;
        int metadataOnly;
        int emptyDir;
        int resolved;
        int truncatedFile;
        int truncatedDir;
        int baselineSkipped;

        int truncated() {
            return truncatedFile + truncatedDir;
        }
    }

    /** 遍历一个目录时累计的内容构成，用于在 postVisitDirectory 判定目录级问题 */
    private static final class DirStat {
        int mediaFiles;
        int metadataFiles;
        int otherFiles;
        int subDirs;
        /** 目录自身的最后修改时刻，进目录时就取好——postVisitDirectory 拿不到 attrs */
        long modifiedMillis;
    }

    private ExtraCounters scanExtras(List<RenameOrphanPlus> allOrphans, Date now) {
        ExtraCounters counters = new ExtraCounters();

        List<ScanRoot> roots = mediaRoots();
        if (roots.isEmpty()) {
            log.debug("没有配置任何重命名任务的目标目录，跳过反向扫描");
            return counters;
        }

        Set<String> known = knownArtifactKeys();
        Map<String, RenameOrphanPlus> existingByPath = allOrphans.stream()
                .filter(o -> o.getDetailId() == null)
                .collect(Collectors.toMap(o -> pathKey(o.getNewPath(), o.getNewName()), o -> o, (a, b) -> a));

        Set<String> foundKeys = new LinkedHashSet<>();

        for (ScanRoot root : roots) {
            walkRoot(root, known, existingByPath, foundKeys, counters, now);
        }

        // 上一轮记过、这一轮没再发现的反向孤儿 = 已经解决了（用户清了、或者重新生成了记录），
        // 从待处理列表里移除。已忽略的（status=2）不动，那是用户的决定
        for (Map.Entry<String, RenameOrphanPlus> entry : existingByPath.entrySet()) {
            RenameOrphanPlus existing = entry.getValue();
            if (foundKeys.contains(entry.getKey()) || !"0".equals(existing.getStatus())) {
                continue;
            }
            renameOrphanService.removeById(existing.getId());
            counters.resolved++;
        }
        return counters;
    }

    private void walkRoot(ScanRoot root, Set<String> known, Map<String, RenameOrphanPlus> existingByPath,
                          Set<String> foundKeys, ExtraCounters counters, Date now) {
        Deque<DirStat> stack = new ArrayDeque<>();
        Deque<Integer> depths = new ArrayDeque<>();
        try {
            // 不跟随符号链接（walkFileTree 的默认行为），避免挂载点自引用把遍历带进死循环
            Files.walkFileTree(root.anchor(), new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    DirStat stat = new DirStat();
                    stat.modifiedMillis = attrs.lastModifiedTime().toMillis();
                    stack.push(stat);
                    depths.push(depths.isEmpty() ? 0 : depths.peek() + 1);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    DirStat stat = stack.peek();
                    String name = file.getFileName().toString();
                    if (openListHelper.isStrm(name) || openListHelper.isVideo(name)) {
                        // 计数必须排在基线判定之前：被基线放过的历史文件同样是"这个目录里有主媒体文件"
                        // 的证据，漏计会让整个目录被判成 metadata_only / empty_dir，
                        // 而那两种是可以点清理的——等于拿基线换来一次误删
                        if (stat != null) {
                            stat.mediaFiles++;
                        }
                        if (beforeBaseline(attrs.lastModifiedTime().toMillis(), root)) {
                            counters.baselineSkipped++;
                            return FileVisitResult.CONTINUE;
                        }
                        Path dir = file.getParent();
                        String key = pathKey(dir == null ? null : dir.toString(), name);
                        if (!known.contains(key)) {
                            record(key, dir == null ? null : dir.toString(), name, name,
                                    OrphanReason.LOCAL_EXTRA, existingByPath, foundKeys, counters, now);
                        }
                    } else if (ArtifactPaths.isMetadataFile(name)) {
                        if (stat != null) {
                            stat.metadataFiles++;
                        }
                    } else if (stat != null) {
                        stat.otherFiles++;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("反向扫描读取失败，跳过: {}", file, exc);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    DirStat stat = stack.pop();
                    int depth = depths.pop();
                    DirStat parent = stack.peek();
                    if (parent != null) {
                        parent.subDirs++;
                    }
                    if (exc != null || depth < MIN_DIR_DEPTH) {
                        return FileVisitResult.CONTINUE;
                    }
                    String dirName = dir.getFileName() == null ? dir.toString() : dir.getFileName().toString();
                    boolean nothingAtAll = stat.mediaFiles == 0 && stat.metadataFiles == 0
                            && stat.otherFiles == 0 && stat.subDirs == 0;
                    boolean metadataOnly = stat.mediaFiles == 0 && stat.metadataFiles > 0
                            && stat.otherFiles == 0 && stat.subDirs == 0;
                    // 目录同样吃基线：任务创建之前就在、此后没被动过的目录不是重命名留下的残骸。
                    // 判定放在这里而不是方法开头，是为了让 baselineSkipped 只统计"本来会上报的"目录——
                    // 库里绝大多数目录压根不是发现项，一并计进去这个数字就没法读了
                    if ((nothingAtAll || metadataOnly) && beforeBaseline(stat.modifiedMillis, root)) {
                        counters.baselineSkipped++;
                        return FileVisitResult.CONTINUE;
                    }
                    if (nothingAtAll) {
                        record(pathKey(dir.toString(), null), dir.toString(), null, dirName,
                                OrphanReason.EMPTY_DIR, existingByPath, foundKeys, counters, now);
                    } else if (metadataOnly) {
                        record(pathKey(dir.toString(), null), dir.toString(), null, dirName,
                                OrphanReason.METADATA_ONLY, existingByPath, foundKeys, counters, now);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("反向扫描目录失败: {}", root, e);
        }
    }

    /**
     * 某个时刻是否早于基线。基线为 0（任务行没有 create_time）时恒为 false，即退化为不过滤。
     */
    private static boolean beforeBaseline(long millis, ScanRoot root) {
        return root.baselineMillis() > 0 && millis < root.baselineMillis();
    }

    /**
     * 落一条反向发现。超过单轮上限时只累加 truncated 并跳过写库——注意仍然不计入 foundKeys，
     * 因此被丢弃的项在下一轮会重新出现，不会永久丢失。
     */
    private void record(String key, String newPath, String newName, String title, String reason,
                        Map<String, RenameOrphanPlus> existingByPath, Set<String> foundKeys,
                        ExtraCounters counters, Date now) {
        // 先登记"本轮见过"，再判上限与忽略。三条路径（正常落库 / 超上限丢弃 / 用户已忽略）
        // 都必须算见过，否则下面的"已恢复"清扫会把它们当成问题已解决而删掉，
        // 下一轮再重新发现——列表来回抖动，用户的忽略也白点了
        foundKeys.add(key);
        boolean dirLevel = !OrphanReason.LOCAL_EXTRA.equals(reason);
        if (dirLevel) {
            if (counters.metadataOnly + counters.emptyDir >= MAX_DIR_FINDINGS) {
                counters.truncatedDir++;
                return;
            }
        } else if (counters.localExtra >= MAX_FILE_FINDINGS) {
            counters.truncatedFile++;
            return;
        }
        RenameOrphanPlus existing = existingByPath.get(key);
        OrphanReconciler.Decision decision =
                OrphanReconciler.reconcileExtra(newPath, newName, title, null, existing, reason, now);
        if (decision.action() == OrphanReconciler.Action.SKIP) {
            return;
        }
        applyDecision(decision);
        if (OrphanReason.LOCAL_EXTRA.equals(reason)) {
            counters.localExtra++;
        } else if (OrphanReason.METADATA_ONLY.equals(reason)) {
            counters.metadataOnly++;
        } else if (OrphanReason.EMPTY_DIR.equals(reason)) {
            counters.emptyDir++;
        }
    }

    /**
     * 所有重命名任务目标目录下的 电影/电视剧 两个锚点，去重后作为反向扫描的起点，
     * 每个锚点附带一个基线时刻。
     * <p>
     * 基线取任务的 {@code create_time}：任务建起来之前就躺在库里、此后又没被动过的东西，
     * 不是这个任务产出的，也就不该被判成"无主"。没有这道闸的话，一个混着历史文件的媒体库
     * 每轮都会把成千上万个与重命名无关的文件报成 local_extra，把额度占满，
     * 真正想抓的残骸（metadata_only / empty_dir）反而一条都露不出来。
     * <p>
     * 同一个锚点被多个任务共用时取<b>最早</b>的 create_time：晚建的那个任务不该把早建任务
     * 的产物挡在基线之外。任一任务缺 create_time 则退化为 0（不过滤），这是保守方向——
     * 宁可多报也不漏报。
     */
    private List<ScanRoot> mediaRoots() {
        List<RenameTaskPlus> tasks = renameTaskService.list();
        Map<Path, Long> baselineByAnchor = new LinkedHashMap<>();
        for (RenameTaskPlus task : tasks) {
            String targetRoot = task.getTargetRoot();
            if (targetRoot == null || targetRoot.isBlank()) {
                continue;
            }
            // create_time 在 BaseEntity 里是 String（MyMetaObjectHandler 按 yyyy-MM-dd HH:mm:ss 填），
            // 解析不出来时 parseDate 返回 null，与"这行本来就没有创建时间"同样处理
            Date created = DateUtils.parseDate(task.getCreateTime());
            long baseline = created == null ? 0L : created.getTime();
            if (baseline == 0L) {
                log.warn("重命名任务 id={} 没有可用的创建时间，反向扫描对 {} 退化为全量判定（历史文件会被报成无主）",
                        task.getId(), targetRoot);
            }
            Path base = Paths.get(targetRoot.replaceAll("[/\\\\]+$", "")).toAbsolutePath().normalize();
            for (String topLevel : ArtifactPaths.MEDIA_TOP_LEVELS) {
                Path anchor = base.resolve(topLevel);
                if (Files.isDirectory(anchor)) {
                    baselineByAnchor.merge(anchor, baseline, Math::min);
                }
            }
        }
        return baselineByAnchor.entrySet().stream()
                .map(e -> new ScanRoot(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    /** rename_detail 里所有成功记录指向的产物路径，作为"有主"的判据 */
    private Set<String> knownArtifactKeys() {
        // 用列名而非 lambda：LambdaQueryWrapper#select 会立刻解析实体的 lambda 缓存，
        // 那份缓存要等 Mapper 注册后才有，纯单测里直接抛 "can not find lambda cache"
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<RenameDetailPlus> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        wrapper.eq("status", "1")
                .isNotNull("new_name")
                .select("new_path", "new_name");
        return renameDetailService.list(wrapper).stream()
                .map(d -> pathKey(d.getNewPath(), d.getNewName()))
                .collect(Collectors.toCollection(HashSet::new));
    }

    /**
     * 去重键。路径必须归一化后再拼：数据库里存的是 {@code Path#toString} 的结果，
     * 遍历得到的也是 Path，但配置里的 target_root 可能带尾斜杠或 {@code ./}，
     * 不归一化会让同一个文件在两侧算出不同的键，于是每个文件都被判成"无主"。
     * 分隔用 \0 而不是路径分隔符，避免目录名里含分隔符时产生歧义。
     */
    private static String pathKey(String dir, String name) {
        String normalizedDir = dir == null ? "" : Paths.get(dir).toAbsolutePath().normalize().toString();
        return normalizedDir + '\0' + (name == null ? "" : name);
    }

    private void applyDecision(OrphanReconciler.Decision decision) {
        switch (decision.action()) {
            case INSERT -> renameOrphanService.save(decision.toPersist());
            case UPDATE -> renameOrphanService.updateById(decision.toPersist());
            case DELETE -> renameOrphanService.removeById(decision.toPersist().getId());
            case SKIP -> {
                // 无需处理
            }
        }
    }

    private static String parentDir(String sourcePath) {
        int idx = sourcePath.lastIndexOf('/');
        return idx > 0 ? sourcePath.substring(0, idx) : "/";
    }

    private static String fileNameOf(String sourcePath) {
        int idx = sourcePath.lastIndexOf('/');
        return idx >= 0 ? sourcePath.substring(idx + 1) : sourcePath;
    }

    // ------------------------------------------------------------------
    // 清理 / 忽略
    // ------------------------------------------------------------------

    @Override
    public void clean(List<Integer> orphanIds) {
        if (orphanIds == null || orphanIds.isEmpty()) {
            return;
        }
        List<RenameOrphanPlus> orphans = renameOrphanService.listByIds(orphanIds);
        Date now = new Date();
        for (RenameOrphanPlus orphan : orphans) {
            try {
                cleanOne(orphan);
            } catch (Exception e) {
                // 单条失败不能中断整批，否则前面已经删过文件、后面的记录状态又没更新，
                // 用户看到的是一份对不上的列表
                log.warn("清理孤儿记录失败 id={} reason={}", orphan.getId(), orphan.getReason(), e);
                continue;
            }
            orphan.setStatus("1");
            orphan.setCleanTime(now);
        }
        renameOrphanService.updateBatchById(orphans);
    }

    private void cleanOne(RenameOrphanPlus orphan) {
        String reason = orphan.getReason();
        if (OrphanReason.LOCAL_EXTRA.equals(reason)) {
            cleanupService.purgeExtraFile(Paths.get(orphan.getNewPath(), orphan.getNewName()));
            return;
        }
        if (OrphanReason.METADATA_ONLY.equals(reason)) {
            cleanupService.purgeMetadataOnlyDir(Paths.get(orphan.getNewPath()));
            return;
        }
        if (OrphanReason.EMPTY_DIR.equals(reason)) {
            cleanupService.reclaimEmptyDirs(Paths.get(orphan.getNewPath()));
            return;
        }
        // local_missing / source_missing：连产物带记录一起清
        if (orphan.getDetailId() == null) {
            return;
        }
        RenameDetailPlus detail = renameDetailService.getById(orphan.getDetailId());
        if (detail != null) {
            cleanupService.purge(List.of(detail), true);
        }
    }

    @Override
    public void ignore(List<Integer> orphanIds) {
        if (orphanIds == null || orphanIds.isEmpty()) {
            return;
        }
        List<RenameOrphanPlus> orphans = renameOrphanService.listByIds(orphanIds);
        Date now = new Date();
        orphans.forEach(o -> {
            o.setStatus("2");
            o.setCleanTime(now);
        });
        renameOrphanService.updateBatchById(orphans);
    }
}
