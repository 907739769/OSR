package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.NotifyRoutePlus;
import com.osr.openliststrm.mybatisplus.mapper.NotifyRoutePlusMapper;
import com.osr.openliststrm.mybatisplus.service.INotifyRoutePlusService;
import org.springframework.stereotype.Service;

/**
 * 通知路由 服务实现类
 *
 * @author Jack
 */
@Service
public class NotifyRoutePlusServiceImpl extends ServiceImpl<NotifyRoutePlusMapper, NotifyRoutePlus>
        implements INotifyRoutePlusService {
}
