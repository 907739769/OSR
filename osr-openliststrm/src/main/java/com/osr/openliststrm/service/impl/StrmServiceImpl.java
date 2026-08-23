package com.osr.openliststrm.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.utils.Threads;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.helper.StrmHelper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmTaskPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmTaskPlusService;
import com.osr.openliststrm.rename.cleanup.ArtifactPaths;
import com.osr.openliststrm.service.IStrmService;
import com.osr.openliststrm.service.StrmSettings;
import com.osr.openliststrm.service.StrmSettingsFactory;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
@Slf4j
public class StrmServiceImpl implements IStrmService {

    /** BFS遍历收集的文件条目 */
    private record FileEntry(String path, String localPath, String name, long size) {}

    /**
     * 单次 STRM 任务的配置快照。避免在每文件的热循环里重复走 sysConfig 缓存查询与 parseLong，
     * 任务开始时取一次即可（同一次任务内配置视为不变）。
     */
    private record StrmCtx(String baseUrl, boolean encode, boolean downloadSub,
                           long minSize, boolean traversalRefresh) {}

    @Autowired
    private OpenlistConfig config;

    @Autowired
    @Qualifier("sharedOkHttpClient")
    private OkHttpClient sharedClient;

    /** 字幕下载专用客户端：带超时 + SSRF 防护 DNS（校验实际解析出的 IP，杜绝 TOCTOU） */
    private OkHttpClient downloadClient;

    @PostConstruct
    public void initDownloadClient() {
        Dns safeDns = hostname -> {
            List<InetAddress> addrs = Dns.SYSTEM.lookup(hostname);
            for (InetAddress a : addrs) {
                if (a.isLoopbackAddress() || a.isAnyLocalAddress()
                        || a.isLinkLocalAddress() || a.isSiteLocalAddress()) {
                    throw new UnknownHostException("拒绝访问内网地址: " + hostname);
                }
            }
            return addrs;
        };
        downloadClient = sharedClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .dns(safeDns)
                .build();
    }

    @Autowired
    private StrmHelper strmHelper;

    @Autowired
    private OpenListHelper openListHelper;

    @Autowired
    private OpenlistApi openListApi;

    @Autowired
    private IOpenlistStrmPlusService openlistStrmPlusService;

    @Autowired
    private IOpenlistCopyPlusService openlistCopyPlusService;

    @Autowired
    private IOpenlistStrmTaskPlusService openlistStrmTaskPlusService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final Pattern ILLEGAL_PATTERN = Pattern.compile("[\\\\/:*?\"<>|]");

    /** STRM文件处理并发度控制，最多10个虚拟线程同时处理 */
    private static final Semaphore STRM_SEMAPHORE = new Semaphore(10);

    private boolean shouldEncode() {
        return "1".equals(config.getOpenListStrmEncode());
    }

    /**
     * 解析该路径生效的 STRM 设置：全局配置叠加「覆盖该路径的任务」的任务级覆盖。
     * <p>
     * <b>按路径反查而不是让调用方传任务</b>：strmDir/strmOneFile 有一半调用方手里根本没有任务
     * 对象——复制完成后的 {@code AsynHelper}、兜底恢复的 {@code CopyRecoveryTask}、
     * TG 的 {@code /strm <路径>} 指令，它们只拿得到一个路径。这些入口若退回全局配置，
     * 同一个目录就会因为「谁触发的」而输出到不同根目录，长出两棵 STRM 树，
     * 一致性检查还会把其中一棵报成孤儿。按路径解析保证同一路径的生成规则唯一。
     * </p>
     * <p>
     * 任务表只有个位数到几十行，每次生成查一次可忽略——而每次生成本身都要打网络 IO。
     * </p>
     */
    private StrmSettings resolveSettings(String path) {
        OpenlistStrmTaskPlus task = null;
        try {
            task = pickCoveringTask(openlistStrmTaskPlusService.list(), path);
        } catch (Exception e) {
            // 查不到任务不该让生成本身失败，退回全局配置即可
            log.warn("查询 STRM 任务级覆盖失败，本次使用全局配置: {}", e.getMessage());
        }
        StrmSettings settings = StrmSettingsFactory.build(config, task == null ? null : task.getStrmOverride());
        if (task != null && StringUtils.isNotBlank(task.getStrmOverride())) {
            // 明确打出「哪条任务的覆盖、最终生效成什么」——否则用户看到 STRM 写去了别处，
            // 只能靠翻数据库里的 JSON 反推
            log.info("STRM 生成套用任务级覆盖: path={}, 任务#{}({}), 生效: 输出目录={} 下字幕={} 最小体积={}字节",
                    path, task.getStrmTaskId(), task.getStrmTaskPath(),
                    settings.outputDir(), settings.downloadSub(), settings.minSize());
        }
        return settings;
    }

    /**
     * 从任务列表里挑出覆盖 {@code path} 的那个，多个都覆盖时取路径最长（最具体）的。
     * <p>
     * 匹配必须落在路径分隔符上：{@code /电视剧} 覆盖 {@code /电视剧/三体}，
     * 但不能覆盖 {@code /电视剧2}——与 {@code subtreeLikePrefix} 是同一个坑。
     * </p>
     * <p>
     * <b>停用的任务同样参与匹配</b>：status 管的是「定时任务要不要自动跑它」，
     * 而覆盖描述的是「这个目录该怎么生成」。停用后被 TG 手动触发一次就写到另一个根目录，
     * 正是本方法要避免的那种不一致。
     * </p>
     */
    static OpenlistStrmTaskPlus pickCoveringTask(List<OpenlistStrmTaskPlus> tasks, String path) {
        if (tasks == null || path == null) {
            return null;
        }
        String target = trimTrailingSlash(path);
        OpenlistStrmTaskPlus best = null;
        int bestLen = -1;
        for (OpenlistStrmTaskPlus task : tasks) {
            if (task == null || task.getStrmTaskPath() == null) {
                continue;
            }
            String taskPath = trimTrailingSlash(task.getStrmTaskPath());
            boolean covers = target.equals(taskPath) || target.startsWith(taskPath + "/");
            if (!covers) {
                continue;
            }
            int len = taskPath.length();
            // 路径同样长时按 id 取小，保证同一份数据每次挑出同一个任务（配置重复时不至于忽左忽右）
            if (len > bestLen || (len == bestLen && idOf(task) < idOf(best))) {
                best = task;
                bestLen = len;
            }
        }
        return best;
    }

    /** 末尾斜杠规范化。根路径 "/" 归一成空串，于是它对任何绝对路径都成立前缀匹配，且长度最短、优先级最低 */
    private static String trimTrailingSlash(String path) {
        String trimmed = path.trim();
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return "/".equals(trimmed) ? "" : trimmed;
    }

    private static int idOf(OpenlistStrmTaskPlus task) {
        return (task == null || task.getStrmTaskId() == null) ? Integer.MAX_VALUE : task.getStrmTaskId();
    }

    @Override
    public void strmDir(String path) {
        log.info("开始执行指定路径strm任务: {}", path);
        try {
            getData(path, resolveSettings(path));
        } catch (Exception e) {
            log.error("strm任务执行异常: {}", path, e);
        } finally {
            log.info("strm任务执行完成: {}", path);
        }
    }

    @Override
    public void strmOneFile(String path) {
        log.info("开始执行指定文件strm任务: {}", path);
        String filePath = "";
        String name = path;
        if (path.contains("/")) {
            filePath = path.substring(0, path.lastIndexOf("/"));
            name = path.substring(path.lastIndexOf("/") + 1);
        }

        if (strmHelper.existsStrm(filePath, name)) {
            log.debug("文件已处理过，跳过处理{}", path);
            return;
        }
        String fileName = path.substring(path.lastIndexOf("/") + 1, path.lastIndexOf(".")).replaceAll("[\\\\/:*?\"<>|]", "");
        String relative = filePath.startsWith("/")
                ? filePath.substring(1)
                : filePath;
        StrmSettings settings = resolveSettings(path);
        Path outputBase = Paths.get(settings.outputDir()).normalize();
        Path targetDir = resolveWithinBase(outputBase, relative.replace("/", File.separator));
        if (targetDir == null) {
            log.error("拒绝路径穿越：strm目标目录超出输出根目录 {}, path={}", settings.outputDir(), path);
            strmHelper.addStrm(filePath, name, "0");
            return;
        }
        File file = targetDir.toFile();
        if (!file.exists()) {
            file.mkdirs();
        }
        Path strmFile = targetDir.resolve((fileName.length() > 255 ? fileName.substring(0, 250) : fileName) + ".strm");
        try {
            String encodePath = path;
            if (shouldEncode()) {
                encodePath = URLEncoder.encode(path, StandardCharsets.UTF_8.name())
                        .replace("+", "%20")
                        .replace("%2F", "/");
            }
            String content = config.getOpenListUrl() + "/d" + encodePath;
            writeAtomically(strmFile, content);
            strmHelper.addStrm(filePath, name, "1");
        } catch (Exception e) {
            log.error("生成 .strm 文件失败 {}", strmFile, e);
            strmHelper.addStrm(filePath, name, "0");
        }
        log.info("执行指定文件strm任务完成: {}", path);
    }

    @Override
    public void batchRemoveNetDisk(List<String> idList) {
        if (idList == null || idList.isEmpty()) return;
        List<OpenlistStrmPlus> strmList = openlistStrmPlusService.listByIds(idList);
        Runnable action = () -> {
            // 外部API调用在事务外执行，单条隔离失败，只清理网盘删除成功的记录，避免网盘/DB状态不一致
            List<OpenlistStrmPlus> succeeded = new java.util.ArrayList<>();
            for (OpenlistStrmPlus strm : strmList) {
                try {
                    JSONObject resp = openListApi.fsRemove(strm.getStrmPath(), Collections.singletonList(strm.getStrmFileName()));
                    if (resp != null && Integer.valueOf(200).equals(resp.getInteger("code"))) {
                        succeeded.add(strm);
                    } else {
                        log.warn("网盘文件删除失败，跳过对应记录清理：{}/{}", strm.getStrmPath(), strm.getStrmFileName());
                    }
                } catch (Exception e) {
                    log.error("网盘文件删除异常，跳过对应记录清理：{}/{}", strm.getStrmPath(), strm.getStrmFileName(), e);
                }
            }
            if (succeeded.isEmpty()) {
                return;
            }
            List<Integer> succeededIds = succeeded.stream().map(OpenlistStrmPlus::getStrmId).toList();
            transactionTemplate.executeWithoutResult(status -> {
                // 合并为一条 OR 条件批量删除，避免逐条 DELETE
                LambdaQueryWrapper<OpenlistCopyPlus> copyWrapper = new LambdaQueryWrapper<>();
                for (OpenlistStrmPlus strm : succeeded) {
                    copyWrapper.or(w -> w.eq(OpenlistCopyPlus::getCopyDstFileName, strm.getStrmFileName())
                            .eq(OpenlistCopyPlus::getCopyDstPath, strm.getStrmPath()));
                }
                openlistCopyPlusService.remove(copyWrapper);
                openlistStrmPlusService.removeBatchByIds(succeededIds);
            });
        };
        if (idList.size() > 20) {
            AsyncManager.me().execute(action);
        } else {
            action.run();
        }
    }

    @Override
    public void retryStrm(List<String> idList) {
        if (idList == null || idList.isEmpty()) return;
        List<OpenlistStrmPlus> strmList = openlistStrmPlusService.listByIds(idList);
        strmList.forEach(strm -> strm.setStrmStatus("0"));
        openlistStrmPlusService.updateBatchById(strmList);
        Runnable action = () -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = strmList.stream()
                    .map(strm -> CompletableFuture.runAsync(Threads.wrap(() -> {
                        try {
                            STRM_SEMAPHORE.acquire();
                            try {
                                strmOneFile(strm.getStrmPath() + "/" + strm.getStrmFileName());
                            } finally {
                                STRM_SEMAPHORE.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }), executor))
                    .toList();
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
        };
        if (idList.size() > 20) {
            AsyncManager.me().execute(action);
        } else {
            action.run();
        }
    }

    @Override
    public RetryOutcome retryAllFailed() {
        LambdaQueryWrapper<OpenlistStrmPlus> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.eq(OpenlistStrmPlus::getStrmStatus, "0");
        long total = openlistStrmPlusService.count(countWrapper);
        if (total == 0) {
            return new RetryOutcome(0, 0);
        }

        LambdaQueryWrapper<OpenlistStrmPlus> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OpenlistStrmPlus::getStrmStatus, "0")
                .orderByDesc(OpenlistStrmPlus::getCreateTime)
                .last("LIMIT 200");
        List<OpenlistStrmPlus> failed = openlistStrmPlusService.list(wrapper);
        List<String> idList = failed.stream().map(s -> String.valueOf(s.getStrmId())).toList();
        retryStrm(idList);
        return new RetryOutcome(idList.size(), (int) total - idList.size());
    }

    public void getData(String rootPath, StrmSettings settings) {
        // 单次任务配置快照，避免每文件热循环重复取配置。
        // 输出根目录 / downloadSub / minSize 来自 settings（全局配置叠加任务级覆盖），
        // encode 仍取全局：它有解码侧消费者，理由见 StrmSettingsFactory 的类注释
        String localRootPath = settings.outputDir();
        StrmCtx ctx = new StrmCtx(config.getOpenListUrl(), shouldEncode(), settings.downloadSub(),
                settings.minSize(), config.getTraversalRefresh());

        // 第一阶段：并行 BFS 遍历收集所有待处理文件。
        // 目录列举是网络 IO（每目录一次 fs/list），逐层并发列举可显著缩短大目录树的遍历耗时。
        // 用无锁的 ConcurrentLinkedQueue 承接并发 add，避免 synchronizedList 的锁竞争。
        java.util.Queue<FileEntry> fileEntries = new java.util.concurrent.ConcurrentLinkedQueue<>();
        Semaphore dirSemaphore = new Semaphore(config.getTraversalConcurrency());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> currentLevel = new java.util.ArrayList<>();
            currentLevel.add(rootPath);

            while (!currentLevel.isEmpty()) {
                List<CompletableFuture<List<String>>> futures = currentLevel.stream()
                        .map(path -> CompletableFuture.supplyAsync(Threads.wrapSupplier(
                                () -> listDirCollect(path, localRootPath, fileEntries, dirSemaphore, ctx)), executor))
                        .toList();

                List<String> nextLevel = new java.util.ArrayList<>();
                for (CompletableFuture<List<String>> f : futures) {
                    nextLevel.addAll(f.join());
                }
                currentLevel = nextLevel;
            }
        }

        int fileEntryCount = fileEntries.size();
        log.info("BFS遍历完成，共收集到 {} 个待处理文件", fileEntryCount);

        // 一次性批量查出该目录树下已有的 strm 记录，用途两个：
        // 1) successKeys（仅成功记录）用于处理阶段跳过已成功的文件；
        // 2) failedIdByKey（仅非成功记录）用于批量落库阶段判断 insert 还是 update——
        //    openlist_strm 表 (strm_path, strm_file_name) 无唯一约束，无法用 ON DUPLICATE KEY UPSERT，
        //    需要显式知道已存在记录的主键才能走 updateBatchById。
        //
        // 前缀匹配补路径分隔符 + 转义 LIKE 通配符，口径与 ScrapeService#hasSiblingInSameShow 一致：
        // 不补分隔符时 /电视剧/三体 会连 /电视剧/三体2 一起捞；不转义时路径里的 _（发布组命名的常态）
        // 在 LIKE 里是"任意单字符"。两者都只造成过取（recordKey 是精确匹配，多捞的行 key 对不上，
        // 不会误跳过文件也不会判错 insert/update），但白读的行会实打实占住内存。
        String rootDir = normalizedRoot(rootPath);
        List<OpenlistStrmPlus> existingList = openlistStrmPlusService.lambdaQuery()
                .and(w -> w.eq(OpenlistStrmPlus::getStrmPath, rootDir)
                        .or().likeRight(OpenlistStrmPlus::getStrmPath, subtreeLikePrefix(rootPath)))
                .select(OpenlistStrmPlus::getStrmId, OpenlistStrmPlus::getStrmPath,
                        OpenlistStrmPlus::getStrmFileName, OpenlistStrmPlus::getStrmStatus)
                .list();
        ExistingIndex existing = indexExisting(existingList);
        Set<String> existingKeys = existing.successKeys();
        Map<String, Integer> existingIdByKey = existing.failedIdByKey();

        // 第二阶段：虚拟线程并行处理文件，处理结果（待写入的DB记录）先收集，处理完成后统一批量落库，
        // 避免每文件各自调度一次异步单行查询+insert/update（N 次数据库往返）
        List<OpenlistStrmPlus> pendingRecords;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<OpenlistStrmPlus>>> futures = fileEntries.stream()
                .map(entry -> CompletableFuture.supplyAsync(Threads.wrapSupplier(() -> {
                    try {
                        STRM_SEMAPHORE.acquire();
                        try {
                            return processFileEntry(entry, existingKeys, ctx);
                        } finally {
                            STRM_SEMAPHORE.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Collections.<OpenlistStrmPlus>emptyList();
                    }
                }), executor).exceptionally(ex -> {
                    // 单个文件处理异常不应导致整批已收集的记录全部无法落库，兜底为空列表后继续
                    log.error("处理文件条目异常 {} / {}", entry.path(), entry.name(), ex);
                    return Collections.emptyList();
                }))
                .toList();
            pendingRecords = futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .toList();
        }
        strmHelper.batchAddStrm(pendingRecords, existingIdByKey);
        // 打 pendingRecords 而不是 fileEntryCount：后者是 BFS 扫到的<b>输入</b>数，
        // 而真正生成的是前者。扫了 500 个文件、因为非媒体/太小/已处理而一个 .strm 都没生成时，
        // 原先那行会说「共处理 500 个文件」——读起来完全像成功了
        log.info("STRM 文件并行处理完成：扫描 {} 个文件，生成 {} 个 .strm 记录",
                fileEntryCount, pendingRecords.size());
    }

    /**
     * 子树内已有记录的索引，由 {@link #indexExisting} 一次遍历建好。
     *
     * @param successKeys  已成功记录的 key，处理阶段据此跳过
     * @param failedIdByKey 非成功记录的 key -> 主键，落库阶段据此判断 insert 还是 update
     */
    record ExistingIndex(Set<String> successKeys, Map<String, Integer> failedIdByKey) {
    }

    /**
     * 把子树内已有的记录拆成"已成功"和"未成功"两个索引。
     * <p>
     * <b>failedIdByKey 刻意不装成功记录</b>，这是这里最省内存的一处：能走到 pendingRecords 的文件，
     * 一定不在 successKeys 里（{@link #processFileEntry} 开头就把它们跳过了），所以落库阶段
     * 永远不会拿一个成功记录的 key 去查 id。健康的库里失败记录是极少数，这个 map 因此从
     * "整个库" 缩到 "几十条"，同时省掉一次全量的 recordKey 拼接——两个索引原先各拼一遍，
     * 产生两批内容相同的独立 String。
     * <p>
     * 未成功的判据用 {@code !"1".equals(status)} 而不是 {@code "0".equals(status)}：
     * strm_status 允许为 NULL（建表时就是 {@code NULL DEFAULT NULL}），历史行可能没有状态值。
     * 按 "0" 收的话这些行会被漏掉，落库时当成"没有记录"走 insert，凭空多出一行重复记录。
     * 反过来漏收成功记录是安全的——它们本来就用不到。
     */
    static ExistingIndex indexExisting(List<OpenlistStrmPlus> rows) {
        Set<String> successKeys = new java.util.HashSet<>();
        Map<String, Integer> failedIdByKey = new java.util.HashMap<>();
        if (rows == null) {
            return new ExistingIndex(successKeys, failedIdByKey);
        }
        for (OpenlistStrmPlus row : rows) {
            String key = StrmHelper.recordKey(row.getStrmPath(), row.getStrmFileName());
            if ("1".equals(row.getStrmStatus())) {
                successKeys.add(key);
            } else {
                failedIdByKey.putIfAbsent(key, row.getStrmId());
            }
        }
        return new ExistingIndex(successKeys, failedIdByKey);
    }

    /** 去掉末尾斜杠的根路径；记录里的 strm_path 也是这个形态（listDirCollect 做过同样的 removeEnd） */
    static String normalizedRoot(String rootPath) {
        return StringUtils.removeEnd(rootPath == null ? "" : rootPath, "/");
    }

    /**
     * 子树前缀匹配用的 LIKE 值：规范化根路径 + 路径分隔符，值本身过 {@code escapeLike}。
     * <p>
     * 根路径为 {@code /} 时规范化成空串，前缀退化为 {@code /}，配合 likeRight 就是
     * {@code LIKE '/%'}——匹配全部记录，正是"整库任务"应有的语义。
     */
    static String subtreeLikePrefix(String rootPath) {
        return ArtifactPaths.escapeLike(normalizedRoot(rootPath)) + "/";
    }

    /**
     * 这个文件值不值得进 {@code fileEntries}。
     * <p>
     * 判据必须与 {@link #processFileEntry} 的开头两个分支等价：不是视频也不是字幕的直接不收，
     * 关掉字幕下载时字幕也不收（那种情况下 processFileEntry 对字幕产出的是空列表，纯白跑）。
     * <p>
     * 刮削过的库里每个影片目录还躺着 poster.jpg / fanart.jpg / .nfo / logo.png，
     * 实际文件数是视频数的好几倍。原先它们全部进了队列、各自占一个 FileEntry、
     * 还各自被调度一次 CompletableFuture，只为在 processFileEntry 第一行 return 掉。
     * 判定成本没有变化——本来也是每文件判一次，只是挪早了。
     */
    private boolean isCollectible(String rawName, StrmCtx ctx) {
        if (openListHelper.isVideo(rawName)) {
            return true;
        }
        return ctx.downloadSub() && openListHelper.isSrt(rawName);
    }

    /**
     * 列举单个目录：创建对应本地目录、收集其中的文件条目、返回子目录路径列表（供下一层遍历）。
     * 通过信号量限制并发列举数，避免压垮 AList。
     */
    private List<String> listDirCollect(String rawPath, String localRootPath,
                                        java.util.Queue<FileEntry> fileEntries, Semaphore dirSemaphore, StrmCtx ctx) {
        String currentPath = StringUtils.removeEnd(rawPath, "/");
        String currentLocalPath = localRootPath + File.separator + currentPath.replace("/", File.separator);
        File currentDir = new File(currentLocalPath);
        if (!currentDir.exists()) {
            currentDir.mkdirs();
        }

        try {
            dirSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
        try {
            // 遍历列举默认走 AList 缓存（不刷新），大幅加速大目录树遍历
            JSONObject jsonObject = openListApi.getOpenlist(currentPath, ctx.traversalRefresh());
            if (jsonObject == null || jsonObject.getInteger("code") != 200
                    || jsonObject.getJSONObject("data") == null) {
                return Collections.emptyList();
            }

            JSONArray jsonArray = jsonObject.getJSONObject("data").getJSONArray("content");
            if (jsonArray == null) {
                return Collections.emptyList();
            }

            List<String> childDirs = new java.util.ArrayList<>();
            for (Object obj : jsonArray) {
                JSONObject object = (JSONObject) obj;
                String rawName = object.getString("name");
                boolean isDir = object.getBoolean("is_dir");
                long size = object.getLongValue("size");

                if (isDir) {
                    childDirs.add(currentPath + "/" + rawName);
                } else if (isCollectible(rawName, ctx)) {
                    fileEntries.add(new FileEntry(currentPath, currentLocalPath, rawName, size));
                }
            }
            return childDirs;
        } finally {
            dirSemaphore.release();
        }
    }

    /**
     * 处理单个文件条目（从 getData 中提取出的文件处理逻辑）。
     * 返回本文件产生的待写入DB记录（0~2条：视频/字幕各一条），由调用方统一批量落库。
     */
    private List<OpenlistStrmPlus> processFileEntry(FileEntry entry, Set<String> existingKeys, StrmCtx ctx) {
        String currentPath = entry.path();
        String currentLocalPath = entry.localPath();
        String rawName = entry.name();
        long size = entry.size();

        if (existingKeys.contains(StrmHelper.recordKey(currentPath, rawName))) {
            if (log.isDebugEnabled()) {
                log.debug("文件已处理过，跳过处理 {} / {}", currentPath, rawName);
            }
            return Collections.emptyList();
        }

        if (!openListHelper.isVideo(rawName) && !openListHelper.isSrt(rawName)) {
            if (log.isDebugEnabled()) {
                log.debug("跳过非媒体文件：{}", rawName);
            }
            return Collections.emptyList();
        }

        int dot = rawName.lastIndexOf('.');
        String baseName = (dot > 0) ? rawName.substring(0, dot) : rawName;
        String safeName = ILLEGAL_PATTERN.matcher(baseName).replaceAll("");
        String fileName = safeName.length() > 255 ? safeName.substring(0, 250) : safeName;

        List<OpenlistStrmPlus> records = new java.util.ArrayList<>(2);

        if (openListHelper.isVideo(rawName)) {
            if (size < ctx.minSize()) {
                log.debug("跳过小文件：{}（{} 字节）", rawName, size);
                return records;
            }

            Path strmFile = Paths.get(currentLocalPath).resolve(fileName + ".strm");
            try {
                String encodePath = currentPath + "/" + rawName;
                if (ctx.encode()) {
                    encodePath = URLEncoder.encode(encodePath, StandardCharsets.UTF_8.name())
                            .replace("+", "%20")
                            .replace("%2F", "/");
                }
                String content = ctx.baseUrl() + "/d" + encodePath;
                writeAtomically(strmFile, content);
                records.add(strmHelper.newRecord(currentPath, rawName, "1"));
            } catch (Exception e) {
                log.error("写入 .strm 文件失败 {}", strmFile, e);
                records.add(strmHelper.newRecord(currentPath, rawName, "0"));
            }
        }

        if (ctx.downloadSub() && openListHelper.isSrt(rawName)) {
            try {
                JSONObject fileJson = openListApi.getFile(currentPath + "/" + rawName);
                if (fileJson != null && fileJson.getJSONObject("data") != null) {
                    String url = fileJson.getJSONObject("data").getString("raw_url");
                    File outFile = new File(currentLocalPath + File.separator + fileName + rawName.substring(rawName.lastIndexOf(".")));
                    downloadSubtitle(url, outFile.getAbsolutePath());
                    records.add(strmHelper.newRecord(currentPath, rawName, "1"));
                }
            } catch (Exception e) {
                log.error("下载字幕失败 {} / {}", currentPath, rawName, e);
                records.add(strmHelper.newRecord(currentPath, rawName, "0"));
            }
        }

        return records;
    }

    /**
     * 将 relativePath 解析到 base 目录下，并校验规范化后的结果仍在 base 内。
     * relativePath 可能来自外部回调（如 qBittorrent 下载完成通知），需防范 ".." 路径穿越写出输出目录。
     * @return 校验通过返回规范化后的绝对路径，越界返回 null。
     */
    private static Path resolveWithinBase(Path base, String relativePath) {
        Path resolved = StringUtils.isBlank(relativePath) ? base : base.resolve(relativePath).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    // SSRF防护：仅校验协议；内网地址的拦截交由 downloadClient 的自定义 DNS 在真正解析时完成，
    // 保证校验与实际连接使用同一解析结果，杜绝 TOCTOU。
    private static void validateScheme(String fileURL) {
        try {
            URI uri = new URI(fileURL);
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("不允许的URL协议: " + scheme);
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("无效的URL: " + fileURL);
        }
    }

    /**
     * 下载字幕文件。使用带超时的 OkHttp 客户端（连接 15s / 读取 60s），避免源站挂起时
     * 永久阻塞并占住虚拟线程与 STRM 信号量；SSRF 防护通过 downloadClient 的自定义 DNS 生效。
     */
    private void downloadSubtitle(String fileURL, String savePath) {
        if (StringUtils.isBlank(fileURL)) {
            return;
        }
        validateScheme(fileURL);
        Request request = new Request.Builder().url(fileURL).get().build();
        try (Response response = downloadClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("下载失败, HTTP " + response.code());
            }
            Path target = Paths.get(savePath);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (InputStream in = response.body().byteStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            log.error("字幕文件下载失败: {}", fileURL);
            throw new RuntimeException("Download failed: " + fileURL, ex);
        }
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path tmpFile = target.resolveSibling(target.getFileName().toString() + ".tmp");
        boolean moved = false;
        try {
            Files.write(tmpFile, content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmpFile, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            moved = true;
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(tmpFile);
            }
        }
    }

}
