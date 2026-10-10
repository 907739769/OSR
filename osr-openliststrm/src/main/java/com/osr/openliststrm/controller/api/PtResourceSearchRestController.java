package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.osr.openliststrm.pt.search.ResourcePushRequest;
import com.osr.openliststrm.pt.search.ResourceSearchRequest;
import com.osr.openliststrm.pt.search.ResourceSearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 资源搜索页：不建订阅，直接按关键词搜全部站点。
 * <p>
 * 搜索对登录用户开放（与订阅内手动搜索同一档）；<b>直接下载限管理员</b>：它绕开了订阅、
 * 不建下载记录，种子没有归属、也不受记录页的归属隔离约束，多用户部署里不该让每个人都能往下载器里塞东西。
 * 普通用户想下就走「转为订阅」。
 * </p>
 */
@RestController
@RequestMapping("/api/openliststrm/pt-search")
public class PtResourceSearchRestController extends BaseController {

    private final ResourceSearchService resourceSearchService;
    private final IPtDownloaderPlusService downloaderService;

    public PtResourceSearchRestController(ResourceSearchService resourceSearchService,
                                          IPtDownloaderPlusService downloaderService) {
        this.resourceSearchService = resourceSearchService;
        this.downloaderService = downloaderService;
    }

    @PostMapping("/search")
    public Result<ResourceSearchService.Result> search(@RequestBody ResourceSearchRequest request) {
        try {
            return Result.success(resourceSearchService.search(
                    request.getKeyword(), request.getIndexerIds(), getUserId(), isAdmin()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 直接下载可选的下载器：启用中、且参与下载（只做种的机器不列） */
    @GetMapping("/downloaders")
    public Result<List<DownloaderOption>> downloaders() {
        Result<List<DownloaderOption>> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        List<DownloaderOption> options = downloaderService.list().stream()
                .filter(d -> "1".equals(d.getEnabled()) && d.participatesInDownload())
                .map(d -> new DownloaderOption(d.getId(), d.getName()))
                .toList();
        return Result.success(options);
    }

    @PostMapping("/push")
    public Result<String> push(@RequestBody ResourcePushRequest request) {
        Result<String> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        try {
            String savePath = resourceSearchService.push(request);
            return Result.success("已推送，保存到 " + savePath);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /** 下拉框只要 id 与名字，不把下载器的地址、账号带出去 */
    public record DownloaderOption(Integer id, String name) {
    }
}
