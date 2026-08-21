package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.osr.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.osr.openliststrm.pt.indexer.CategoryOption;
import com.osr.openliststrm.pt.indexer.TorznabClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PT Torznab 索引器配置 REST API 控制器
 *
 * @author Jack
 * @date 2026-07-24
 */
@RestController
@RequestMapping("/api/openliststrm/pt-indexers")
public class PtIndexerRestController extends BaseCrudRestController<IPtIndexerPlusService, PtIndexerPlus> {

    @Autowired
    private TorznabClient torznabClient;

    /**
     * 索引器配置存着各站 apikey（passkey 常拼在 url 里），且它决定了整个 PT 检索链路打哪些站，
     * 写操作限管理员。读仍放开——{@link #maskSensitiveFields} 已经把 apikey 抹掉了。
     */
    @Override
    protected boolean adminOnlyWrite() {
        return true;
    }

    @Override
    protected void maskSensitiveFields(PtIndexerPlus entity) {
        entity.setApiKey(null);
    }

    @Override
    protected void mergeUnchangedSensitiveFields(PtIndexerPlus incoming, PtIndexerPlus existing) {
        if (StringUtils.isBlank(incoming.getApiKey())) {
            incoming.setApiKey(existing.getApiKey());
        }
    }

    @Override
    protected Wrapper<PtIndexerPlus> buildQueryWrapper(PtIndexerPlus entity) {
        LambdaQueryWrapper<PtIndexerPlus> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(entity.getName())) {
            wrapper.like(PtIndexerPlus::getName, entity.getName());
        }
        if (StringUtils.isNotBlank(entity.getEnabled())) {
            wrapper.eq(PtIndexerPlus::getEnabled, entity.getEnabled());
        }
        wrapper.orderByAsc(PtIndexerPlus::getId);
        return wrapper;
    }

    /**
     * 连通性测试。接收前端表单当前值，无需先保存即可测试。
     * <p>
     * <b>必须限管理员</b>，比一般写操作更硬的理由：本端点会用
     * {@link #fillSavedApiKeyIfBlank} 把<b>已保存的 apikey</b> 填进来，再向请求体里那个
     * <b>调用方指定的 url</b> 发出去。不设门槛的话，任何登录用户提交
     * {@code {"id":1,"url":"http://自己的服务器"}} 就能把该索引器的 apikey 原样收走——
     * 而这正是 {@code maskSensitiveFields} 费力不让它出现在响应里的那个值。
     * </p>
     */
    @PostMapping("/test")
    public Result<Void> test(@RequestBody PtIndexerPlus entity) {
        Result<Void> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        fillSavedApiKeyIfBlank(entity);
        if (StringUtils.isBlank(entity.getUrl()) || StringUtils.isBlank(entity.getApiKey())) {
            return Result.error("接口地址与 apikey 不能为空");
        }
        try {
            return torznabClient.testConnection(entity)
                    ? Result.success()
                    : Result.error("连接失败，请检查地址、apikey 与网络");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 获取索引器支持的分类树（t=caps），供前端分类下拉使用。接收表单当前值，无需先保存。
     */
    @PostMapping("/categories")
    public Result<List<CategoryOption>> categories(@RequestBody PtIndexerPlus entity) {
        // 与 test 同理：会把已保存的 apikey 发往调用方指定的 url
        Result<List<CategoryOption>> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        fillSavedApiKeyIfBlank(entity);
        if (StringUtils.isBlank(entity.getUrl()) || StringUtils.isBlank(entity.getApiKey())) {
            return Result.error("接口地址与 apikey 不能为空");
        }
        try {
            return Result.success(torznabClient.getCategories(entity));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            return Result.error("获取分类失败：" + e.getMessage());
        }
    }

    /**
     * 编辑已有索引器时前端 apikey 框留空表示"沿用已保存的 apikey"，测试连接/取分类同样要用已保存的值。
     */
    private void fillSavedApiKeyIfBlank(PtIndexerPlus entity) {
        if (StringUtils.isBlank(entity.getApiKey()) && entity.getId() != null) {
            PtIndexerPlus existing = service.getById(entity.getId());
            if (existing != null) {
                entity.setApiKey(existing.getApiKey());
            }
        }
    }
}
