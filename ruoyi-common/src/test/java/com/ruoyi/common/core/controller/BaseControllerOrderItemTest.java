package com.ruoyi.common.core.controller;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.ruoyi.common.core.page.PageDomain;
import com.ruoyi.common.exception.UtilException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseControllerOrderItemTest
{
    private PageDomain pageDomain(String orderByColumn, String isAsc)
    {
        PageDomain pageDomain = new PageDomain();
        pageDomain.setOrderByColumn(orderByColumn);
        if (isAsc != null)
        {
            pageDomain.setIsAsc(isAsc);
        }
        return pageDomain;
    }

    @Test
    void buildOrderItem_未指定排序列_返回null()
    {
        assertNull(BaseController.buildOrderItem(pageDomain(null, "asc")));
        assertNull(BaseController.buildOrderItem(pageDomain("", "asc")));
    }

    @Test
    void buildOrderItem_默认升序_列名不能混入方向文本()
    {
        OrderItem orderItem = BaseController.buildOrderItem(pageDomain("createTime", null));

        assertEquals("create_time", orderItem.getColumn());
        assertTrue(orderItem.isAsc());
    }

    @Test
    void buildOrderItem_显式降序_isAsc参数生效()
    {
        OrderItem orderItem = BaseController.buildOrderItem(pageDomain("createTime", "desc"));

        assertEquals("create_time", orderItem.getColumn());
        assertFalse(orderItem.isAsc());
    }

    @Test
    void buildOrderItem_非法排序列_仍被拒绝()
    {
        assertThrows(UtilException.class, () -> BaseController.buildOrderItem(pageDomain("id) OR 1=1--", "asc")));
    }
}
