package com.osr.openliststrm.mcp;

/**
 * 工具调用中「模型自己能处理」的失败：参数不对、对象不存在、权限不够。
 * <p>
 * 与普通异常分开是为了区分给谁看：本异常的 message 会原样回给模型
 * （{@code CallToolResult.isError(true)}），所以它必须是一句<b>模型读了知道下一步该干什么</b>
 * 的话；而其它异常说明的是服务端出了问题，只回一句通用提示、细节留在日志里——
 * 把 NullPointerException 的堆栈丢给模型，它既修不了也只会照着编。
 * </p>
 *
 * @author Jack
 */
public class McpToolException extends RuntimeException
{
    public McpToolException(String message)
    {
        super(message);
    }
}
