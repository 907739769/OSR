package com.osr.common.core.controller;

import java.beans.PropertyEditorSupport;
import java.util.Date;
import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.osr.common.core.domain.AjaxResult;
import com.osr.common.core.domain.AjaxResult.Type;
import com.osr.common.core.domain.PageResult;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.core.page.PageDomain;
import com.osr.common.core.page.TableSupport;
import com.osr.common.core.page.TableDataInfo;
import com.osr.common.utils.CurrentUserService;
import com.osr.common.utils.DateUtils;
import com.osr.common.utils.ServletUtils;
import com.osr.common.utils.CurrentUserContext;
import com.osr.common.utils.StringUtils;
import com.osr.common.utils.sql.SqlUtil;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * web层通用数据处理
 * 
 * @author osr
 */
public class BaseController
{
    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private CurrentUserService currentUserService;

    /**
     * 将前台传递过来的日期格式的字符串，自动转化为Date类型
     */
    @InitBinder
    public void initBinder(WebDataBinder binder)
    {
        // Date 类型转换
        binder.registerCustomEditor(Date.class, new PropertyEditorSupport()
        {
            @Override
            public void setAsText(String text)
            {
                setValue(DateUtils.parseDate(text));
            }
        });
    }

    /**
     * 设置请求分页数据（已废弃 - 请使用 MyBatis-Plus Page 对象）
     */
    @Deprecated
    protected void startPage()
    {
    }

    /**
     * 设置请求排序数据（已废弃）
     */
    @Deprecated
    protected void startOrderBy()
    {
    }

    /**
     * 清理分页的线程变量（已废弃）
     */
    @Deprecated
    protected void clearPage()
    {
    }

    /**
     * MyBatis-Plus 分页查询辅助方法
     * 使用 MyBatis-Plus 原生 Page 对象进行分页，解决 PageHelper 与 MyBatis-Plus 不兼容问题
     *
     * @param baseMapper BaseMapper 实例
     * @param wrapper    查询条件包装器
     * @return 分页结果
     */
    @SuppressWarnings("unchecked")
    protected <T, M extends BaseMapper<T>> PageResult<T> selectPage(M baseMapper, Wrapper<T> wrapper)
    {
        PageDomain pageDomain = TableSupport.buildPageRequest();
        Integer pageNum = pageDomain.getPageNum();
        Integer pageSize = pageDomain.getPageSize();
        if (pageNum == null || pageNum < 1) pageNum = 1;

        // pageSize = -1 表示不分页返回全部（前端 Vuetify 表格 footer 的「全部」选项）
        if (pageSize != null && pageSize == -1)
        {
            List<T> records = baseMapper.selectList(wrapper);
            return PageResult.of(records, records.size(), pageNum, records.size());
        }
        if (pageSize == null || pageSize < 1) pageSize = 10;

        // 先查总数
        long total = baseMapper.selectCount(wrapper);

        // 再查分页数据
        Page<T> mpPage = new Page<>(pageNum, pageSize);
        OrderItem orderItem = buildOrderItem(pageDomain);
        if (orderItem != null)
        {
            mpPage.addOrder(orderItem);
        }
        baseMapper.selectPage(mpPage, wrapper);

        return PageResult.of(mpPage.getRecords(), total, (int) mpPage.getCurrent(), (int) mpPage.getSize());
    }

    /**
     * 根据分页请求构造排序对象；未指定排序列时返回 null（不排序）
     * 列名与排序方向须分别处理，不能拼接成一个字符串再整体当作列名传给 OrderItem，
     * 否则 MyBatis-Plus 会在拼接的列名后再追加一次方向关键字，生成非法的 ORDER BY 子句
     */
    protected static OrderItem buildOrderItem(PageDomain pageDomain)
    {
        String orderByColumn = SqlUtil.escapeOrderBySql(StringUtils.toUnderScoreCase(pageDomain.getOrderByColumn()));
        if (StringUtils.isEmpty(orderByColumn))
        {
            return null;
        }
        boolean isAsc = !"desc".equalsIgnoreCase(pageDomain.getIsAsc());
        return isAsc ? OrderItem.asc(orderByColumn) : OrderItem.desc(orderByColumn);
    }

    /**
     * 获取request
     */
    public HttpServletRequest getRequest()
    {
        return ServletUtils.getRequest();
    }

    /**
     * 获取response
     */
    public HttpServletResponse getResponse()
    {
        return ServletUtils.getResponse();
    }

    /**
     * 获取session
     */
    public HttpSession getSession()
    {
        return getRequest().getSession();
    }

    /**
     * 响应请求分页数据（已废弃 - 请使用 MyBatis-Plus Page 对象）
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Deprecated
    protected TableDataInfo getDataTable(List<?> list)
    {
        TableDataInfo rspData = new TableDataInfo();
        rspData.setCode(0);
        rspData.setRows(list);
        rspData.setTotal(0);
        return rspData;
    }

    /**
     * 响应返回结果
     * 
     * @param rows 影响行数
     * @return 操作结果
     */
    protected AjaxResult toAjax(int rows)
    {
        return rows > 0 ? success() : error();
    }

    /**
     * 响应返回结果
     * 
     * @param result 结果
     * @return 操作结果
     */
    protected AjaxResult toAjax(boolean result)
    {
        return result ? success() : error();
    }

    /**
     * 返回成功
     */
    public AjaxResult success()
    {
        return AjaxResult.success();
    }

    /**
     * 返回失败消息
     */
    public AjaxResult error()
    {
        return AjaxResult.error();
    }

    /**
     * 返回成功消息
     */
    public AjaxResult success(String message)
    {
        return AjaxResult.success(message);
    }

    /**
     * 返回成功数据
     */
    public static AjaxResult success(Object data)
    {
        return AjaxResult.success("操作成功", data);
    }

    /**
     * 返回失败消息
     */
    public AjaxResult error(String message)
    {
        return AjaxResult.error(message);
    }

    /**
     * 返回错误码消息
     */
    public AjaxResult error(Type type, String message)
    {
        return new AjaxResult(type, message);
    }

    /**
     * 页面跳转
     */
    public String redirect(String url)
    {
        return StringUtils.format("redirect:{}", url);
    }

    /**
     * 获取用户缓存信息
     */
    public SysUser getSysUser()
    {
        return currentUserService.getUser();
    }

    /**
     * 设置用户缓存信息
     */
    public void setSysUser(SysUser user)
    {
        CurrentUserContext.setCurrentUser(user);
    }

    /**
     * 获取登录用户id
     */
    public Long getUserId()
    {
        return currentUserService.getUserId();
    }

    /**
     * 获取登录用户名
     */
    public String getLoginName()
    {
        return currentUserService.getLoginName();
    }
}
