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
     * 本控制器的<b>写操作</b>（add/edit/delete）是否仅管理员可用。默认 {@code false}。
     * <p>
     * 覆写返回 true 的应该是「系统级配置」——不属于任何用户、含第三方凭据、或改一下就影响
     * 全局行为的那些（索引器、下载器、媒体服务器、删种与转移规则）。
     * </p>
     * <p>
     * <b>只拦写、不拦读</b>是刻意的：这几个页面的 list/getById 要供前端渲染，而敏感字段已经被
     * {@link #maskSensitiveFields} 抹掉了，读一眼不构成风险；把读也拦掉会让非管理员打开页面
     * 看到一片空白加一个 403，而他多半只是想看看当前配了哪些索引器。
     * </p>
     * <p>
     * <b>做成钩子而不是在子类上标 {@code @PreAuthorize}</b>：这三个端点是从本基类<b>继承</b>的，
     * 注解要落到继承来的方法上依赖 Spring Security 对 targetClass 的解析细节，而这类机制一旦
     * 没生效是<b>完全静默</b>的——接口照常 200，只有权限没了。本项目已经在
     * 「{@code @EnableMethodSecurity} 没开、注解白写」上具备同款风险（见 SecurityConfig），
     * 用一个返回 boolean 的钩子换来「一定会被执行」，值这个不够漂亮。
     * </p>
     */
    protected boolean adminOnlyWrite()
    {
        return false;
    }

    /** 写操作的准入校验：不通过时返回错误 Result，通过返回 null */
    private <R> Result<R> denyIfWriteForbidden()
    {
        return adminOnlyWrite() ? denyIfNotAdmin() : null;
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
        Result<Void> denied = denyIfWriteForbidden();
        if (denied != null)
        {
            return denied;
        }
        boolean result = service.save(entity);
        return result ? Result.success() : Result.error("新增失败");
    }

    /**
     * 修改
     */
    @PutMapping
    public Result<Void> edit(@RequestBody T entity)
    {
        Result<Void> denied = denyIfWriteForbidden();
        if (denied != null)
        {
            return denied;
        }
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
        Result<Void> denied = denyIfWriteForbidden();
        if (denied != null)
        {
            return denied;
        }
        boolean result = service.removeById(id);
        return result ? Result.success() : Result.error("删除失败");
    }
}
