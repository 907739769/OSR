package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.pt.media.MediaServerClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PT 媒体服务器配置 REST API 控制器
 *
 * @author Jack
 * @date 2026-07-24
 */
@RestController
@RequestMapping("/api/openliststrm/pt-media-servers")
public class PtMediaServerRestController extends BaseCrudRestController<IPtMediaServerPlusService, PtMediaServerPlus> {

    @Autowired
    private MediaServerClientFactory mediaServerClientFactory;

    @Override
    protected void maskSensitiveFields(PtMediaServerPlus entity) {
        entity.setApiKey(null);
    }

    @Override
    protected void mergeUnchangedSensitiveFields(PtMediaServerPlus incoming, PtMediaServerPlus existing) {
        if (StringUtils.isBlank(incoming.getApiKey())) {
            incoming.setApiKey(existing.getApiKey());
        }
    }

    @Override
    protected Wrapper<PtMediaServerPlus> buildQueryWrapper(PtMediaServerPlus entity) {
        LambdaQueryWrapper<PtMediaServerPlus> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(entity.getName())) {
            wrapper.like(PtMediaServerPlus::getName, entity.getName());
        }
        if (StringUtils.isNotBlank(entity.getEnabled())) {
            wrapper.eq(PtMediaServerPlus::getEnabled, entity.getEnabled());
        }
        wrapper.orderByAsc(PtMediaServerPlus::getId);
        return wrapper;
    }

    /**
     * 连通性测试。
     */
    @PostMapping("/test")
    public Result<Void> test(@RequestBody PtMediaServerPlus entity) {
        // 编辑已有媒体服务器时前端 API Key 框留空表示"沿用已保存的 API Key"，测试连接同样要用已保存的值
        if (StringUtils.isBlank(entity.getApiKey()) && entity.getId() != null) {
            PtMediaServerPlus existing = service.getById(entity.getId());
            if (existing != null) {
                entity.setApiKey(existing.getApiKey());
            }
        }
        if (StringUtils.isBlank(entity.getUrl()) || StringUtils.isBlank(entity.getApiKey())) {
            return Result.error("服务器地址与 API Key 不能为空");
        }
        try {
            return mediaServerClientFactory.get(entity).testConnection(entity)
                    ? Result.success()
                    : Result.error("连接失败，请检查地址、API Key 与网络");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
