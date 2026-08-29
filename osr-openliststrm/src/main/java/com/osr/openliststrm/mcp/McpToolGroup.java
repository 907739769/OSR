package com.osr.openliststrm.mcp;

import java.util.List;

/**
 * 一组同类工具的声明。实现类标 {@code @Component}，{@code McpToolRegistry} 会自动收集。
 * <p>
 * 按业务域分组（订阅 / 追剧 / 下载 / 任务 / 运维）而不是按权限档分组：读的人问的是
 * 「订阅相关有哪些工具」，不是「哪些工具需要 write」。
 * </p>
 *
 * @author Jack
 */
public interface McpToolGroup
{
    List<McpToolSpec> tools();
}
