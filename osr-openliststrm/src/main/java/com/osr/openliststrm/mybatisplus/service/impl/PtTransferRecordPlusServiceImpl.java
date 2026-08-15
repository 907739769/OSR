package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRecordPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtTransferRecordPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtTransferRecordPlusService;
import com.osr.openliststrm.pt.transfer.TransferState;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * PT 转移做种记录 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-08-15
 */
@Service
public class PtTransferRecordPlusServiceImpl extends ServiceImpl<PtTransferRecordPlusMapper, PtTransferRecordPlus>
        implements IPtTransferRecordPlusService {

    @Override
    public List<PtTransferRecordPlus> listVerifying(Integer ruleId) {
        if (ruleId == null) {
            return List.of();
        }
        return list(new QueryWrapper<PtTransferRecordPlus>()
                .eq("rule_id", ruleId)
                .eq("state", TransferState.VERIFYING.value())
                .orderByAsc("id"));
    }

    @Override
    public boolean hasVerifying(Integer ruleId, String torrentHash) {
        if (ruleId == null || torrentHash == null) {
            return false;
        }
        return count(new QueryWrapper<PtTransferRecordPlus>()
                .eq("rule_id", ruleId)
                .eq("torrent_hash", torrentHash.toLowerCase())
                .eq("state", TransferState.VERIFYING.value())) > 0;
    }
}
