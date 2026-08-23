package com.osr.openliststrm.helper;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.api.OpenlistApi;
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

    @Autowired
    private OpenlistApi openlistApi;

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
                    log.debug("复制记录已存在：path={}, fileName={}",
                            openlistCopyPlus.getCopySrcPath(), openlistCopyPlus.getCopySrcFileName());
                } else {
                    log.error("写入复制记录失败：path={}, fileName={}",
                            openlistCopyPlus.getCopySrcPath(), openlistCopyPlus.getCopySrcFileName(), e);
                }
            } catch (Exception e) {
                log.error("写入复制记录失败：path={}, fileName={}",
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
     * 复制失败时先问一句「源文件是不是已经不在了」，是的话直接把这条记录删掉并返回 {@code true}。
     *
     * <p>存在一类失败不是"复制出了问题"而是"要复制的东西中途没了"：下载器删种（自动删种、
     * 用户手删、IYUU 转移）会在 OSR 提交 fs/copy 之后、AList 真正读到文件之前把源删光。
     * 把它记成 {@code copy_status='2'} 有两个坏处：①用户看到一条无从解释的失败告警；
     * ②{@code retryAllFailed} 会一遍遍捡起它重试，而源已经不存在，<b>这条记录永远不可能变成成功</b>，
     * 只会一直占着失败列表。删掉才是它真实的语义——这份文件不再需要被同步了。
     *
     * <p><b>「AList 说找不到」与「AList 不可达」必须分开</b>（口径同 {@code CopyRecoveryTask#probeDst}）：
     * AList 的业务错误走 HTTP 200 + {@code code!=200} 的响应体，而网络不通时
     * {@code getFile} 返回 null。响应为 null 时一律返回 false（照常记失败），
     * 否则一次网络抖动就会把一批本该保留、可重试的失败记录静默删光。
     *
     * @return true = 源确实不在了、记录已删除；false = 源还在或判不出来，调用方按原逻辑记失败
     */
    public boolean discardIfSourceGone(OpenlistCopyPlus copy) {
        String dir = StringUtils.removeEnd(copy.getCopySrcPath(), "/");
        if (StringUtils.isBlank(dir) || StringUtils.isBlank(copy.getCopySrcFileName())) {
            return false;
        }
        String srcFile = dir + "/" + copy.getCopySrcFileName();
        try {
            JSONObject resp = openlistApi.getFile(srcFile);
            if (resp == null) {
                // AList 不可达：这一轮不下结论，按普通失败处理，留给用户/重试入口
                return false;
            }
            if (Integer.valueOf(200).equals(resp.getInteger("code")) && resp.getJSONObject("data") != null) {
                return false;
            }
            log.info("复制失败但源文件已不存在（多半是下载器在复制期间删了种），丢弃该记录: {} (AList: {})",
                    srcFile, resp.getString("message"));
            if (copy.getCopyId() != null) {
                openlistCopyPlusService.removeById(copy.getCopyId());
            }
            return true;
        } catch (Exception e) {
            log.warn("探测源文件是否存在失败，按普通复制失败处理: {}", srcFile, e);
            return false;
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
