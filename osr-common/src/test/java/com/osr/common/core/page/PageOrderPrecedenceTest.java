package com.osr.common.core.page;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住一个整套表头排序都建立在其上的第三方行为：<b>挂在 Page 上的排序排在 SQL 自带的
 * ORDER BY 之前</b>，SQL 自带的那句降为次级排序键。
 * <p>
 * 这条一旦反过来，前端点表头会变成「请求正常、顺序纹丝不动」——因为各 Controller 的
 * buildQueryWrapper 里几乎都带一句默认排序（create_time / id 这种行行不同的列），
 * 排在前面就足以把用户选的排序整个吃掉。而它<b>不报错、不告警</b>，只是不生效，
 * 从日志和响应里都看不出任何异常。升级 MyBatis-Plus 时这条测试是唯一的哨兵。
 * </p>
 * <p>
 * 反过来说，正因为次级键还在，各 Controller 的默认排序<b>不需要</b>改成
 * 「用户排序时就不加」——留着它，同值行（比如按状态排序时的一大片 SUCCESS）
 * 的先后才是稳定的，否则翻页会出现重复行与漏行。
 * </p>
 */
class PageOrderPrecedenceTest
{
    @Test
    void 分页排序排在SQL自带的orderBy之前()
    {
        String sql = "SELECT dict_code, dict_sort FROM sys_dict_data ORDER BY dict_sort ASC";

        String merged = new PaginationInnerInterceptor()
                .concatOrderBy(sql, List.of(OrderItem.desc("create_time")));

        int userOrder = merged.toUpperCase().indexOf("CREATE_TIME");
        int sqlOrder = merged.toUpperCase().lastIndexOf("DICT_SORT");
        assertTrue(userOrder > 0, "用户指定的排序列应该出现在 SQL 里：" + merged);
        assertTrue(userOrder < sqlOrder, "用户指定的排序应排在 SQL 自带的 order by 之前：" + merged);
    }
}
