package com.osr.web.controller.api.system;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import com.osr.common.core.page.PageDomain;
import com.osr.common.core.page.TableSupport;
import com.osr.common.core.domain.entity.SysDictData;
import com.osr.system.service.ISysDictDataService;

/**
 * 字典数据REST API控制器
 *
 * @author osr
 */
@RestController
@RequestMapping("/api/system/dict/data")
public class SysDictDataApiController extends BaseController
{
    @Autowired
    private ISysDictDataService dictDataService;

    /**
     * 查询字典数据分页列表
     */
    @GetMapping("/list")
    public Result<PageResult<SysDictData>> list(SysDictData dictData)
    {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        Page<SysDictData> page = new Page<>(pageDomain.getPageNum(), pageDomain.getPageSize());
        // 本类没走 BaseController#selectPage（它查的是 XML 里的分页 SQL），表头排序要自己接上，
        // 不接的话前端点了表头顺序一动不动。分页拦截器会把这里的排序放在 SQL 自带的
        // order by dict_sort 之前，dict_sort 自然降为次级键，同值行的顺序仍然稳定
        OrderItem order = buildOrderItem(pageDomain);
        if (order != null)
        {
            page.addOrder(order);
        }
        List<SysDictData> list = dictDataService.selectDictDataListPage(page, dictData);
        return Result.success(PageResult.of(list, page.getTotal(), (int) page.getCurrent(), (int) page.getSize()));
    }

    /**
     * 根据字典数据ID查询字典数据信息
     */
    @GetMapping("/{dictCode}")
    public Result<SysDictData> getInfo(@PathVariable("dictCode") Long dictCode)
    {
        SysDictData dictData = dictDataService.selectDictDataById(dictCode);
        return Result.success(dictData);
    }

    /**
     * 新增字典数据
     */
    @PostMapping
    public Result<Integer> add(@RequestBody SysDictData dict)
    {
        dict.setCreateBy(getLoginName());
        int rows = dictDataService.insertDictData(dict);
        return Result.success(rows);
    }

    /**
     * 修改字典数据
     */
    @PutMapping
    public Result<Integer> edit(@RequestBody SysDictData dict)
    {
        dict.setUpdateBy(getLoginName());
        int rows = dictDataService.updateDictData(dict);
        return Result.success(rows);
    }

    /**
     * 删除字典数据（单个）
     */
    @DeleteMapping("/{dictCode}")
    public Result<Integer> remove(@PathVariable("dictCode") Long dictCode)
    {
        dictDataService.deleteDictDataByIds(dictCode.toString());
        return Result.success(1);
    }

    /**
     * 删除字典数据（批量）
     */
    @DeleteMapping
    public Result<Integer> removeBatch(@RequestBody String ids)
    {
        dictDataService.deleteDictDataByIds(ids);
        return Result.success(1);
    }
}
