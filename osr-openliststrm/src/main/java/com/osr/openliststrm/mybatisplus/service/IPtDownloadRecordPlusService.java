package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;

import java.util.List;

/**
 * <p>
 * PT 下载记录 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
public interface IPtDownloadRecordPlusService extends IService<PtDownloadRecordPlus> {

    /**
     * 查指定下载器下正在 H&R 保种考核中的记录（{@code state=COMPLETED} 且 {@code hr_state=PENDING}）。
     * <p>
     * 单独成方法而不是让调用方拼 QueryWrapper：这批记录与"在途下载记录"是两个语义完全不同的
     * 集合，前者已是 COMPLETED 终态、只为 H&R 考核而被继续追踪，混在同一个泛化的 list 调用里
     * 既读不出意图，测试打桩时也无从区分。
     * </p>
     */
    List<PtDownloadRecordPlus> listSeedingPending(Integer downloaderId);
}
