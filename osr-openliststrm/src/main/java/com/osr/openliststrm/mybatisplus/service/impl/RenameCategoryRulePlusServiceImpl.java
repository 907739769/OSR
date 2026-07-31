package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.RenameCategoryRulePlus;
import com.osr.openliststrm.mybatisplus.mapper.RenameCategoryRulePlusMapper;
import com.osr.openliststrm.mybatisplus.service.IRenameCategoryRulePlusService;
import com.osr.openliststrm.rename.rule.CategoryRuleValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>
 * 重命名分类规则配置 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-20
 */
@Service
public class RenameCategoryRulePlusServiceImpl extends ServiceImpl<RenameCategoryRulePlusMapper, RenameCategoryRulePlus> implements IRenameCategoryRulePlusService {

    @Override
    public List<RenameCategoryRulePlus> listEnabledRules(String mediaType) {
        return lambdaQuery()
                .eq(RenameCategoryRulePlus::getMediaType, mediaType)
                .orderByAsc(RenameCategoryRulePlus::getSeq)
                .list();
    }

    @Override
    @Transactional
    public void replaceAll(String mediaType, List<RenameCategoryRulePlus> rules) {
        CategoryRuleValidator.validate(rules);
        remove(new LambdaQueryWrapper<RenameCategoryRulePlus>().eq(RenameCategoryRulePlus::getMediaType, mediaType));
        for (int i = 0; i < rules.size(); i++) {
            RenameCategoryRulePlus rule = rules.get(i);
            rule.setId(null);
            rule.setMediaType(mediaType);
            rule.setSeq(i);
        }
        saveBatch(rules);
    }
}
