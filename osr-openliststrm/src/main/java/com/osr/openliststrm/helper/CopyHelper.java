package com.osr.openliststrm.helper;

import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistCopyPlusService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class CopyHelper {

    /** 批量查询已存在记录时，IN 子句每批携带的最大文件名数量 */
    private static final int LOOKUP_CHUNK_SIZE = 1000;

    @Autowired
    private IOpenlistCopyPlusService openlistCopyPlusService;

    public void addCopy(OpenlistCopyPlus openlistCopyPlus) {
        AsyncManager.me().execute(() -> {
            try {
                OpenlistCopyPlus existing = openlistCopyPlusService.getOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OpenlistCopyPlus>()
                                .eq(OpenlistCopyPlus::getCopySrcPath, openlistCopyPlus.getCopySrcPath())
                                .eq(OpenlistCopyPlus::getCopySrcFileName, openlistCopyPlus.getCopySrcFileName())
                );
                if (existing != null) {
                    openlistCopyPlus.setCopyId(existing.getCopyId());
                    openlistCopyPlusService.updateById(openlistCopyPlus);
                } else {
                    openlistCopyPlusService.save(openlistCopyPlus);
                }
            } catch (MybatisPlusException e) {
                if (e.getMessage() != null && e.getMessage().contains("Duplicate entry")) {
                    log.debug("Copy record already exists: path={}, fileName={}",
                            openlistCopyPlus.getCopySrcPath(), openlistCopyPlus.getCopySrcFileName());
                } else {
                    log.error("Error adding copy: path={}, fileName={}",
                            openlistCopyPlus.getCopySrcPath(), openlistCopyPlus.getCopySrcFileName(), e);
                }
            } catch (Exception e) {
                log.error("Error adding copy: path={}, fileName={}",
                        openlistCopyPlus.getCopySrcPath(), openlistCopyPlus.getCopySrcFileName(), e);
            }
        });
    }

    /**
     * 批量新增/更新同一源目录下的 copy 记录，用于目录级批量同步场景。
     * <p>
     * openlist_copy 表上 (copy_src_path, copy_src_file_name) 没有唯一约束，无法用单条
     * ON DUPLICATE KEY UPSERT。改为：先按这两列批量查出该目录下已存在的记录（1次查询），
     * 回填 copyId 后分两组分别 saveBatch / updateBatchById（各1次批量写入）。
     * 相比逐条 addCopy（每条各一次 getOne + save/update），把 2N 次数据库往返压缩为 O(1) 次。
     * <p>
     * 调用方（{@code syncOneDir}）已运行在后台虚拟线程上，且需要保证方法返回时记录已入库
     * （下游 isCopyDone 监控依赖这一点），因此这里同步执行，不包 AsyncManager 延迟调度。
     */
    public void batchAddCopy(String srcPath, List<OpenlistCopyPlus> copies) {
        if (copies == null || copies.isEmpty()) {
            return;
        }
        try {
            List<String> names = copies.stream().map(OpenlistCopyPlus::getCopySrcFileName).toList();
            Map<String, Integer> existingIds = new HashMap<>();
            for (int from = 0; from < names.size(); from += LOOKUP_CHUNK_SIZE) {
                int to = Math.min(from + LOOKUP_CHUNK_SIZE, names.size());
                List<OpenlistCopyPlus> existing = openlistCopyPlusService.lambdaQuery()
                        .eq(OpenlistCopyPlus::getCopySrcPath, srcPath)
                        .in(OpenlistCopyPlus::getCopySrcFileName, names.subList(from, to))
                        .select(OpenlistCopyPlus::getCopyId, OpenlistCopyPlus::getCopySrcFileName)
                        .list();
                for (OpenlistCopyPlus e : existing) {
                    existingIds.putIfAbsent(e.getCopySrcFileName(), e.getCopyId());
                }
            }

            List<OpenlistCopyPlus> toInsert = new ArrayList<>();
            List<OpenlistCopyPlus> toUpdate = new ArrayList<>();
            for (OpenlistCopyPlus copy : copies) {
                Integer existingId = existingIds.get(copy.getCopySrcFileName());
                if (existingId != null) {
                    copy.setCopyId(existingId);
                    toUpdate.add(copy);
                } else {
                    toInsert.add(copy);
                }
            }
            if (!toInsert.isEmpty()) {
                openlistCopyPlusService.saveBatch(toInsert);
            }
            if (!toUpdate.isEmpty()) {
                openlistCopyPlusService.updateBatchById(toUpdate);
            }
        } catch (Exception e) {
            // 调用方（syncOneDir）依赖"方法返回即已入库"这一保证（下游 isCopyDone 监控读库判断），
            // 批量写失败时不能只打日志静默吞掉，必须抛出让调用方感知这一目录的数据未落库
            log.error("批量写入copy记录失败: srcPath={}, 数量={}", srcPath, copies.size(), e);
            throw new RuntimeException("批量写入copy记录失败", e);
        }
    }

    /**
     * 检查copy记录是否已存在
     */
    public boolean existsCopy(OpenlistCopyPlus openlistCopyPlus) {
        return openlistCopyPlusService.lambdaQuery()
                .eq(StringUtils.isNotBlank(openlistCopyPlus.getCopySrcPath()), OpenlistCopyPlus::getCopySrcPath, openlistCopyPlus.getCopySrcPath())
                .eq(StringUtils.isNotBlank(openlistCopyPlus.getCopyDstPath()), OpenlistCopyPlus::getCopyDstPath, openlistCopyPlus.getCopyDstPath())
                .eq(StringUtils.isNotBlank(openlistCopyPlus.getCopySrcFileName()), OpenlistCopyPlus::getCopySrcFileName, openlistCopyPlus.getCopySrcFileName())
                .eq(StringUtils.isNotBlank(openlistCopyPlus.getCopyDstFileName()), OpenlistCopyPlus::getCopyDstFileName, openlistCopyPlus.getCopyDstFileName())
                .in(OpenlistCopyPlus::getCopyStatus, "1", "3")
                .exists();
    }

}
