package com.osr.openliststrm.orphan;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameOrphanPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.mybatisplus.service.IRenameOrphanPlusService;
import com.osr.openliststrm.scrape.ScrapeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
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

    @Autowired
    IRenameDetailPlusService renameDetailService;

    @Autowired
    IRenameOrphanPlusService renameOrphanService;

    @Autowired
    OpenlistApi openListApi;

    @Autowired
    OpenlistConfig config;

    @Autowired
    ScrapeService scrapeService;

    private record ScanCandidate(RenameDetailPlus detail, String sourcePath) {
    }

    @Override
    public ScanSummary scan() {
        LambdaQueryWrapper<RenameDetailPlus> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RenameDetailPlus::getStatus, "1").likeLeft(RenameDetailPlus::getNewName, ".strm");
        List<RenameDetailPlus> candidates = renameDetailService.list(wrapper);

        Map<Integer, RenameOrphanPlus> existingByDetailId = renameOrphanService.list().stream()
                .collect(Collectors.toMap(RenameOrphanPlus::getDetailId, o -> o, (a, b) -> a));

        String baseUrl = config.getOpenListUrl();
        boolean encoded = "1".equals(config.getOpenListStrmEncode());
        Date now = new Date();

        int localMissing = 0;
        int unparsable = 0;
        List<ScanCandidate> stage2 = new ArrayList<>();

        for (RenameDetailPlus detail : candidates) {
            Path file = Paths.get(detail.getNewPath(), detail.getNewName());
            if (!Files.exists(file)) {
                OrphanReconciler.Decision decision = OrphanReconciler.reconcile(detail, existingByDetailId.get(detail.getId()), "local_missing", now);
                if (decision.action() != OrphanReconciler.Action.SKIP) {
                    localMissing++;
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
                unparsable++;
                continue;
            }
            stage2.add(new ScanCandidate(detail, sourcePath));
        }

        Map<String, List<ScanCandidate>> byDir = stage2.stream()
                .collect(Collectors.groupingBy(c -> parentDir(c.sourcePath())));

        int sourceMissing = 0;
        int resolved = 0;
        if (!byDir.isEmpty()) {
            Semaphore semaphore = new Semaphore(config.getTraversalConcurrency());
            List<int[]> counts;
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<CompletableFuture<int[]>> futures = byDir.entrySet().stream()
                        .map(entry -> CompletableFuture.supplyAsync(() -> {
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
                        }, executor))
                        .toList();
                counts = futures.stream().map(CompletableFuture::join).toList();
            }
            for (int[] c : counts) {
                sourceMissing += c[0];
                resolved += c[1];
            }
        }

        ScanSummary summary = new ScanSummary(localMissing, sourceMissing, resolved, unparsable);
        log.info("重命名一致性检查扫描完成: 本地丢失={}, 网盘源丢失={}, 已恢复正常={}, 无法解析跳过={}",
                summary.localMissing(), summary.sourceMissing(), summary.resolved(), summary.unparsable());
        return summary;
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
                OrphanReconciler.Decision decision = OrphanReconciler.reconcile(candidate.detail(), existing, "source_missing", now);
                if (decision.action() != OrphanReconciler.Action.SKIP) {
                    sourceMissing++;
                }
                applyDecision(decision);
            }
        }
        return new int[]{sourceMissing, resolved};
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

    @Override
    public void clean(List<Integer> orphanIds) {
        if (orphanIds == null || orphanIds.isEmpty()) {
            return;
        }
        List<RenameOrphanPlus> orphans = renameOrphanService.listByIds(orphanIds);
        Date now = new Date();
        for (RenameOrphanPlus orphan : orphans) {
            RenameDetailPlus detail = renameDetailService.getById(orphan.getDetailId());
            if (detail != null) {
                if ("source_missing".equals(orphan.getReason())) {
                    deleteLocalFile(detail);
                }
                scrapeService.deleteScrapeFiles(detail);
                renameDetailService.removeById(detail.getId());
            }
            orphan.setStatus("1");
            orphan.setCleanTime(now);
        }
        renameOrphanService.updateBatchById(orphans);
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

    private void deleteLocalFile(RenameDetailPlus detail) {
        try {
            Path file = Paths.get(detail.getNewPath(), detail.getNewName());
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除本地strm文件失败: {}/{}", detail.getNewPath(), detail.getNewName(), e);
        }
    }
}
