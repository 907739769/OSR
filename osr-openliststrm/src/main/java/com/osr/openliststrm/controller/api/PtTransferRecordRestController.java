package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRecordPlus;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRecordPlusService;
import com.osr.openliststrm.pt.transfer.TransferState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PT 转移做种记录 REST API：列表只读（记录由转移流程自动生成），另提供一个清除失败记录的入口。
 * <p>
 * 转移失败时用户唯一的诊断入口就是这里的 {@code failReason} 与
 * 「源路径 / 目标路径」两列——路径映射配错的表现是"校验后进度只有 0.0%"，
 * 而两个路径摆在一起就能看出问题。
 * </p>
 * <p>
 * {@code DELETE /failed} 是「同一个种子失败太多次后不再自动重试」这条闸门的解除开关
 * （见 {@code TorrentTransferService#retryBlockedBy}）。没有它的话，那道闸门就成了
 * 用户自己解不开的死锁：配置改对了，种子却因为历史失败次数永远不会再被转移。
 * </p>
 *
 * @author Jack
 * @date 2026-08-15
 */
@RestController
@RequestMapping("/api/openliststrm/pt-transfer-records")
public class PtTransferRecordRestController extends BaseController {

    @Autowired
    private IPtTransferRecordPlusService recordService;

    @GetMapping({"", "/list"})
    public Result<PageResult<PtTransferRecordPlus>> list(
            @RequestParam(value = "ruleId", required = false) Integer ruleId,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "torrentName", required = false) String torrentName) {
        LambdaQueryWrapper<PtTransferRecordPlus> wrapper = new LambdaQueryWrapper<>();
        if (ruleId != null) {
            wrapper.eq(PtTransferRecordPlus::getRuleId, ruleId);
        }
        if (StringUtils.isNotBlank(state)) {
            wrapper.eq(PtTransferRecordPlus::getState, state);
        }
        if (StringUtils.isNotBlank(torrentName)) {
            wrapper.like(PtTransferRecordPlus::getTorrentName, torrentName);
        }
        // 最近的排前面：出问题时用户看的永远是最后几条
        wrapper.orderByDesc(PtTransferRecordPlus::getId);
        return Result.success(selectPage(recordService.getBaseMapper(), wrapper));
    }

    /**
     * 清除失败记录，让被"失败次数过多"挡住的种子重新参与转移。
     * <p>
     * 只删 FAILED 的行：VERIFYING 是正在进行的转移（删掉会让目标端留下一个没人管的暂停
     * 种子），COMPLETED 是成功转移的凭证，两者都不该被这个按钮碰到。
     * </p>
     *
     * @param ruleId 只清这条规则的失败记录，不传则清全部
     */
    @DeleteMapping("/failed")
    public Result<Integer> clearFailed(@RequestParam(value = "ruleId", required = false) Integer ruleId) {
        LambdaQueryWrapper<PtTransferRecordPlus> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PtTransferRecordPlus::getState, TransferState.FAILED.value());
        if (ruleId != null) {
            wrapper.eq(PtTransferRecordPlus::getRuleId, ruleId);
        }
        long removed = recordService.count(wrapper);
        recordService.remove(wrapper);
        return Result.success((int) removed);
    }
}
