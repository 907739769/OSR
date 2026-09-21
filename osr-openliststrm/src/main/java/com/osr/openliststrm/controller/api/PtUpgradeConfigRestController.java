package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.openliststrm.mybatisplus.domain.PtUpgradeConfigPlus;
import com.osr.openliststrm.mybatisplus.service.IPtUpgradeConfigPlusService;
import com.osr.openliststrm.pt.upgrade.UpgradeConfigAdminService;
import com.osr.openliststrm.pt.upgrade.UpgradeDimension;
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

    @Autowired
    private UpgradeConfigAdminService adminService;

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

    /** 洗版状态概览：各状态集数、上次/下次扫描、沿用的过滤优先级、一致性问题 */
    @GetMapping("/overview")
    public Result<UpgradeConfigAdminService.Overview> overview() {
        return Result.success(adminService.overview());
    }

    /** 对一份尚未保存的洗版规则做一致性诊断（对照已保存的过滤规则），供页面边改边提示 */
    @PostMapping("/check")
    public Result<List<String>> check(@RequestBody PtUpgradeConfigPlus draft) {
        return Result.success(adminService.check(draft));
    }

    /** 立即扫描一轮，后台执行、立即返回，仅管理员 */
    @PostMapping("/scan")
    public Result<Void> scan() {
        Result<Void> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        String refused = adminService.triggerScan();
        return refused == null ? Result.success() : Result.error(refused);
    }

    /**
     * 保存洗版规则，仅管理员。强制写 id=1，避免前端漏传主键导致插出第二行。
     * 校验不通过时把原因原样返回，见 {@link UpgradeConfigAdminService#save}。
     */
    @PutMapping
    public Result<Void> save(@RequestBody PtUpgradeConfigPlus config) {
        Result<Void> denied = denyIfNotAdmin();
        if (denied != null) {
            return denied;
        }
        List<String> errors = adminService.save(config);
        return errors.isEmpty() ? Result.success() : Result.error(String.join("；", errors));
    }
}
