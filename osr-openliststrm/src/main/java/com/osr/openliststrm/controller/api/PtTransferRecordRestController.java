package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRecordPlus;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRecordPlusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PT 转移做种记录 REST API：只读列表，不提供增删改（记录由转移流程自动生成）。
 * <p>
 * 转移失败时用户唯一的诊断入口就是这里的 {@code failReason} 与
 * 「源路径 / 目标路径」两列——路径映射配错的表现是"校验后进度只有 0.0%"，
 * 而两个路径摆在一起就能看出问题。
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
}
