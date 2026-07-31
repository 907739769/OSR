package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.openliststrm.pt.filter.SortDimension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * PT 全局过滤与排序规则 REST API 控制器。
 * <p>
 * pt_filter_config 是单行配置表，不走 CRUD 基类，只提供读与存两个端点。
 * </p>
 *
 * @author Jack
 * @date 2026-07-27
 */
@RestController
@RequestMapping("/api/openliststrm/pt-filter-config")
public class PtFilterConfigRestController extends BaseController {

    @Autowired
    private IPtFilterConfigPlusService filterConfigService;

    /**
     * 读取全局过滤规则。种子数据被误删时服务层会返回内置默认值，不会为 null。
     */
    @GetMapping
    public Result<PtFilterConfigPlus> get() {
        return Result.success(filterConfigService.getConfig());
    }

    /**
     * 可选的排序维度清单，供前端渲染拖拽/多选控件。
     */
    @GetMapping("/sort-dimensions")
    public Result<List<String>> sortDimensions() {
        return Result.success(Arrays.stream(SortDimension.values()).map(Enum::name).toList());
    }

    /**
     * 保存全局过滤规则。强制写 id=1，避免前端漏传主键导致插出第二行。
     */
    @PutMapping
    public Result<Void> save(@RequestBody PtFilterConfigPlus config) {
        config.setId(PtFilterConfigPlus.SINGLETON_ID);
        boolean ok = filterConfigService.saveOrUpdate(config);
        return ok ? Result.success() : Result.error("保存失败");
    }
}
