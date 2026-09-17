package com.osr.openliststrm.service.impl;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.osr.common.utils.Threads;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.helper.AsynHelper;
import com.osr.openliststrm.helper.CopyFailReason;
import com.osr.openliststrm.helper.CopyHelper;
import com.osr.openliststrm.helper.OpenListHelper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import com.osr.openliststrm.service.ICopyService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 复制 openlist 文件（队列版，非递归，防内存泄漏）
 */
@Service
@Slf4j
public class CopyServiceImpl implements ICopyService {

    /** 复制文件处理并发度控制，最多10个虚拟线程同时处理 */
    private static final Semaphore COPY_SEMAPHORE = new Semaphore(10);

    /** 可以按记录重试的状态：失败、监控超时/任务丢失。处理中与已成功不重试 */
    static final List<String> RETRYABLE_STATUSES = List.of("2", "4");

    @Autowired
    private OpenListHelper openListHelper;

    @Autowired
    private OpenlistApi openlistApi;

    @Autowired
    private AsynHelper asynHelper;

    @Autowired
    private CopyHelper copyHelper;

    @Autowired
    private OpenlistConfig config;

    @Autowired
    private IOpenlistCopyPlusService openlistCopyPlusService;

    @Autowired
    private IOpenlistStrmPlusService openlistStrmPlusService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /**
     * 队列方式同步目录（完全替代递归）
     *
     * @param incrementalAfter 增量基准时间，为 null 时全量同步
     */
    private void syncFilesByQueue(String srcDir, String dstDir, String startRelativePath, Date incrementalAfter) {
        log.info("开始同步目录: {} {} {}", srcDir, dstDir, startRelativePath);
        if (StringUtils.isAnyBlank(srcDir, dstDir)) {
            return;
        }

        if (StringUtils.isNotBlank(startRelativePath) && startRelativePath.startsWith("/")) {
            startRelativePath = startRelativePath.substring(1);
        }

        final String rootSrcDir = StringUtils.removeEnd(srcDir, "/");
        final String rootDstDir = StringUtils.removeEnd(dstDir, "/");
        final Semaphore dirSemaphore = new Semaphore(config.getTraversalConcurrency());
        // 单次任务快照最小文件大小，避免每文件重复走配置缓存 + parseLong
        final long minSize = config.getMinFileSizeBytes();

        // 逐层并行 BFS：同一层的目录并发列举（每目录含 fs/list + 目标列举 + 一次 DB 查询），
        // 层与层之间用 join 做屏障，保证父目录已 mkdir 后子目录才被列举。
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<String> currentLevel = new java.util.ArrayList<>();
            currentLevel.add(startRelativePath == null ? "" : startRelativePath);

            while (!currentLevel.isEmpty()) {
                List<CompletableFuture<List<String>>> futures = currentLevel.stream()
                        .map(rel -> CompletableFuture.supplyAsync(Threads.wrapSupplier(
                                () -> syncOneDir(rootSrcDir, rootDstDir, rel, incrementalAfter, dirSemaphore, minSize)), executor))
                        .toList();

                List<String> nextLevel = new java.util.ArrayList<>();
                for (CompletableFuture<List<String>> f : futures) {
                    nextLevel.addAll(f.join());
                }
                currentLevel = nextLevel;
            }
        }
    }

    /**
     * 同步单个目录：列举源目录、创建缺失的目标子目录、对满足条件的视频文件提交异步复制任务，
     * 返回需要继续遍历的子目录相对路径列表。通过信号量限制并发列举数，避免压垮 AList。
     */
    private List<String> syncOneDir(String srcDir, String dstDir, String relativePath,
                                    Date incrementalAfter, Semaphore dirSemaphore, long minSize) {
        try {
            dirSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
        try {
            String srcPath = srcDir + (StringUtils.isBlank(relativePath) ? "" : "/" + relativePath);
            JSONObject listResp = openlistApi.getOpenlist(srcPath);

            if (listResp == null || listResp.getJSONObject("data") == null) {
                return Collections.emptyList();
            }

            JSONArray contents = listResp.getJSONObject("data").getJSONArray("content");
            if (contents == null || contents.isEmpty()) {
                return Collections.emptyList();
            }

            // 一次性获取目标目录下已有的名称集合，避免对每个子项都单独调用 fs/get。
            // 目标列举沿用全局 refresh 配置：需感知目标已存在文件以避免重复复制，故不降级为纯缓存读。
            String dstPath = dstDir + (StringUtils.isBlank(relativePath) ? "" : "/" + relativePath);
            Set<String> dstExistingNames = listDstNames(dstPath);

            // 一次性批量查询该目录下已处理过的文件，避免逐文件查询数据库
            Set<String> processedFileNames = openlistCopyPlusService.lambdaQuery()
                    .eq(OpenlistCopyPlus::getCopySrcPath, srcPath)
                    .in(OpenlistCopyPlus::getCopyStatus, "1", "3")
                    .list()
                    .stream()
                    .map(OpenlistCopyPlus::getCopySrcFileName)
                    .collect(Collectors.toSet());

            List<String> childDirs = new java.util.ArrayList<>();
            // 收集同一目录下需要落库的 copy 记录，处理完整个目录后统一批量写入（1次查询+至多2次批量写），
            // 替代逐文件调用 copyHelper.addCopy（每条各一次 getOne + save/update）
            List<OpenlistCopyPlus> toPersist = new java.util.ArrayList<>();
            // 收集同一目录下需要复制的文件，合并成一次 fs/copy 调用（AList copy 接口本就接受 names 列表），
            // N 次网络请求 → 1 次，显著减少对 AList 的压力。
            List<String> namesToCopy = new java.util.ArrayList<>();
            // 列目录时 OpenList 已经给了大小，顺手带进记录，不必再为每个文件单独查一次
            java.util.Map<String, Long> sizeByName = new java.util.HashMap<>();
            for (Object obj : contents) {
                JSONObject content = (JSONObject) obj;
                String name = content.getString("name");
                boolean isDir = content.getBooleanValue("is_dir");

                // 非目录 & 非视频文件，直接跳过
                if (!isDir && !openListHelper.isVideo(name)) {
                    continue;
                }

                // Transmission 删种时会先把内容挪进一个 <种子名>__<mkdtemp 6位随机> 的临时目录再删掉，
                // 遍历撞上它就会在网盘上建出空目录、并给注定要消失的文件提交复制任务。
                // 判据只能是名字，见 OpenListHelper#isTransientDir
                if (isDir && openListHelper.isTransientDir(name)) {
                    log.info("跳过疑似删种临时目录（内容正被下载器删除）: {}/{}", srcPath, name);
                    continue;
                }

                // 增量同步：跳过修改时间早于基准的文件
                if (!isDir && incrementalAfter != null) {
                    Date modified = parseModified(content.getString("modified"));
                    if (modified != null && !modified.after(incrementalAfter)) {
                        continue;
                    }
                }

                String childRelativePath =
                        StringUtils.isBlank(relativePath) ? name : relativePath + "/" + name;
                boolean existsInDst = dstExistingNames.contains(name);

                if (isDir) {
                    // 目录不存在则创建
                    if (!existsInDst) {
                        openlistApi.mkdir(dstPath + "/" + name);
                    }
                    childDirs.add(childRelativePath);
                } else {
                    if (processedFileNames.contains(name)) {
                        log.debug("文件已处理过，跳过处理 {}/{}", dstPath, name);
                        continue;
                    }
                    if (existsInDst) {
                        // 目标已存在，直接记为成功，无需复制
                        OpenlistCopyPlus copy = new OpenlistCopyPlus();
                        copy.setCopySrcPath(srcPath);
                        copy.setCopyDstPath(dstPath);
                        copy.setCopySrcFileName(name);
                        copy.setCopyDstFileName(name);
                        copy.setCopyStatus("3");
                        copy.setFileSize(content.getLongValue("size"));
                        toPersist.add(copy);
                    } else if (content.getLongValue("size") >= minSize) {
                        namesToCopy.add(name);
                        sizeByName.put(name, content.getLongValue("size"));
                    }
                }
            }

            // 同步提交批量复制任务，保证 syncFilesByQueue 返回前所有复制记录已入库，
            // 消除后续 isCopyDone 监控与异步入库之间的时序竞态。
            toPersist.addAll(submitCopyBatch(srcPath, dstPath, namesToCopy, sizeByName));
            // 整个目录一次性批量落库，而不是逐文件调度
            copyHelper.batchAddCopy(srcPath, toPersist);
            return childDirs;
        } finally {
            dirSemaphore.release();
        }
    }

    /**
     * 一次性列出目标目录下的所有名称，用于批量存在性判断
     */
    private Set<String> listDstNames(String dstPath) {
        JSONObject resp = openlistApi.getOpenlist(dstPath);
        if (resp == null || resp.getJSONObject("data") == null) {
            return Collections.emptySet();
        }
        JSONArray contents = resp.getJSONObject("data").getJSONArray("content");
        if (contents == null) {
            return Collections.emptySet();
        }
        Set<String> names = new HashSet<>();
        for (Object obj : contents) {
            names.add(((JSONObject) obj).getString("name"));
        }
        return names;
    }

    /**
     * 提交同一目录下的批量复制任务：一次 fs/copy 调用复制多个文件，按返回的 tasks 顺序回填任务 ID。
     * 返回构建好的记录列表（不在此处落库），由调用方与目录内其它记录合并后一次性批量写入。
     */
    List<OpenlistCopyPlus> submitCopyBatch(String srcPath, String dstPath, List<String> names,
                                                   java.util.Map<String, Long> sizeByName) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        JSONObject resp = openlistApi.copyOpenlist(srcPath, dstPath, names);
        boolean submitted = resp != null && Integer.valueOf(200).equals(resp.getInteger("code"))
                && resp.getJSONObject("data") != null;
        // 整批提交失败也要落成失败记录。原先直接返回空列表：这批文件既不出现在同步记录里、也没有重试入口，
        // 只在日志里留一行 warn，用户看到的是「同步跑完了，这几个文件就是没过去」。
        // 记成失败（2）不影响下次同步自动再试——已处理集合只认处理中与成功
        String failReason = submitted ? null : CopyFailReason.submitFailed(resp);
        JSONArray tasks = submitted ? resp.getJSONObject("data").getJSONArray("tasks") : null;
        if (!submitted) {
            log.warn("批量复制提交失败 {} => {}, 文件数={}, 原因={}", srcPath, dstPath, names.size(), failReason);
        } else if (tasks != null && tasks.size() != names.size()) {
            log.warn("复制任务数({})与文件数({})不一致，按顺序尽力映射: {} => {}",
                    tasks.size(), names.size(), srcPath, dstPath);
        }
        List<OpenlistCopyPlus> records = new java.util.ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            String fileName = names.get(i);
            OpenlistCopyPlus copy = new OpenlistCopyPlus();
            copy.setCopySrcPath(srcPath);
            copy.setCopyDstPath(dstPath);
            copy.setCopySrcFileName(fileName);
            copy.setCopyDstFileName(fileName);
            copy.setFileSize(sizeByName.get(fileName));
            if (submitted) {
                // AList 按 names 顺序返回 tasks，逐一映射任务 ID
                if (tasks != null && i < tasks.size()) {
                    copy.setCopyTaskId(tasks.getJSONObject(i).getString("id"));
                }
                copy.setCopyStatus("1");
            } else {
                copy.setCopyStatus("2");
                copy.setFailReason(failReason);
            }
            records.add(copy);
        }
        return records;
    }

    /**
     * 单文件同步（保持原实现）
     */
    @Override
    public void syncOneFile(String srcDir, String dstDir, String relativePath) {
        log.info("开始同步文件: {}", relativePath);
        if (!openListHelper.isVideo(relativePath)) {
            return;
        }

        if (relativePath.startsWith("/")) {
            relativePath = relativePath.substring(1);
        }

        srcDir = StringUtils.removeEnd(srcDir, "/");
        dstDir = StringUtils.removeEnd(dstDir, "/");

        String copySrcPath = srcDir;
        String copyDstPath = dstDir;
        String fileName = relativePath;

        if (relativePath.contains("/")) {
            copySrcPath = srcDir + "/" + relativePath.substring(0, relativePath.lastIndexOf("/"));
            copyDstPath = dstDir + "/" + relativePath.substring(0, relativePath.lastIndexOf("/"));
            fileName = relativePath.substring(relativePath.lastIndexOf("/") + 1);
        }

        OpenlistCopyPlus copy = new OpenlistCopyPlus();
        copy.setCopySrcPath(copySrcPath);
        copy.setCopyDstPath(copyDstPath);
        copy.setCopySrcFileName(fileName);
        copy.setCopyDstFileName(fileName);

        // 去重只属于外部事件入口（第三方回调、目录监听会对同一文件推好几次）；
        // 按记录重试不经过这里，见 retryCopy
        if (copyHelper.existsCopy(copy)) {
            log.debug("文件已处理过，跳过处理 {}/{}", dstDir, relativePath);
            return;
        }
        doSyncOneFile(copy);
    }

    /**
     * 单文件同步的执行层：不去重，拿到哪条记录就处理哪条。
     * <p>
     * {@code copy.copyId} 为空表示外部事件新发现的文件，成功提交后才落库（维持原有语义：
     * 源不存在、体积不够、提交失败都不留记录）；不为空表示按记录重试，此时记录已被
     * {@link #claimForRetry} 认领成「处理中」，<b>每条提前返回的分支都必须把它收尾</b>，
     * 否则它会一直挂在处理中，直到兜底任务过了宽限期才来接管。
     */
    void doSyncOneFile(OpenlistCopyPlus copy) {
        boolean retry = copy.getCopyId() != null;
        String srcFile = StringUtils.removeEnd(copy.getCopySrcPath(), "/") + "/" + copy.getCopySrcFileName();
        String dstFile = StringUtils.removeEnd(copy.getCopyDstPath(), "/") + "/" + copy.getCopyDstFileName();

        JSONObject dstExistResp = openlistApi.getFile(dstFile);
        if (dstExistResp != null && Integer.valueOf(200).equals(dstExistResp.getInteger("code"))) {
            // 目标已存在，直接记为成功，无需复制
            JSONObject dstData = dstExistResp.getJSONObject("data");
            if (dstData != null && dstData.containsKey("size")) {
                copy.setFileSize(dstData.getLongValue("size"));
            }
            copy.setCopyStatus("3");
            copy.setFailReason(null);
            saveCopy(copy);
            asynHelper.isCopyDoneOneFile(dstFile, copy);
            return;
        }

        JSONObject srcResp = openlistApi.getFile(srcFile);
        if (srcResp == null) {
            // AList 不可达：判不出源在不在，不能按「源已消失」删记录（口径同 CopyHelper#discardIfSourceGone）
            log.warn("查询源文件失败（OpenList 无响应）{}", srcFile);
            markRetryFailed(copy, retry, CopyFailReason.sourceQueryFailed());
            return;
        }
        if (srcResp.getJSONObject("data") == null) {
            log.warn("源文件不存在 {}", srcFile);
            if (retry) {
                // 源确实没了，这条记录永远不可能重试成功，删掉才是它的真实语义
                log.info("重试时源文件已不存在，丢弃复制记录[{}] {}", copy.getCopyId(), srcFile);
                openlistCopyPlusService.removeById(copy.getCopyId());
            }
            return;
        }
        long size = srcResp.getJSONObject("data").getLongValue("size");
        copy.setFileSize(size);
        if (size < config.getMinFileSizeBytes()) {
            log.info("源文件体积 {} 字节低于同步阈值 {} 字节，不复制 {}", size, config.getMinFileSizeBytes(), srcFile);
            markRetryFailed(copy, retry, CopyFailReason.belowMinSize(size, config.getMinFileSizeBytes()));
            return;
        }

        openlistApi.mkdir(copy.getCopyDstPath());
        JSONObject resp = openlistApi.copyOpenlist(
                copy.getCopySrcPath(),
                copy.getCopyDstPath(),
                Collections.singletonList(copy.getCopySrcFileName())
        );
        JSONArray tasks = resp == null || resp.getJSONObject("data") == null
                ? null : resp.getJSONObject("data").getJSONArray("tasks");
        if (!Integer.valueOf(200).equals(resp == null ? null : resp.getInteger("code")) || tasks == null || tasks.isEmpty()) {
            log.warn("提交复制任务失败 {} => {}", srcFile, copy.getCopyDstPath());
            markRetryFailed(copy, retry, CopyFailReason.submitFailed(resp));
            return;
        }
        copy.setFailReason(null);
        copy.setCopyTaskId(tasks.getJSONObject(0).getString("id"));
        copy.setCopyStatus("1");
        saveCopy(copy);
        asynHelper.isCopyDoneOneFile(dstFile, copy);
    }

    /**
     * 已有记录按 id 同步写回：紧接着启动的监控会拿这个对象 updateById，id 必须在手里。
     * 新记录走 CopyHelper#addCopy 的 upsert（同步，写完回填 id）。
     */
    private void saveCopy(OpenlistCopyPlus copy) {
        if (copy.getCopyId() != null) {
            openlistCopyPlusService.updateById(copy);
        } else {
            copyHelper.addCopy(copy);
        }
    }

    /** 重试时没能提交复制，把认领时置成的「处理中」退回失败；新发现的文件没有记录，无需处理 */
    private void markRetryFailed(OpenlistCopyPlus copy, boolean retry, String failReason) {
        if (!retry) {
            return;
        }
        copy.setCopyStatus("2");
        copy.setFailReason(failReason);
        openlistCopyPlusService.updateById(copy);
    }

    /**
     * 认领一条待重试的记录：条件更新 {@code 失败/未知 → 处理中}，影响行数为 1 才算抢到。
     * <p>
     * 这一步替代了原先「先把状态改成失败、好让 existsCopy 放行」的做法，同时补上那层去重
     * 顺带提供的并发保护——页面连点两次、或 TG 的 retryAllFailed 与页面重试撞在一起时，
     * 只有一方能认领成功，不会给 OpenList 提交两个复制任务。
     * <p>
     * <b>必须在信号量内、真正执行前调用，不能在入口处批量预置</b>：排队中的记录若提前变成处理中
     * 且没有任务 ID，过了宽限期会被 {@code CopyRecoveryTask} 当成无主记录接管，两条链路抢同一条记录。
     */
    boolean claimForRetry(OpenlistCopyPlus copy) {
        OpenlistCopyPlus set = new OpenlistCopyPlus();
        set.setCopyStatus("1");
        set.setCopyTaskId("");
        boolean claimed = openlistCopyPlusService.update(set, new UpdateWrapper<OpenlistCopyPlus>()
                .eq("copy_id", copy.getCopyId())
                .in("copy_status", RETRYABLE_STATUSES));
        if (claimed) {
            copy.setCopyStatus("1");
            copy.setCopyTaskId("");
            // 库里的原因已被认领一并清空（ALWAYS 策略），对象上也要清，否则后续 updateById 会把旧原因写回去
            copy.setFailReason(null);
            // 置空后由自动填充写入当前时间；否则后续 updateById 会把库里刚刷新的 update_time 写回旧值
            copy.setUpdateTime(null);
        }
        return claimed;
    }

    @Override
    public void syncFiles(String srcDir, String dstDir, String relativePath) {
        syncFilesByQueue(srcDir, dstDir, relativePath, null);
        asynHelper.isCopyDone(dstDir, relativePath);
    }

    @Override
    public void syncFiles(String srcDir, String dstDir) {
        syncFiles(srcDir, dstDir, "");
    }

    @Override
    public void syncFilesIncremental(String srcDir, String dstDir, Date lastSyncTime) {
        log.info("开始增量同步目录: {} -> {}, 基准时间: {}", srcDir, dstDir, lastSyncTime);
        syncFilesByQueue(srcDir, dstDir, "", lastSyncTime);
        asynHelper.isCopyDone(dstDir, "");
    }

    @Override
    public void batchRemoveNetDisk(List<String> idList) {
        if (idList == null || idList.isEmpty()) return;
        List<OpenlistCopyPlus> copyList = openlistCopyPlusService.listByIds(idList);
        Runnable action = () -> {
            // 外部API调用在事务外执行，单条隔离失败，只清理网盘删除成功的记录，避免网盘/DB状态不一致
            List<OpenlistCopyPlus> succeeded = new java.util.ArrayList<>();
            for (OpenlistCopyPlus copy : copyList) {
                try {
                    JSONObject resp = openlistApi.fsRemove(copy.getCopyDstPath(), Collections.singletonList(copy.getCopyDstFileName()));
                    if (resp != null && Integer.valueOf(200).equals(resp.getInteger("code"))) {
                        succeeded.add(copy);
                    } else {
                        log.warn("网盘文件删除失败，跳过对应记录清理：{}/{}", copy.getCopyDstPath(), copy.getCopyDstFileName());
                    }
                } catch (Exception e) {
                    log.error("网盘文件删除异常，跳过对应记录清理：{}/{}", copy.getCopyDstPath(), copy.getCopyDstFileName(), e);
                }
            }
            if (succeeded.isEmpty()) {
                return;
            }
            List<Integer> succeededIds = succeeded.stream().map(OpenlistCopyPlus::getCopyId).toList();
            transactionTemplate.executeWithoutResult(status -> {
                // 合并为一条 OR 条件批量删除，避免逐条 DELETE
                LambdaQueryWrapper<OpenlistStrmPlus> strmWrapper = new LambdaQueryWrapper<>();
                for (OpenlistCopyPlus copy : succeeded) {
                    strmWrapper.or(w -> w.eq(OpenlistStrmPlus::getStrmFileName, copy.getCopyDstFileName())
                            .eq(OpenlistStrmPlus::getStrmPath, copy.getCopyDstPath()));
                }
                openlistStrmPlusService.remove(strmWrapper);
                openlistCopyPlusService.removeBatchByIds(succeededIds);
            });
        };
        if (idList.size() > 20) {
            AsyncManager.me().execute(action);
        } else {
            action.run();
        }
    }

    @Override
    public int retryCopy(List<String> idList) {
        if (idList == null || idList.isEmpty()) return 0;
        List<OpenlistCopyPlus> copyList = openlistCopyPlusService.listByIds(idList).stream()
                .filter(copy -> RETRYABLE_STATUSES.contains(copy.getCopyStatus()))
                .toList();
        if (copyList.size() < idList.size()) {
            log.info("重试复制记录：选中 {} 条，其中 {} 条处于处理中/已成功或已不存在，跳过",
                    idList.size(), idList.size() - copyList.size());
        }
        if (copyList.isEmpty()) {
            return 0;
        }
        Runnable action = () -> {
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<Void>> futures = copyList.stream()
                    .map(copy -> CompletableFuture.runAsync(Threads.wrap(() -> {
                        try {
                            COPY_SEMAPHORE.acquire();
                            try {
                                if (claimForRetry(copy)) {
                                    doSyncOneFile(copy);
                                } else {
                                    log.info("复制记录[{}] 已被其它重试认领或状态已变化，跳过", copy.getCopyId());
                                }
                            } catch (Exception e) {
                                log.error("重试复制记录[{}] 失败：{}", copy.getCopyId(), e.getMessage(), e);
                            } finally {
                                COPY_SEMAPHORE.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }), executor))
                    .toList();
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
        };
        if (copyList.size() > 20) {
            AsyncManager.me().execute(action);
        } else {
            action.run();
        }
        return copyList.size();
    }

    @Override
    public RetryOutcome retryAllFailed() {
        // 与页面重试同一口径：失败 + 监控超时/任务丢失都算
        LambdaQueryWrapper<OpenlistCopyPlus> countWrapper = new LambdaQueryWrapper<>();
        countWrapper.in(OpenlistCopyPlus::getCopyStatus, RETRYABLE_STATUSES);
        long total = openlistCopyPlusService.count(countWrapper);
        if (total == 0) {
            return new RetryOutcome(0, 0);
        }

        LambdaQueryWrapper<OpenlistCopyPlus> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(OpenlistCopyPlus::getCopyStatus, RETRYABLE_STATUSES)
                .orderByDesc(OpenlistCopyPlus::getCreateTime)
                .last("LIMIT 200");
        List<OpenlistCopyPlus> failed = openlistCopyPlusService.list(wrapper);
        List<String> idList = failed.stream().map(c -> String.valueOf(c.getCopyId())).toList();
        // 报实际提交数：查询与重试之间状态可能已被监控/兜底任务改掉，按查到的条数报会虚高
        int submitted = retryCopy(idList);
        return new RetryOutcome(submitted, (int) total - idList.size());
    }

    /**
     * 解析 AList 返回的 modified 字段。
     * <p>
     * AList 通常返回带时区偏移的 ISO-8601（如 {@code 2024-01-01T12:00:00+08:00} 或以 Z 结尾），
     * 必须按偏移换算成绝对时刻再与基准时间比较；旧实现直接删掉偏移后缀会导致跨时区偏移，
     * 使增量同步漏拷或重复拷。这里优先按带偏移的 ISO-8601 解析，无偏移时才回退到服务器本地时区。
     */
    private Date parseModified(String modifiedStr) {
        if (StringUtils.isBlank(modifiedStr)) {
            return null;
        }
        String s = modifiedStr.trim();
        // 1) 带时区偏移或 Z 的 ISO-8601 —— 直接得到绝对时刻
        try {
            return Date.from(OffsetDateTime.parse(s).toInstant());
        } catch (DateTimeParseException ignore) {
            // 继续尝试其他格式
        }
        // 2) 无时区信息的 ISO-8601（含 T），按服务器本地时区解释
        try {
            return Date.from(LocalDateTime.parse(s.replace(' ', 'T'))
                    .atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ignore) {
            // 继续尝试兜底格式
        }
        // 3) 兜底：yyyy-MM-dd HH:mm:ss（本地时区）
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s.replace('T', ' '));
        } catch (ParseException e) {
            log.debug("无法解析 modified 字段: {}", modifiedStr);
            return null;
        }
    }
}