package com.osr.openliststrm.monitor.processor;

import com.osr.common.utils.LogOnce;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.Threads;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameCategoryRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameTaskPlusService;
import com.osr.openliststrm.rename.MediaParser;
import com.osr.openliststrm.rename.RenameClientProvider;
import com.osr.openliststrm.rename.RenameEventListener;
import com.osr.openliststrm.rename.config.IRenameTemplateConfigService;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.openliststrm.rename.rule.CategoryClassifier;
import com.osr.openliststrm.rename.rule.CategoryRuleConverter;
import com.osr.openliststrm.scrape.ScrapeService;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * 媒体文件重命名处理器
 *
 * @author: Jack
 * @creat: 2026/1/12 14:45
 */
@Slf4j
public class MediaRenameProcessor implements FileProcessor {

    private final Path targetRoot;
    private final RenameEventListener renameListener;
    private final OpenListHelper openListHelper;
    private final long minFileSizeBytes;

    private final RenameClientProvider clientProvider;
    private final IRenameTaskPlusService taskService;
    private final IRenameCategoryRulePlusService categoryRuleService;
    private final IRenameTemplateConfigService templateConfigService;
    private ScrapeService scrapeService;

    /** 按目标路径加锁，使用引用计数避免"释放后又被新线程复用同一把已失效锁"的竞态 */
    private static final ConcurrentMap<String, LockEntry> FILE_LOCKS = new ConcurrentHashMap<>();
    private final Set<Path> processing = ConcurrentHashMap.newKeySet();

    /**
     * 「文件仍在写入」按路径去重。这是<b>持续状态</b>不是事件：文件每被写一次就来一个
     * ENTRY_MODIFY，同一个路径每轮都得到同一个答案。实测一个临时文件在 3 秒内刷了 569 行。
     * 文件转为稳定时 forget，之后它再次被改写还会照常记一次。
     */
    private final LogOnce writingLogged = new LogOnce();

    /** processOnce 批量扫描时的并发处理度（虚拟线程），避免大目录下逐文件串行 + isFileStable 固定2s导致耗时线性叠加 */
    private static final int PROCESS_ONCE_CONCURRENCY = 8;

    /**
     * scrapeAsyncFile 对应的 RenameTaskPlus 懒加载缓存：同一 processor 实例的 targetRoot 不变，
     * 避免批量处理时每个文件都重复按 targetRoot 查一次任务配置（N+1）。
     * 该实例在任务配置变更时会被 RenameMonitorRegistry/RenameTaskManager 整体重建，故不存在缓存过期问题。
     */
    private volatile RenameTaskPlus cachedTask;
    private volatile boolean taskLookedUp = false;
    private final Object taskLookupLock = new Object();

    private static final class LockEntry {
        private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
        private final java.util.concurrent.atomic.AtomicInteger refCount = new java.util.concurrent.atomic.AtomicInteger(0);
    }

    /**
     * 按 key 加锁执行 action，锁对象在最后一个持有者退出时才从 map 中移除，
     * 避免 computeIfAbsent+remove 模拟锁在并发场景下出现两把不同锁保护同一 key 的问题。
     */
    private static void withFileLock(String key, IOAction action) throws IOException {
        LockEntry entry = FILE_LOCKS.compute(key, (k, v) -> {
            if (v == null) v = new LockEntry();
            v.refCount.incrementAndGet();
            return v;
        });
        entry.lock.lock();
        try {
            action.run();
        } finally {
            entry.lock.unlock();
            FILE_LOCKS.computeIfPresent(key, (k, v) -> v.refCount.decrementAndGet() <= 0 ? null : v);
        }
    }

    @FunctionalInterface
    private interface IOAction {
        void run() throws IOException;
    }


    public MediaRenameProcessor(
            Path targetRoot,
            RenameClientProvider clientProvider,
            OpenListHelper openListHelper,
            OpenlistConfig config,
            RenameEventListener renameListener
    ) {
        this.targetRoot = targetRoot;
        this.renameListener = renameListener;
        this.openListHelper = openListHelper;
        this.minFileSizeBytes =
                Optional.ofNullable(config)
                        .map(OpenlistConfig::getOpenListMinFileSize)
                        .filter(StringUtils::isNotBlank)
                        .map(v -> Long.parseLong(v) * 1024 * 1024)
                        .orElse(10 * 1024 * 1024L);
        this.clientProvider = clientProvider;
        this.taskService = SpringUtils.getBean(IRenameTaskPlusService.class);
        this.categoryRuleService = SpringUtils.getBean(IRenameCategoryRulePlusService.class);
        this.templateConfigService = SpringUtils.getBean(IRenameTemplateConfigService.class);
        this.scrapeService = SpringUtils.getBean(ScrapeService.class);
    }

    @Override
    public void process(Path file) {
        Path p = file.toAbsolutePath().normalize();
        String fileName = p.toString();
        // 1. 忽略下载器/网盘客户端的在途产物（清单与判据见 FileStabilityUtils）
        if (FileStabilityUtils.isTransientArtifact(p)) {
            return;
        }
        //判断文件是否还在写入中
        if (!FileStabilityUtils.isFileStable(p)) {
            if (writingLogged.firstTime(fileName)) {
                log.debug("文件仍在写入，稍后再试：{}", p);
            }
            return;
        }
        writingLogged.forget(fileName);
        if (!processing.add(p)) {
            log.debug("跳过重复处理：{}", p);
            return;
        }
        try {
            handleFile(p);
        } catch (Exception e) {
            log.error("处理文件失败：{}", p, e);
        } finally {
            processing.remove(p);
        }
    }

    public void handleOneFile(Path file, String title, String year, String season, String episode) {
        try {
            handleFileIfReady(file, title, year, season, episode);
        } catch (IOException e) {
            log.error("处理单个文件失败 {}", file, e);
        }
    }

    /**
     * //     * 执行一次完整扫描并处理源目录下的所有文件（用于手动/批量触发任务）。
     * //     * 与长期监控不同：不会注册 WatchService，仅遍历现有文件并按常规流程处理。
     * //
     */
    public void processOnce(Path sourceDir) {
        try {
            if (!Files.exists(sourceDir) || !Files.isDirectory(sourceDir)) {
                log.warn("源目录不存在或不是目录，本轮跳过：{}", sourceDir);
                return;
            }
            IRenameDetailPlusService renameDetailPlusService = SpringUtils.getBean(IRenameDetailPlusService.class);
            // 一次性批量查出该目录下已处理成功的文件，避免遍历时逐文件查询数据库
            String sourceDirStr = sourceDir.toAbsolutePath().normalize().toString();
            Set<String> processedKeys = renameDetailPlusService.lambdaQuery()
                    .likeRight(RenameDetailPlus::getOriginalPath, sourceDirStr)
                    .eq(RenameDetailPlus::getStatus, "1")
                    .list()
                    .stream()
                    .map(r -> r.getOriginalPath() + " " + r.getOriginalName())
                    .collect(java.util.stream.Collectors.toSet());

            // 先收集全部待处理文件，再用虚拟线程并发处理；原实现在 walkFileTree 回调里逐个同步处理，
            // 每个文件仅 isFileStable 就固定阻塞2s，大目录下耗时随文件数线性叠加
            List<Path> pendingFiles = new ArrayList<>();
            Files.walkFileTree(sourceDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // Transmission 删种时挪出来的临时目录整棵跳过：里面的文件正在被删，
                    // 处理它们只会得到一批半截产物和失败记录。见 OpenListHelper#isTransientDir。
                    // 起始目录永远不跳——它要是碰巧长得像临时目录，跳过等于静默取消整次扫描
                    if (!dir.equals(sourceDir) && openListHelper != null
                            && dir.getFileName() != null
                            && openListHelper.isTransientDir(dir.getFileName().toString())) {
                        log.info("processOnce 跳过疑似删种临时目录: {}", dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    Path p = file.toAbsolutePath().normalize();
                    String key = p.getParent().toString() + " " + p.getFileName().toString();
                    if (!processedKeys.contains(key)) {
                        pendingFiles.add(p);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            Semaphore semaphore = new Semaphore(PROCESS_ONCE_CONCURRENCY);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = pendingFiles.stream()
                        .map(p -> CompletableFuture.runAsync(Threads.wrap(() -> {
                            try {
                                semaphore.acquire();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                            try {
                                processing.add(p);
                                try {
                                    handleFileIfReady(p);
                                } catch (Exception e) {
                                    log.error("单文件就绪检查失败：{}", p, e);
                                } finally {
                                    processing.remove(p);
                                }
                            } finally {
                                semaphore.release();
                            }
                        }), executor))
                        .toList();
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
        } catch (IOException e) {
            log.error("单轮重命名扫描失败", e);
        }
    }

    private void handleFile(Path file) throws Exception {
        handleFileIfReady(file);
    }

    private void handleFileIfReady(Path file) throws IOException {
        handleFileIfReady(file, null, null, null, null);
    }

    private void handleFileIfReady(Path file, String title, String year, String season, String episode) throws IOException {
        // Step 1: 验证文件是否就绪
        Path p = file.toAbsolutePath().normalize();
        if (!validateFile(p)) return;

        // Step 2: 解析媒体信息
        MediaParser parser = new MediaParser(clientProvider.tmdb(), clientProvider.openAI());
        MediaInfo info = parseMediaInfo(parser, p, title, year, season, episode);
        if (info == null) return;

        String mediaType = (info.getSeason() != null || info.getEpisode() != null) ? "tv" : "movie";
        if (info.getTmdbId() == null || info.getTmdbId().trim().isEmpty()) {
            log.info("未找到 tmdbId，跳过文件处理：{} ; parsed info title={}", p, info.getTitle());
            notifyFailed(p, info, mediaType, "tmdbId not found");
            return;
        }

        // Step 3: 构建目标路径
        Path[] destResult = buildDestPath(p, info, mediaType, parser);
        Path finalDestDir = destResult[0];
        Path destFile = destResult[1];

        // Step 4: 复制文件
        if (!copyFileToDest(p, destFile, finalDestDir, file.getFileName().toString())) return;

        // Step 5: 通知和刮削
        notifyAndScrape(p, destFile, info, mediaType, finalDestDir);
    }

    /** Step 1: 验证文件存在、类型和大小 */
    private boolean validateFile(Path p) throws IOException {
        if (!Files.exists(p) || Files.isDirectory(p)) return false;
        long size = Files.size(p);
        String filename = p.getFileName().toString();

        if (openListHelper != null) {
            boolean isStrm = openListHelper.isStrm(filename);
            boolean isVideo = openListHelper.isVideo(filename);
            if (!isStrm && !isVideo) {
                log.debug("跳过非视频文件：{}", p);
                return false;
            }
            if (!isStrm && size < minFileSizeBytes) {
                log.debug("跳过小文件：{}（{} 字节）", p, size);
                return false;
            }
        } else if (size < minFileSizeBytes) {
            log.debug("跳过小文件 {} ({} bytes)", p, size);
            return false;
        }

        if (!FileStabilityUtils.isFileStable(p)) {
            if (writingLogged.firstTime(p.toString())) {
                log.debug("文件仍在写入，稍后再试：{}", p);
            }
            return false;
        }
        writingLogged.forget(p.toString());
        return true;
    }

    /** Step 2: 解析媒体信息 */
    private MediaInfo parseMediaInfo(MediaParser parser, Path p, String title, String year, String season, String episode) {
        MediaInfo info = parser.parse(p.getFileName().toString(), title, year);
        if (StringUtils.isNotEmpty(season)) info.setSeason(season);
        if (StringUtils.isNotEmpty(episode)) info.setEpisode(episode);
        return info;
    }

    /** Step 3: 构建目标路径，返回 [finalDestDir, destFile] */
    private Path[] buildDestPath(Path p, MediaInfo info, String mediaType, MediaParser parser) throws IOException {
        String category = classify(info, mediaType);
        if (category == null) category = "未分类";

        String topLevel = "movie".equals(mediaType) ? "电影" : "电视剧";
        Path destDir = targetRoot.resolve(topLevel).resolve(category);
        Files.createDirectories(destDir);

        String rendered = parser.render(info, templateConfigService.getTemplate());
        String filename = p.getFileName().toString();
        String newFilename = (rendered == null || rendered.trim().isEmpty()) ? filename : rendered.trim();
        newFilename = newFilename.replace('\\', '/');

        Path finalDestDir = destDir;
        String fileNameOnly = newFilename;
        if (newFilename.contains("/")) {
            String[] parts = newFilename.split("/");
            List<String> cleanParts = new ArrayList<>();
            for (String part : parts) {
                if (part == null) continue;
                part = part.trim();
                if (part.isEmpty() || ".".equals(part) || "..".equals(part)) continue;
                cleanParts.add(sanitizeForPath(part));
            }
            if (cleanParts.isEmpty()) {
                fileNameOnly = sanitizeForPath(filename);
            } else {
                for (int i = 0; i < cleanParts.size() - 1; i++) {
                    finalDestDir = finalDestDir.resolve(cleanParts.get(i));
                }
                fileNameOnly = cleanParts.get(cleanParts.size() - 1);
            }
        } else {
            fileNameOnly = sanitizeForPath(newFilename);
        }

        Files.createDirectories(finalDestDir);
        return new Path[]{finalDestDir, finalDestDir.resolve(fileNameOnly)};
    }

    /** Step 4: 复制文件到目标路径（含文件锁保护） */
    private boolean copyFileToDest(Path source, Path destFile, Path finalDestDir, String originalFilename) throws IOException {
        String lockKey = destFile.toAbsolutePath().normalize().toString();
        withFileLock(lockKey, () -> {
            Path tmpFile = finalDestDir.resolve(destFile.getFileName().toString() + ".tmp");
            try {
                if (openListHelper.isStrm(originalFilename)) {
                    Files.copy(source, destFile, REPLACE_EXISTING);
                } else {
                    Files.copy(source, tmpFile, REPLACE_EXISTING);
                    Files.move(tmpFile, destFile, StandardCopyOption.ATOMIC_MOVE);
                }
                log.info("已复制并重命名 {} -> {}", source, destFile);
            } finally {
                Files.deleteIfExists(tmpFile);
            }
        });
        return true;
    }

    /** Step 5: 通知监听器并触发刮削 */
    private void notifyAndScrape(Path source, Path destFile, MediaInfo info, String mediaType, Path outputDir) {
        Integer detailId = null;
        if (renameListener != null) {
            try {
                detailId = renameListener.onRename(source, destFile, info, mediaType);
            } catch (Exception e) {
                log.warn("重命名成功回调失败：{}", e.getMessage());
            }
        }
        try {
            scrapeAsyncFile(destFile, info, mediaType, outputDir, detailId);
        } catch (Exception e) {
            log.warn("启动刮削失败: {}", e.getMessage());
        }
    }

    private void notifyFailed(Path p, MediaInfo info, String mediaType, String reason) {
        if (renameListener != null) {
            try {
                renameListener.onRenameFailed(p, targetRoot, info, mediaType, reason);
            } catch (Exception e) {
                log.warn("重命名失败回调本身失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 异步触发刮削（NFO + 图片下载）。
     * 从任务配置中读取 scrape 开关。
     */
    private void scrapeAsyncFile(Path destFile, MediaInfo info, String mediaType, Path outputDir, Integer detailId) {
        try {
            RenameTaskPlus task = resolveTask();
            if (task == null) {
                log.warn("未找到 targetRoot={} 对应的重命名任务，跳过刮削", targetRoot);
                return;
            }

            // 检查刮削配置：只有启用了刮削才执行
            if (!"1".equals(task.getScrapeEnabled())) {
                log.debug("任务 {} 未启用刮削，跳过", task.getId());
                return;
            }

            scrapeService.scrapeAsync(
                    detailId, info, mediaType, destFile, outputDir,
                    task.getScrapeEnabled(), task.getScrapeNfo(), task.getScrapeImages(),
                    "1".equals(task.getScrapeForceOverwrite())
            );
        } catch (Exception e) {
            log.warn("查询任务配置失败: {}", e.getMessage());
        }
    }

    /**
     * 懒加载并缓存本 processor 实例对应的 RenameTaskPlus（按 targetRoot 查询）。
     * processOnce 批量扫描时避免每个文件都重复查询一次（N+1）。
     */
    private RenameTaskPlus resolveTask() {
        if (taskLookedUp) return cachedTask;
        synchronized (taskLookupLock) {
            if (!taskLookedUp) {
                String targetRootPath = targetRoot.toString().replaceAll("/+$", "");
                cachedTask = taskService.getOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RenameTaskPlus>()
                                .eq(RenameTaskPlus::getTargetRoot, targetRootPath)
                                .last("LIMIT 1")
                );
                taskLookedUp = true;
            }
        }
        return cachedTask;
    }

    private String sanitizeForPath(String s) {
        // remove characters invalid in Windows paths
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private String classify(MediaInfo info, String mediaType) {
        List<RenameCategoryRulePlus> rows = categoryRuleService.listEnabledRules(mediaType);
        if (rows.isEmpty()) {
            log.warn("未配置 mediaType={} 的分类规则，使用兜底目录", mediaType);
            return "未分类";
        }
        String category = CategoryClassifier.classify(CategoryRuleConverter.toCategoryRules(rows), info);
        return category != null ? category : "未分类";
    }

}
