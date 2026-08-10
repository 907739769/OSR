package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.mybatisplus.domain.PtCleanRulePlus;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.service.IPtCleanRulePlusService;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.pt.clean.CleanGroupDecision;
import com.osr.openliststrm.pt.clean.CleanSummary;
import com.osr.openliststrm.pt.clean.TorrentCleanService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * PT 下载器自动删种规则 REST API 控制器。
 * <p>
 * 除标准 CRUD 外，额外提供两个按下载器维度的操作：
 * </p>
 * <ul>
 *   <li>{@code POST /preview/{downloaderId}}：只判定不删除，把每个辅种组的判定结果与原因列出来。
 *       删种不可逆，配完规则先看一眼"这次会删掉什么"是必要的一步。</li>
 *   <li>{@code POST /run/{downloaderId}}：立即按规则执行一次清理，不等定时任务。</li>
 * </ul>
 *
 * @author Jack
 * @date 2026-08-10
 */
@RestController
@RequestMapping("/api/openliststrm/pt-clean-rules")
public class PtCleanRuleRestController extends BaseCrudRestController<IPtCleanRulePlusService, PtCleanRulePlus> {

    @Autowired
    private IPtDownloaderPlusService downloaderService;

    @Autowired
    private TorrentCleanService cleanService;

    @Override
    protected Wrapper<PtCleanRulePlus> buildQueryWrapper(PtCleanRulePlus entity) {
        LambdaQueryWrapper<PtCleanRulePlus> wrapper = new LambdaQueryWrapper<>();
        if (entity.getDownloaderId() != null) {
            wrapper.eq(PtCleanRulePlus::getDownloaderId, entity.getDownloaderId());
        }
        wrapper.orderByAsc(PtCleanRulePlus::getSortOrder).orderByAsc(PtCleanRulePlus::getId);
        return wrapper;
    }

    /**
     * 预览：按当前规则判定，但<b>不删除任何东西</b>。
     */
    @PostMapping("/preview/{downloaderId}")
    public Result<List<Map<String, Object>>> preview(@PathVariable("downloaderId") Integer downloaderId) {
        PtDownloaderPlus downloader = downloaderService.getById(downloaderId);
        if (downloader == null) {
            return Result.error("下载器不存在");
        }
        try {
            List<Map<String, Object>> rows = cleanService.evaluate(downloader).stream()
                    .map(this::toRow)
                    .toList();
            return Result.success(rows);
        } catch (Exception e) {
            return Result.error("预览失败：" + e.getMessage());
        }
    }

    /**
     * 立即执行一次清理。
     */
    @PostMapping("/run/{downloaderId}")
    public Result<CleanSummary> run(@PathVariable("downloaderId") Integer downloaderId) {
        PtDownloaderPlus downloader = downloaderService.getById(downloaderId);
        if (downloader == null) {
            return Result.error("下载器不存在");
        }
        if (!downloader.autoDeleteOn()) {
            // 手动触发同样受总开关约束：开关是用户表达"这台机器可以自动删种"的唯一位置，
            // 绕开它意味着一次误点就能删掉一整批种子
            return Result.error("该下载器未开启自动删种，请先在下载器配置里开启");
        }
        try {
            return Result.success(cleanService.clean(downloader));
        } catch (Exception e) {
            return Result.error("清理失败：" + e.getMessage());
        }
    }

    private Map<String, Object> toRow(CleanGroupDecision decision) {
        return Map.of(
                "name", decision.displayName() == null ? "" : decision.displayName(),
                "torrentCount", decision.getTorrents().size(),
                "sizeBytes", decision.sizeBytes(),
                "deletable", decision.isDeletable(),
                "deleteFiles", decision.isDeleteFiles(),
                "skipReason", decision.getSkipReason() == null ? "" : decision.getSkipReason().getDesc(),
                "blockedBy", decision.getBlockedBy() == null ? "" : decision.getBlockedBy());
    }
}
