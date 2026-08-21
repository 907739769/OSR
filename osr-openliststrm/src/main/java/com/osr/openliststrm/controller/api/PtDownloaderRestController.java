package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.pt.downloader.DownloaderClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PT 下载器配置 REST API 控制器
 *
 * @author Jack
 * @date 2026-07-24
 */
@RestController
@RequestMapping("/api/openliststrm/pt-downloaders")
public class PtDownloaderRestController extends BaseCrudRestController<IPtDownloaderPlusService, PtDownloaderPlus> {

    @Autowired
    private DownloaderClientFactory downloaderClientFactory;

    /**
     * 下载器配置存着 qB/TR 的账号密码，且它决定订阅往哪台机器推种、哪台机器开自动删种，
     * 写操作限管理员。读仍放开——{@link #maskSensitiveFields} 已经把密码抹掉了。
     */
    @Override
    protected boolean adminOnlyWrite() {
        return true;
    }

    @Override
    protected void maskSensitiveFields(PtDownloaderPlus entity) {
        entity.setPassword(null);
    }

    @Override
    protected void mergeUnchangedSensitiveFields(PtDownloaderPlus incoming, PtDownloaderPlus existing) {
        if (StringUtils.isBlank(incoming.getPassword())) {
            incoming.setPassword(existing.getPassword());
        }
    }

    @Override
    protected Wrapper<PtDownloaderPlus> buildQueryWrapper(PtDownloaderPlus entity) {
        LambdaQueryWrapper<PtDownloaderPlus> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(entity.getName())) {
            wrapper.like(PtDownloaderPlus::getName, entity.getName());
        }
        if (StringUtils.isNotBlank(entity.getEnabled())) {
            wrapper.eq(PtDownloaderPlus::getEnabled, entity.getEnabled());
        }
        wrapper.orderByAsc(PtDownloaderPlus::getId);
        return wrapper;
    }

    /**
     * 连通性测试。
     * <p>
     * 限管理员的理由与 {@code PtIndexerRestController#test} 完全相同：下面那段会把
     * <b>已保存的密码</b>填进来，再连到请求体里<b>调用方指定的</b> host:port——不设门槛
     * 等于给出一个把下载器密码送到任意地址的接口。
     * </p>
     */
    @PostMapping("/test")
    public Result<Void> test(@RequestBody PtDownloaderPlus entity) {
        Result<Void> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        if (StringUtils.isBlank(entity.getHost()) || entity.getPort() == null) {
            return Result.error("主机与端口不能为空");
        }
        // 编辑已有下载器时前端密码框留空表示"沿用已保存的密码"，测试连接同样要用已保存的密码
        if (StringUtils.isBlank(entity.getPassword()) && entity.getId() != null) {
            PtDownloaderPlus existing = service.getById(entity.getId());
            if (existing != null) {
                entity.setPassword(existing.getPassword());
            }
        }
        try {
            return downloaderClientFactory.get(entity).testConnection(entity)
                    ? Result.success()
                    : Result.error("连接失败，请检查地址、端口与用户名密码");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 校验保存路径是否位于某个文件同步任务的监听目录之下。
     * 不满足不阻断保存，仅返回提示，由前端以警告形式展示。
     */
    @PostMapping("/validate-save-path")
    public Result<String> validateSavePath(@RequestBody PtDownloaderPlus entity) {
        String message = service.validateSavePath(entity.getSavePath());
        return message == null ? Result.success() : Result.success(message);
    }
}
