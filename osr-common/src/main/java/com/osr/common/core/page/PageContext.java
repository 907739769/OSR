package com.osr.common.core.page;

/**
 * 分页参数的线程级覆盖。
 * <p>
 * {@link TableSupport#getPageDomain()} 默认从<b>当前 servlet 请求的 query 参数</b>里读
 * pageNum/pageSize/orderByColumn/isAsc。这对网页端是对的——那些参数本来就在 URL 上；
 * 但对「进程内直接调用 Controller」的调用方（MCP 工具层）就不成立了：
 * 那时的 servlet 请求是 {@code POST /mcp}，上面没有任何分页参数，
 * 于是每个列表接口恒定返回第 1 页 10 条、排序参数永远为空。更糟的是若调用发生在
 * 非 servlet 线程上，{@code ServletUtils.getRequest()} 会直接 NPE。
 * </p>
 * <p>
 * 本类给这类调用方一个显式入口：设了就用这一份，没设就<b>完全走原路径</b>——
 * 现有 11 个列表页的行为逐字节不变。
 * </p>
 * <p>
 * <b>刻意不做成「给 selectPage 加一个 PageDomain 重载」</b>：那要求每个 Controller 的
 * {@code list} 方法都多带一个参数往下传，等于把改动摊到三十来个类上，而其中绝大多数
 * 永远不会用到它。用 ThreadLocal 把这件事收在一处，代价是<b>调用方必须在 finally 里
 * {@link #clear()}</b>——线程是复用的，不清等于把上一次调用的分页参数留给下一个请求。
 * </p>
 *
 * @author Jack
 */
public final class PageContext
{
    /**
     * 一次调用的分页意图。字段为 null 表示「这一项不覆盖」，仍退回 {@link TableSupport} 的默认值。
     */
    public record PageOverride(Integer pageNum, Integer pageSize, String orderByColumn, String isAsc)
    {
    }

    private static final ThreadLocal<PageOverride> OVERRIDE = new ThreadLocal<>();

    private PageContext()
    {
    }

    public static void set(PageOverride override)
    {
        OVERRIDE.set(override);
    }

    public static void set(Integer pageNum, Integer pageSize, String orderByColumn, String isAsc)
    {
        OVERRIDE.set(new PageOverride(pageNum, pageSize, orderByColumn, isAsc));
    }

    public static PageOverride get()
    {
        return OVERRIDE.get();
    }

    public static void clear()
    {
        OVERRIDE.remove();
    }
}
