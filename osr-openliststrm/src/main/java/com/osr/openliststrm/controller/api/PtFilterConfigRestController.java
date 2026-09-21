package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.mybatisplus.domain.PtFilterConfigPlus;
import com.osr.openliststrm.mybatisplus.service.IPtFilterConfigPlusService;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.pt.filter.FilterConfigAdminService;
import com.osr.openliststrm.pt.filter.FilterVocabulary;
import com.osr.openliststrm.pt.filter.SortDimension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    @Autowired
    private FilterConfigAdminService adminService;

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
     * 分辨率 / 来源 / 质量标签的可选值。这三类字段后端按全等比对，前端从这里选而不是手打。
     */
    @GetMapping("/vocabulary")
    public Result<FilterVocabulary.Options> vocabulary() {
        return Result.success(FilterVocabulary.options());
    }

    /**
     * 规则试算：拿一条种子标题过一遍（可能尚未保存的）过滤规则。只读，不落库，不限管理员。
     */
    @PostMapping("/preview")
    public Result<FilterConfigAdminService.PreviewResult> preview(
            @RequestBody FilterConfigAdminService.PreviewRequest request) {
        if (request == null || StringUtils.isBlank(request.title())) {
            return Result.error("请输入种子标题");
        }
        return Result.success(adminService.preview(request));
    }

    /**
     * 保存全局过滤规则，仅管理员。强制写 id=1，避免前端漏传主键导致插出第二行。
     * 校验不通过时把原因原样返回，见 {@link FilterConfigAdminService#save}。
     */
    @PutMapping
    public Result<Void> save(@RequestBody PtFilterConfigPlus config) {
        Result<Void> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        List<String> errors = adminService.save(config);
        return errors.isEmpty() ? Result.success() : Result.error(String.join("；", errors));
    }
}
