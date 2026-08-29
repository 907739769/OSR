package com.osr.openliststrm.mcp;

import com.osr.common.core.page.PageContext;
import com.osr.common.utils.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具入参的读取器。
 * <p>
 * MCP 的 {@code arguments} 是一个 {@code Map<String, Object>}，值的实际类型由客户端的 JSON
 * 解析决定——同一个「集号 5」可能是 Integer、Long、Double 或字符串 {@code "5"}，取决于
 * 对面用的是哪个 SDK、以及模型把它写成了 {@code 5} 还是 {@code "5"}。逐个工具去 cast
 * 迟早会在某个客户端上抛 ClassCastException，而那时报出来的错跟真实原因（类型不是 Integer）
 * 毫无关系。所以取值一律走本类，<b>按语义转换而不是按类型强转</b>。
 * </p>
 *
 * @author Jack
 */
public final class McpArgs {

    /** 分页参数的公共名字，所有列表类工具共用同一套，模型不用为每个工具重新学一遍 */
    public static final String PAGE = "page";
    public static final String PAGE_SIZE = "pageSize";
    public static final String ORDER_BY = "orderBy";
    public static final String ORDER_DESC = "orderDesc";

    /** 列表工具的默认返回条数。刻意小于网页端：模型的上下文比屏幕贵得多 */
    private static final int DEFAULT_PAGE_SIZE = 20;

    /** 单次最多返回多少条。超过这个量对模型不是「更全」而是「更难读」 */
    private static final int MAX_PAGE_SIZE = 100;

    private final Map<String, Object> raw;

    public McpArgs(Map<String, Object> raw) {
        this.raw = raw == null ? Collections.emptyMap() : raw;
    }

    public boolean has(String name) {
        Object value = raw.get(name);
        return value != null && !(value instanceof String s && s.isBlank());
    }

    public String getString(String name) {
        Object value = raw.get(name);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public String requireString(String name) {
        String value = getString(name);
        if (value == null) {
            throw new McpToolException("缺少必填参数 " + name);
        }
        return value;
    }

    public Integer getInt(String name) {
        Object value = raw.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            // 先按 double 解析再取整：模型偶尔会把整数写成 5.0，直接 Integer.parseInt 会炸
            return (int) Double.parseDouble(text);
        } catch (NumberFormatException e) {
            throw new McpToolException("参数 " + name + " 需要是数字，收到的是：" + text);
        }
    }

    public int requireInt(String name) {
        Integer value = getInt(name);
        if (value == null) {
            throw new McpToolException("缺少必填参数 " + name);
        }
        return value;
    }

    public boolean getBool(String name, boolean defaultValue) {
        Object value = raw.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "yes".equalsIgnoreCase(text);
    }

    /**
     * 取一组 id。
     * <p>
     * 同时接受 JSON 数组与逗号分隔的字符串：schema 里声明的是数组，但模型时不时会写成
     * {@code "1,2,3"}，而这类工具多半是批量操作——因为格式不合就整批失败，
     * 换来的只是让模型多试一次。
     * </p>
     */
    public List<Integer> getIntList(String name) {
        Object value = raw.get(name);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<Integer> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof Number number) {
                    result.add(number.intValue());
                } else if (item != null) {
                    result.add(parseIdOrThrow(name, String.valueOf(item)));
                }
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(item -> parseIdOrThrow(name, item))
                .collect(Collectors.toList());
    }

    /** 把 id 列表还原成现有批量接口要的逗号分隔串 */
    public String getIdsAsCsv(String name) {
        return getIntList(name).stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private int parseIdOrThrow(String name, String item) {
        try {
            return Integer.parseInt(item);
        } catch (NumberFormatException e) {
            throw new McpToolException("参数 " + name + " 里有非数字的 id：" + item);
        }
    }

    /**
     * 把本次调用的分页意图写进 {@link PageContext}，供随后直接调用的 Controller 读取。
     * <p>
     * 由 {@code McpToolRegistry} 的统一包装在<b>每次</b>调用前无条件执行，
     * 因此不含分页参数的工具也会拿到一份默认值——这正是要的：分页参数留在上一次调用的
     * 值上，比拿到默认值糟得多，而清理由那层的 finally 负责。
     * </p>
     */
    public void applyPaging() {
        Integer page = getInt(PAGE);
        Integer pageSize = getInt(PAGE_SIZE);
        if (pageSize == null || pageSize < 1) {
            pageSize = DEFAULT_PAGE_SIZE;
        }
        if (pageSize > MAX_PAGE_SIZE) {
            pageSize = MAX_PAGE_SIZE;
        }
        PageContext.set(page == null || page < 1 ? 1 : page, pageSize,
                getString(ORDER_BY), getBool(ORDER_DESC, true) ? "desc" : "asc");
    }
}
