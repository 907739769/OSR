package com.osr.openliststrm.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 一个 MCP 工具的完整声明：名字、给模型看的说明、要求的权限档、入参 schema、以及处理函数。
 * <p>
 * 与 SDK 的 {@code McpSchema.Tool} 分开是刻意的：那个是协议结构，这里多带两样它没有的东西——
 * {@link #requiredScope}（本项目的权限档）与「处理函数只需要写业务，不必关心上下文绑定、
 * 分页、异常转换、审计日志」这个约定。转换在 {@code McpToolRegistry} 一处完成，
 * 于是那些横切关注点<b>不可能被某个工具漏掉</b>。
 * </p>
 *
 * @author Jack
 */
public record McpToolSpec(String name,
                          String description,
                          McpScope requiredScope,
                          boolean readOnly,
                          boolean destructive,
                          Map<String, Object> inputSchema,
                          Function<McpArgs, Object> handler)
{
    public static Builder named(String name)
    {
        return new Builder(name);
    }

    /** 工具声明的构造器 */
    public static final class Builder
    {
        private final String name;
        private String description;
        private McpScope requiredScope = McpScope.READ;
        private boolean readOnly = true;
        private boolean destructive = false;
        private final Map<String, Object> properties = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();
        private Function<McpArgs, Object> handler;

        private Builder(String name)
        {
            this.name = name;
        }

        /**
         * 给模型看的说明。
         * <p>
         * 这段话是模型决定「该不该调用这个工具」的<b>唯一</b>依据，写法上有两条：
         * 说清它返回什么/改变什么，以及说清它<b>不</b>做什么（相邻工具容易混淆时）。
         * 耗时超过十几秒的工具还要写明「返回 jobId，需要轮询」，否则模型会以为它失败了并重试。
         * </p>
         */
        public Builder describe(String description)
        {
            this.description = description;
            return this;
        }

        /** 声明为写操作：要求 WRITE 档，且不再标 readOnlyHint */
        public Builder write()
        {
            this.requiredScope = McpScope.WRITE;
            this.readOnly = false;
            return this;
        }

        /**
         * 声明为难以撤销的操作：要求 ADMIN 档，并打上 {@code destructiveHint}。
         * <p>
         * 那个 hint 是给<b>客户端</b>看的——支持的客户端据此弹二次确认。它不构成服务端的
         * 任何约束（服务端的约束是权限档），但少了它，用户在客户端上看到的「删除订阅」
         * 与「查询订阅」长得一模一样。
         * </p>
         */
        public Builder destructive()
        {
            this.requiredScope = McpScope.ADMIN;
            this.readOnly = false;
            this.destructive = true;
            return this;
        }

        public Builder param(String paramName, String jsonType, String description)
        {
            properties.put(paramName, Map.of("type", jsonType, "description", description));
            return this;
        }

        public Builder requiredParam(String paramName, String jsonType, String description)
        {
            param(paramName, jsonType, description);
            required.add(paramName);
            return this;
        }

        /** 枚举型参数。把取值列进 schema，模型就不必靠猜，也省掉一轮「取值不合法」的往返 */
        public Builder enumParam(String paramName, String description, List<String> values)
        {
            properties.put(paramName, Map.of("type", "string", "description", description, "enum", values));
            return this;
        }

        public Builder requiredEnumParam(String paramName, String description, List<String> values)
        {
            enumParam(paramName, description, values);
            required.add(paramName);
            return this;
        }

        public Builder intArrayParam(String paramName, String description)
        {
            properties.put(paramName, Map.of("type", "array", "description", description,
                    "items", Map.of("type", "integer")));
            return this;
        }

        public Builder requiredIntArrayParam(String paramName, String description)
        {
            intArrayParam(paramName, description);
            required.add(paramName);
            return this;
        }

        /** 追加列表类工具通用的分页/排序参数 */
        public Builder paging()
        {
            param(McpArgs.PAGE, "integer", "页码，从 1 开始，默认 1");
            param(McpArgs.PAGE_SIZE, "integer", "每页条数，默认 20，最大 100");
            param(McpArgs.ORDER_BY, "string", "排序字段（数据库列对应的驼峰名），不填则用接口默认排序");
            param(McpArgs.ORDER_DESC, "boolean", "是否倒序，默认 true");
            return this;
        }

        /**
         * 处理函数。返回值会被序列化成 JSON 回给模型；返回 {@code Result} 时由
         * {@code McpToolRegistry} 拆包——失败转成错误结果，成功只回 data。
         */
        public McpToolSpec handle(Function<McpArgs, Object> handler)
        {
            this.handler = handler;
            return build();
        }

        private McpToolSpec build()
        {
            if (description == null || description.isBlank())
            {
                throw new IllegalStateException("工具 " + name + " 缺少 description，模型将无从判断该不该调用它");
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.copyOf(required));
            return new McpToolSpec(name, description, requiredScope, readOnly, destructive, schema, handler);
        }
    }
}
