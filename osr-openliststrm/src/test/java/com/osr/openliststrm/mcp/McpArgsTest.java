package com.osr.openliststrm.mcp;

import com.osr.common.core.page.PageContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 入参读取。
 * <p>
 * 这些用例看着琐碎，钉的却是一类真实存在的不稳定：{@code arguments} 里值的类型由客户端的
 * JSON 解析与模型的书写方式共同决定，同一个「集号 5」在不同客户端上可能是 Integer、Double
 * 或字符串。逐个工具去 cast 的写法迟早在某个客户端上抛 ClassCastException，
 * 而报出来的错跟真实原因毫无关系。
 * </p>
 *
 * @author Jack
 */
class McpArgsTest {

    @AfterEach
    void 清理分页覆盖() {
        PageContext.clear();
    }

    @Test
    void 整数可以写成数字字符串或小数() {
        assertEquals(5, new McpArgs(Map.of("episode", 5)).requireInt("episode"));
        assertEquals(5, new McpArgs(Map.of("episode", "5")).requireInt("episode"));
        assertEquals(5, new McpArgs(Map.of("episode", 5.0)).requireInt("episode"),
                "模型偶尔会把整数写成 5.0，直接 Integer.parseInt 会炸");
    }

    @Test
    void 非数字的整数参数给出说得清的错误() {
        McpToolException e = assertThrows(McpToolException.class,
                () -> new McpArgs(Map.of("episode", "第五集")).requireInt("episode"));
        assertTrue(e.getMessage().contains("episode"), "错误里要点名是哪个参数，否则模型改不对");
    }

    @Test
    void 缺失的必填参数直接拒绝() {
        assertThrows(McpToolException.class, () -> new McpArgs(Map.of()).requireString("keyword"));
        assertThrows(McpToolException.class, () -> new McpArgs(Map.of()).requireInt("id"));
    }

    @Test
    void 空白字符串等同于没填() {
        McpArgs args = new McpArgs(Map.of("title", "   "));
        assertFalse(args.has("title"));
        assertEquals(null, args.getString("title"));
    }

    @Test
    void id列表同时接受数组与逗号分隔串() {
        assertEquals(List.of(1, 2, 3), new McpArgs(Map.of("ids", List.of(1, 2, 3))).getIntList("ids"));
        assertEquals(List.of(1, 2, 3), new McpArgs(Map.of("ids", "1,2,3")).getIntList("ids"),
                "schema 里声明的是数组，但模型时不时会写成字符串；"
                        + "因为格式不合就整批失败，换来的只是让它多试一次");
        assertEquals("1,2,3", new McpArgs(Map.of("ids", List.of(1, 2, 3))).getIdsAsCsv("ids"));
    }

    @Test
    void 布尔值接受多种写法() {
        assertTrue(new McpArgs(Map.of("flag", true)).getBool("flag", false));
        assertTrue(new McpArgs(Map.of("flag", "true")).getBool("flag", false));
        assertTrue(new McpArgs(Map.of("flag", "1")).getBool("flag", false));
        assertFalse(new McpArgs(Map.of("flag", "no")).getBool("flag", true));
        assertTrue(new McpArgs(Map.of()).getBool("flag", true), "没填时用默认值");
    }

    @Test
    void 分页有默认值也有上限() {
        new McpArgs(Map.of()).applyPaging();
        PageContext.PageOverride defaults = PageContext.get();
        assertEquals(1, defaults.pageNum());
        assertEquals(20, defaults.pageSize(), "默认条数刻意小于网页端：模型的上下文比屏幕贵得多");

        new McpArgs(Map.of("pageSize", 5000)).applyPaging();
        assertEquals(100, PageContext.get().pageSize(),
                "不封顶的话模型一次要走整张表，返回的东西它自己也读不完");

        new McpArgs(Map.of("page", 0)).applyPaging();
        assertEquals(1, PageContext.get().pageNum(), "页码从 1 开始，0 要归一化");
    }

    @Test
    void 排序方向默认倒序() {
        new McpArgs(Map.of("orderBy", "createTime")).applyPaging();
        assertEquals("createTime", PageContext.get().orderByColumn());
        assertEquals("desc", PageContext.get().isAsc());

        new McpArgs(Map.of("orderBy", "createTime", "orderDesc", false)).applyPaging();
        assertEquals("asc", PageContext.get().isAsc());
    }
}
