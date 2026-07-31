package com.osr.web.controller.api.system;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.core.page.PageDomain;
import com.osr.common.core.page.TableSupport;
import com.osr.system.domain.SysConfig;
import com.osr.system.service.ISysConfigService;

/**
 * 参数配置REST API控制器
 *
 * @author osr
 */
@RestController
@RequestMapping("/api/system/config")
public class SysConfigApiController extends BaseController
{
    @Autowired
    private ISysConfigService configService;

    /**
     * 查询参数配置分页列表
     */
    @GetMapping("/list")
    public Result<PageResult<SysConfig>> list(SysConfig config)
    {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        Page<SysConfig> page = new Page<>(pageDomain.getPageNum(), pageDomain.getPageSize());
        List<SysConfig> list = configService.selectConfigListPage(page, config);
        return Result.success(PageResult.of(list, page.getTotal(), (int) page.getCurrent(), (int) page.getSize()));
    }

    /**
     * 根据参数配置ID查询参数配置信息
     */
    @GetMapping("/{configId}")
    public Result<SysConfig> getInfo(@PathVariable("configId") Long configId)
    {
        SysConfig config = configService.selectConfigById(configId);
        return Result.success(config);
    }

    /**
     * 新增参数配置
     */
    @PostMapping
    public Result<Integer> add(@Validated @RequestBody SysConfig config)
    {
        if (!configService.checkConfigKeyUnique(config))
        {
            return Result.error("新增参数'" + config.getConfigName() + "'失败，参数键名已存在");
        }
        config.setCreateBy(getLoginName());
        int rows = configService.insertConfig(config);
        return Result.success(rows);
    }

    /**
     * 修改参数配置
     */
    @PutMapping
    public Result<Integer> edit(@Validated @RequestBody SysConfig config)
    {
        if (!configService.checkConfigKeyUnique(config))
        {
            return Result.error("修改参数'" + config.getConfigName() + "'失败，参数键名已存在");
        }
        config.setUpdateBy(getLoginName());
        int rows = configService.updateConfig(config);
        return Result.success(rows);
    }

    /**
     * 删除参数配置（单个）
     */
    @DeleteMapping("/{configId}")
    public Result<Integer> remove(@PathVariable("configId") Long configId)
    {
        configService.deleteConfigByIds(configId.toString());
        return Result.success(1);
    }

    /**
     * 删除参数配置（批量）
     */
    @DeleteMapping
    public Result<Integer> removeBatch(@RequestBody String configIds)
    {
        configService.deleteConfigByIds(configIds);
        return Result.success(1);
    }

    /**
     * 刷新参数缓存
     */
    @PostMapping("/refreshCache")
    public Result<Void> refreshCache()
    {
        configService.resetConfigCache();
        return Result.success();
    }

    /**
     * 校验参数键名是否唯一
     */
    @GetMapping("/checkConfigKeyUnique")
    public Result<Boolean> checkConfigKeyUnique(SysConfig config)
    {
        boolean isUnique = configService.checkConfigKeyUnique(config);
        return Result.success(isUnique);
    }
}
