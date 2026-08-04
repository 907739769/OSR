package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import com.osr.openliststrm.mybatisplus.service.IPtUpgradeConfigPlusService;
import com.osr.openliststrm.pt.upgrade.UpgradeDimension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * PT 洗版规则 REST API 控制器。
 * <p>
 * pt_upgrade_config 是单行配置表，与 {@link PtFilterConfigRestController} 同构，
 * 只提供读与存两个端点。
 * </p>
 *
 * @author Jack
 * @date 2026-08-04
 */
@RestController
@RequestMapping("/api/openliststrm/pt-upgrade-config")
public class PtUpgradeConfigRestController extends BaseController {

    @Autowired
    private IPtUpgradeConfigPlusService upgradeConfigService;

    /** 读取洗版规则。种子数据被误删时服务层返回内置默认值（总开关关闭），不会为 null */
    @GetMapping
    public Result<PtUpgradeConfigPlus> get() {
        return Result.success(upgradeConfigService.getConfig());
    }

    /** 可选的洗版比较维度清单，供前端渲染排序控件 */
    @GetMapping("/quality-dimensions")
    public Result<List<String>> qualityDimensions() {
        return Result.success(Arrays.stream(UpgradeDimension.values()).map(Enum::name).toList());
    }

    /** 保存洗版规则。强制写 id=1，避免前端漏传主键导致插出第二行 */
    @PutMapping
    public Result<Void> save(@RequestBody PtUpgradeConfigPlus config) {
        config.setId(PtUpgradeConfigPlus.SINGLETON_ID);
        boolean ok = upgradeConfigService.saveOrUpdate(config);
        return ok ? Result.success() : Result.error("保存失败");
    }
}
