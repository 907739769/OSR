package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtDownloadRecordPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.osr.openliststrm.pt.task.DownloadRecordState;
import com.osr.openliststrm.pt.task.HitAndRunState;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 * PT 下载记录 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Service
public class PtDownloadRecordPlusServiceImpl extends ServiceImpl<PtDownloadRecordPlusMapper, PtDownloadRecordPlus> implements IPtDownloadRecordPlusService {

    @Override
    public List<PtDownloadRecordPlus> listSeedingPending(Integer downloaderId) {
        return list(new QueryWrapper<PtDownloadRecordPlus>()
                .eq("downloader_id", downloaderId)
                .eq("state", DownloadRecordState.COMPLETED.value())
                .eq("hr_state", HitAndRunState.PENDING.value()));
    }
}
