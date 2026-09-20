package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtMediaServerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtMediaServerPlusService;
import com.osr.openliststrm.pt.media.MediaServerClientFactory;
import com.osr.openliststrm.pt.media.MediaServerProbe;
import com.osr.openliststrm.pt.media.MediaServerUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

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

    /**
     * 媒体服务器配置存着 Emby/Jellyfin 的 API Key，且它是订阅「已入库」判定的唯一数据来源，
     * 写操作限管理员。读仍放开——{@link #maskSensitiveFields} 已经把 apikey 抹掉了。
     */
    @Override
    protected boolean adminOnlyWrite() {
        return true;
    }

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
     * <p>
     * 限管理员的理由与 {@code PtIndexerRestController#test} 相同：下面那段会把
     * <b>已保存的 API Key</b> 填进来，再发往请求体里<b>调用方指定的</b> url。
     * </p>
     */
    @PostMapping("/test")
    public Result<String> test(@RequestBody PtMediaServerPlus entity) {
        Result<String> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        Result<String> invalid = prepareForProbe(entity);
        if (invalid != null) {
            return invalid;
        }
        try {
            MediaServerProbe probe = mediaServerClientFactory.get(entity).testConnection(entity);
            // 成功与失败都把 detail 原样回给用户：那句话是他唯一能看到的解释，
            // 换成通用文案等于把刚算出来的信息丢掉（同「推送失败要把真实原因回到用户眼前」）
            return probe.ok() ? Result.success(probe.detail()) : Result.error(probe.detail());
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 列出该媒体服务器上的用户，供配置页填「用户ID」时选取。
     * <p>
     * 限管理员的理由与 {@link #test} 逐字相同：下面同样会把<b>已保存的</b> API Key 填进来，
     * 再发往请求体里<b>调用方指定的</b> url。
     * </p>
     */
    @PostMapping("/users")
    public Result<List<MediaServerUser>> users(@RequestBody PtMediaServerPlus entity) {
        Result<List<MediaServerUser>> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        Result<List<MediaServerUser>> invalid = prepareForProbe(entity);
        if (invalid != null) {
            return invalid;
        }
        try {
            return Result.success(mediaServerClientFactory.get(entity).listUsers(entity));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (IOException e) {
            return Result.error("获取用户列表失败：" + e.getMessage());
        }
    }

    /**
     * 两个探测类端点共用的准备：回填已保存的 API Key，并校验必填项。
     *
     * @return 校验不通过时的错误响应；通过则返回 null
     */
    private <T> Result<T> prepareForProbe(PtMediaServerPlus entity) {
        // 编辑已有媒体服务器时前端 API Key 框留空表示"沿用已保存的 API Key"，探测同样要用已保存的值
        if (StringUtils.isBlank(entity.getApiKey()) && entity.getId() != null) {
            PtMediaServerPlus existing = service.getById(entity.getId());
            if (existing != null) {
                entity.setApiKey(existing.getApiKey());
            }
        }
        if (StringUtils.isBlank(entity.getUrl()) || StringUtils.isBlank(entity.getApiKey())) {
            return Result.error("服务器地址与 API Key 不能为空");
        }
        return null;
    }
}
