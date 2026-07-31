package com.osr.openliststrm.mybatisplus.service;

import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 重命名分类规则配置 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-20
 */
public interface IRenameCategoryRulePlusService extends IService<RenameCategoryRulePlus> {

    /**
     * 按 media_type 查询启用中的规则，按 seq 升序排列
     */
    List<RenameCategoryRulePlus> listEnabledRules(String mediaType);

    /**
     * 整体替换某个 media_type 下的全部规则：先校验（CategoryRuleValidator），
     * 校验通过后在同一事务内清空旧数据、按提交顺序重新写入（seq=数组下标）。
     * 校验失败抛 IllegalArgumentException，不写库。
     */
    void replaceAll(String mediaType, List<RenameCategoryRulePlus> rules);
}
