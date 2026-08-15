package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtTransferRecordPlus;

import java.util.List;

/**
 * <p>
 * PT 转移做种记录 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-08-15
 */
public interface IPtTransferRecordPlusService extends IService<PtTransferRecordPlus> {

    /**
     * 查询某规则下所有还在校验中的记录，按 id 升序。定时任务每轮先推进它们。
     */
    List<PtTransferRecordPlus> listVerifying(Integer ruleId);

    /**
     * 该 hash 在这条规则下是否还有未终结（校验中）的记录。
     * <p>
     * 用来防止同一个种子被重复发起转移：目标端已经加进去、正在校验的种子，源端仍然
     * 处于"已完成且满足条件"的状态，不拦的话每一轮都会再推一次。
     * </p>
     */
    boolean hasVerifying(Integer ruleId, String torrentHash);
}
