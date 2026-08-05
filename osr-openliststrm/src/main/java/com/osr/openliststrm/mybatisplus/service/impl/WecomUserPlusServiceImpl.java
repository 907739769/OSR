package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;
import com.osr.openliststrm.mybatisplus.mapper.WecomUserPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * <p>
 * 企业微信成员绑定 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-08-05
 */
@Service
public class WecomUserPlusServiceImpl extends ServiceImpl<WecomUserPlusMapper, WecomUserPlus> implements IWecomUserPlusService {

    @Override
    public WecomUserPlus getByWecomUserId(String wecomUserId) {
        if (StringUtils.isBlank(wecomUserId)) {
            return null;
        }
        return lambdaQuery()
                .eq(WecomUserPlus::getWecomUserid, wecomUserId)
                .one();
    }

    @Override
    public List<WecomUserPlus> listEnabledBySysUserId(Long sysUserId) {
        if (sysUserId == null) {
            return Collections.emptyList();
        }
        return lambdaQuery()
                .eq(WecomUserPlus::getSysUserId, sysUserId)
                // status 允许为 NULL（历史/手工插入的行），ne('1') 会把 NULL 行一起过滤掉，
                // 表现为「明明启用着却收不到通知」，所以显式把 NULL 也算作启用
                .and(w -> w.ne(WecomUserPlus::getStatus, "1").or().isNull(WecomUserPlus::getStatus))
                .orderByAsc(WecomUserPlus::getId)
                .list();
    }
}
