package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRulePlus;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRulePlusService;
import com.osr.openliststrm.pt.transfer.TorrentTransferService;
import com.osr.openliststrm.pt.transfer.TransferCandidate;
import com.osr.openliststrm.pt.transfer.TransferSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PT 转移做种规则 REST API 控制器。
 * <p>
 * 除标准 CRUD 外，额外提供两个操作：
 * </p>
 * <ul>
 *   <li>{@code POST /preview/{id}}：只判定不执行，把源下载器上每个种子会不会被搬、
 *       不搬的原因、以及<b>映射后的目标路径</b>列出来。路径映射配错是本功能最常见的故障，
 *       而「源路径 → 目标路径」这一对值是用户唯一能一眼看出配错了的地方。</li>
 *   <li>{@code POST /run/{id}}：立即跑一次，不等定时任务。</li>
 * </ul>
 *
 * @author Jack
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/api/openliststrm/pt-transfer-rules")
public class PtTransferRuleRestController
        extends BaseCrudRestController<IPtTransferRulePlusService, PtTransferRulePlus> {

    @Autowired
    private TorrentTransferService transferService;

    /**
     * 转移规则的写操作限管理员：转移的最后一步是<b>删源端种子</b>，而路径映射配错
     * （本功能最常见的故障）会让目标端校验失败、整批种子在两边都不在做种。
     */
    @Override
    protected boolean adminOnlyWrite() {
        return true;
    }

    @Override
    protected Wrapper<PtTransferRulePlus> buildQueryWrapper(PtTransferRulePlus entity) {
        LambdaQueryWrapper<PtTransferRulePlus> wrapper = new LambdaQueryWrapper<>();
        if (entity.getSourceDownloaderId() != null) {
            wrapper.eq(PtTransferRulePlus::getSourceDownloaderId, entity.getSourceDownloaderId());
        }
        if (entity.getTargetDownloaderId() != null) {
            wrapper.eq(PtTransferRulePlus::getTargetDownloaderId, entity.getTargetDownloaderId());
        }
        wrapper.orderByAsc(PtTransferRulePlus::getId);
        return wrapper;
    }

    /**
     * 预览：按当前规则判定，但<b>不搬动任何东西</b>。
     */
    @PostMapping("/preview/{id}")
    public Result<List<Map<String, Object>>> preview(@PathVariable("id") Integer id) {
        PtTransferRulePlus rule = service.getById(id);
        if (rule == null) {
            return Result.error("规则不存在");
        }
        try {
            List<Map<String, Object>> rows = transferService.evaluate(rule).stream()
                    .map(this::toRow)
                    .toList();
            return Result.success(rows);
        } catch (Exception e) {
            return Result.error("预览失败：" + e.getMessage());
        }
    }

    /**
     * 立即执行一次转移。
     * <p>
     * 与自动删种一样受启用开关约束：开关是用户表达"这条规则可以自动搬种子"的唯一位置，
     * 绕开它意味着一次误点就能把一批种子搬走。
     * </p>
     */
    @PostMapping("/run/{id}")
    public Result<TransferSummary> run(@PathVariable("id") Integer id) {
        // 这个端点会真的搬种并删源端种子，比改规则本身更该限管理员
        Result<TransferSummary> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        PtTransferRulePlus rule = service.getById(id);
        if (rule == null) {
            return Result.error("规则不存在");
        }
        if (!rule.enabledOn()) {
            return Result.error("该规则未启用，请先启用后再执行");
        }
        try {
            return Result.success(transferService.runRule(rule));
        } catch (Exception e) {
            return Result.error("执行失败：" + e.getMessage());
        }
    }

    /**
     * 判定结果转成前端行。
     * <p>
     * 用 {@link HashMap} 而不是 {@code Map.of}：后者不接受 null 值，而
     * {@code targetSavePath} 在下载器没给出保存路径时确实可能为 null，
     * 那会在预览接口上抛出一个与业务无关的 NPE。
     * </p>
     */
    private Map<String, Object> toRow(TransferCandidate candidate) {
        Map<String, Object> row = new HashMap<>();
        row.put("name", candidate.displayName());
        row.put("hash", candidate.getTorrent().getHash());
        row.put("sizeBytes", candidate.sizeBytes());
        row.put("seedingSeconds", candidate.getTorrent().getSeedingSeconds());
        row.put("tags", candidate.getTorrent().getTags());
        row.put("sourceSavePath", candidate.getTorrent().getSavePath());
        row.put("targetSavePath", candidate.getTargetSavePath());
        row.put("transferable", candidate.isTransferable());
        row.put("skipReason", candidate.getSkipReason() == null ? "" : candidate.getSkipReason().getDesc());
        return row;
    }
}
