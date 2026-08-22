package com.osr.web.controller.api.system;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
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
import com.osr.common.core.domain.entity.SysDictType;
import com.osr.system.service.ISysDictDataService;
import com.osr.system.service.ISysDictTypeService;

/**
 * 字典类型REST API控制器
 *
 * @author osr
 */
@RestController
@RequestMapping("/api/system/dict/type")
@CrossOrigin(origins = "*")
public class SysDictTypeApiController extends BaseController
{
    @Autowired
    private ISysDictTypeService dictTypeService;

    @Autowired
    private ISysDictDataService dictDataService;

    /**
     * 查询字典类型分页列表
     */
    @GetMapping("/list")
    public Result<PageResult<SysDictType>> list(SysDictType dictType)
    {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        Page<SysDictType> page = new Page<>(pageDomain.getPageNum(), pageDomain.getPageSize());
        // 本类没走 BaseController#selectPage（它查的是 XML 里的分页 SQL），表头排序要自己接上，
        // 不接的话前端点了表头顺序一动不动。该分页 SQL 自己没有 ORDER BY，不指定就沿用库序
        OrderItem order = buildOrderItem(pageDomain);
        if (order != null)
        {
            page.addOrder(order);
        }
        List<SysDictType> list = dictTypeService.selectDictTypeListPage(page, dictType);
        return Result.success(PageResult.of(list, page.getTotal(), (int) page.getCurrent(), (int) page.getSize()));
    }

    /**
     * 根据字典类型ID查询字典类型信息
     */
    @GetMapping("/{dictId}")
    public Result<SysDictType> getInfo(@PathVariable("dictId") Long dictId)
    {
        SysDictType dictType = dictTypeService.selectDictTypeById(dictId);
        return Result.success(dictType);
    }

    /**
     * 新增字典类型
     */
    @PostMapping
    public Result<Integer> add(@Validated @RequestBody SysDictType dict)
    {
        if (!dictTypeService.checkDictTypeUnique(dict))
        {
            return Result.error("新增字典'" + dict.getDictName() + "'失败，字典类型已存在");
        }
        dict.setCreateBy(getLoginName());
        int rows = dictTypeService.insertDictType(dict);
        return Result.success(rows);
    }

    /**
     * 修改字典类型
     */
    @PutMapping
    public Result<Integer> edit(@Validated @RequestBody SysDictType dict)
    {
        if (!dictTypeService.checkDictTypeUnique(dict))
        {
            return Result.error("修改字典'" + dict.getDictName() + "'失败，字典类型已存在");
        }
        dict.setUpdateBy(getLoginName());
        int rows = dictTypeService.updateDictType(dict);
        return Result.success(rows);
    }

    /**
     * 删除字典类型（单个）
     */
    @DeleteMapping("/{dictId}")
    public Result<Integer> remove(@PathVariable("dictId") Long dictId)
    {
        dictTypeService.deleteDictTypeByIds(dictId.toString());
        return Result.success(1);
    }

    /**
     * 删除字典类型（批量）
     */
    @DeleteMapping
    public Result<Integer> removeBatch(@RequestBody String dictIds)
    {
        dictTypeService.deleteDictTypeByIds(dictIds);
        return Result.success(1);
    }

    /**
     * 查询字典类型选项集合
     */
    @GetMapping("/options")
    public Result<List<SysDictType>> options()
    {
        List<SysDictType> dictTypes = dictTypeService.selectDictTypeAll();
        return Result.success(dictTypes);
    }

    /**
     * 根据字典类型查询字典数据
     */
    @GetMapping("/{dictType}")
    public Result<List<SysDictData>> dictData(@PathVariable("dictType") String dictType)
    {
        SysDictData dictData = new SysDictData();
        dictData.setDictType(dictType);
        List<SysDictData> dictDatas = dictDataService.selectDictDataList(dictData);
        return Result.success(dictDatas);
    }

    /**
     * 刷新字典缓存
     */
    @PostMapping("/refreshCache")
    public Result<Void> refreshCache()
    {
        dictTypeService.resetDictCache();
        return Result.success();
    }

    /**
     * 校验字典类型是否唯一
     */
    @PostMapping("/checkDictTypeUnique")
    public Result<Boolean> checkDictTypeUnique(SysDictType dictType)
    {
        boolean isUnique = dictTypeService.checkDictTypeUnique(dictType);
        return Result.success(isUnique);
    }
}
