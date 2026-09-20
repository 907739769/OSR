package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.mapper.PtMediaServerPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * <p>
 * PT 媒体服务器配置 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-24
 */
@Service
public class PtMediaServerPlusServiceImpl extends ServiceImpl<PtMediaServerPlusMapper, PtMediaServerPlus> implements IPtMediaServerPlusService {

    /** last_check_error 是 varchar(255)，截断留出余量，避免一条长异常消息把整次写回打回 */
    private static final int MAX_ERROR_LENGTH = 200;

    @Override
    public List<PtMediaServerPlus> listActive() {
        return lambdaQuery()
                .eq(PtMediaServerPlus::getEnabled, "1")
                .orderByAsc(PtMediaServerPlus::getId)
                .list();
    }

    @Override
    public void updateProbeResult(Integer id, boolean ok, String error) {
        if (id == null) {
            return;
        }
        LambdaUpdateWrapper<PtMediaServerPlus> wrapper = new LambdaUpdateWrapper<PtMediaServerPlus>()
                .eq(PtMediaServerPlus::getId, id)
                .set(PtMediaServerPlus::getLastCheckTime, new Date())
                .set(PtMediaServerPlus::getLastCheckOk, ok ? "1" : "0")
                // 成功时显式置 null 清空上次的原因；用实体写回的话 NOT_NULL 策略会把这一步整个跳过
                .set(PtMediaServerPlus::getLastCheckError, ok ? null : truncate(error));
        update(wrapper);
    }

    private static String truncate(String error) {
        if (StringUtils.isBlank(error)) {
            return "未知错误";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH) + "...";
    }
}
