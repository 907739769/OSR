package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.PtCleanRulePlus;

import java.util.List;

/**
 * <p>
 * PT 下载器自动删种规则 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-08-10
 */
public interface IPtCleanRulePlusService extends IService<PtCleanRulePlus> {

    /**
     * 查询某下载器<b>启用中</b>的规则，按 sort_order 升序、id 升序。
     * <p>
     * 顺序即优先级：清理时取第一条体积区间命中的规则。用专门的方法而不是内联
     * QueryWrapper，是因为"启用 + 有序"这两个约束一旦在某个调用点漏掉，
     * 表现是"规则时灵时不灵"，很难从日志看出来。
     * </p>
     */
    List<PtCleanRulePlus> listEnabledByDownloader(Integer downloaderId);
}
