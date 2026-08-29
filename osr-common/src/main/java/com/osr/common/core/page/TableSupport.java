package com.osr.common.core.page;

import com.osr.common.core.text.Convert;
import com.osr.common.utils.ServletUtils;

/**
 * 表格数据处理
 * 
 * @author osr
 */
public class TableSupport
{
    /**
     * 当前记录起始索引
     */
    public static final String PAGE_NUM = "pageNum";

    /**
     * 每页显示记录数
     */
    public static final String PAGE_SIZE = "pageSize";

    /**
     * 排序列
     */
    public static final String ORDER_BY_COLUMN = "orderByColumn";

    /**
     * 排序的方向 "desc" 或者 "asc".
     */
    public static final String IS_ASC = "isAsc";

    /**
     * 分页参数合理化
     */
    public static final String REASONABLE = "reasonable";

    /**
     * 封装分页对象
     * <p>
     * 优先取 {@link PageContext} 里的线程级覆盖：那是给「进程内直接调用 Controller」的
     * 调用方（MCP 工具层）准备的——它们的 servlet 请求是 {@code POST /mcp}，URL 上没有
     * 任何分页参数，走下面的原路径只会恒定拿到第 1 页 10 条；调用若发生在非 servlet 线程上，
     * {@code ServletUtils.getRequest()} 更是直接 NPE。
     * </p>
     * <p>
     * 没有覆盖时行为与引入 PageContext 之前<b>逐字节相同</b>，现有列表页不受影响。
     * </p>
     */
    public static PageDomain getPageDomain()
    {
        PageContext.PageOverride override = PageContext.get();
        if (override != null)
        {
            PageDomain pageDomain = new PageDomain();
            pageDomain.setPageNum(override.pageNum() != null ? override.pageNum() : 1);
            pageDomain.setPageSize(override.pageSize() != null ? override.pageSize() : 10);
            pageDomain.setOrderByColumn(override.orderByColumn());
            pageDomain.setIsAsc(override.isAsc());
            return pageDomain;
        }
        PageDomain pageDomain = new PageDomain();
        pageDomain.setPageNum(Convert.toInt(ServletUtils.getParameter(PAGE_NUM), 1));
        pageDomain.setPageSize(Convert.toInt(ServletUtils.getParameter(PAGE_SIZE), 10));
        pageDomain.setOrderByColumn(ServletUtils.getParameter(ORDER_BY_COLUMN));
        pageDomain.setIsAsc(ServletUtils.getParameter(IS_ASC));
        pageDomain.setReasonable(ServletUtils.getParameterToBool(REASONABLE));
        return pageDomain;
    }

    public static PageDomain buildPageRequest()
    {
        return getPageDomain();
    }
}
