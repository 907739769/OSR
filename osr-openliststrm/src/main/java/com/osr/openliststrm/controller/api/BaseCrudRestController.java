package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 通用 CRUD Controller 基类
 * <p>
 * 提供 list / getById / add / edit / delete 五个标准 CRUD 端点。
 * 子类只需实现 {@link #buildQueryWrapper(Object)} 并提供自己的 @RequestMapping。
 * 若子类需要自定义验证逻辑，可覆写对应方法。
 * </p>
 *
 * @param <S> IService 子类
 * @param <T> 实体类型
 * @author Jack
 */
public abstract class BaseCrudRestController<S extends IService<T>, T> extends BaseController
{
    @Autowired
    protected S service;

    /**
     * 子类实现查询条件构建（返回 QueryWrapper 或 LambdaQueryWrapper 均可）
     */
    protected abstract Wrapper<T> buildQueryWrapper(T entity);

    /**
     * 返回给前端前对敏感字段脱敏（如密码、apikey）。默认不做任何处理，
     * 含密码/apikey 等字段的子类需覆写，避免明文随接口响应下发。
     */
    protected void maskSensitiveFields(T entity)
    {
    }

    /**
     * 编辑时，若前端提交的敏感字段为空（表示用户未修改），用数据库中已有值回填，
     * 避免因为 getById/list 已脱敏、前端表单留空提交后把已保存的密码/apikey 覆盖成空值。
     * 默认不做任何处理，含敏感字段的子类需覆写。
     */
    protected void mergeUnchangedSensitiveFields(T incoming, T existing)
    {
    }

    /**
     * 分页查询列表 - 支持 /xxx 和 /xxx/list
     */
    @GetMapping({"", "/list"})
    public Result<PageResult<T>> list(T entity)
    {
        PageResult<T> page = selectPage(service.getBaseMapper(), buildQueryWrapper(entity));
        if (page.getRecords() != null)
        {
            page.getRecords().forEach(this::maskSensitiveFields);
        }
        return Result.success(page);
    }

    /**
     * 根据 ID 获取详情
     */
    @GetMapping("/{id}")
    public Result<T> getById(@PathVariable("id") Integer id)
    {
        T record = service.getById(id);
        if (record == null)
        {
            return Result.error("记录不存在");
        }
        maskSensitiveFields(record);
        return Result.success(record);
    }

    /**
     * 新增
     */
    @PostMapping
    public Result<Void> add(@RequestBody T entity)
    {
        boolean result = service.save(entity);
        return result ? Result.success() : Result.error("新增失败");
    }

    /**
     * 修改
     */
    @PutMapping
    public Result<Void> edit(@RequestBody T entity)
    {
        T existing = service.getById((java.io.Serializable) getEntityId(entity));
        if (existing != null)
        {
            mergeUnchangedSensitiveFields(entity, existing);
        }
        boolean result = service.updateById(entity);
        return result ? Result.success() : Result.error("修改失败");
    }

    /**
     * 从实体上取主键值，供 edit 回填未修改的敏感字段前查旧记录用。
     * 子类实体的主键字段固定名为 getId()（现有 *Plus 实体均如此），因此这里直接反射调用即可。
     */
    private Object getEntityId(T entity)
    {
        try
        {
            return entity.getClass().getMethod("getId").invoke(entity);
        }
        catch (ReflectiveOperationException e)
        {
            return null;
        }
    }

    /**
     * 删除
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Integer id)
    {
        boolean result = service.removeById(id);
        return result ? Result.success() : Result.error("删除失败");
    }
}
