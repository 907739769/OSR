package com.osr.openliststrm.mcp;

import com.osr.common.core.domain.Result;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link Result} 到工具返回值的转换。
 * <p>
 * 有两种转法，用在不同地方，别搞混：
 * </p>
 * <ul>
 *   <li>{@link #unwrapOrThrow} —— 失败即失败。工具处理函数直接返回 {@code Result} 时，
 *       {@code McpToolRegistry} 走的就是这条：{@code code != 200} 转成
 *       {@code isError=true}，模型不会把失败读成成功。</li>
 *   <li>{@link #describe} —— 失败也是一种结果。用在<b>后台作业</b>里：
 *       「搜了一圈但一个资源都没推动」在 OSR 的接口语义里是 {@code Result.error}
 *       （网页端要把原因红字显示出来），可它并不是「作业失败」——把它变成 FAILED 作业，
 *       模型看到的是一个技术错误，多半会去重试，而重试改变不了任何事。</li>
 * </ul>
 *
 * @author Jack
 */
public final class McpResults
{
    private McpResults()
    {
    }

    /** 成功取 data，失败抛 {@link McpToolException}（其 message 会原样回给模型） */
    public static Object unwrapOrThrow(Result<?> result)
    {
        if (result == null)
        {
            return null;
        }
        if (result.getCode() != 200)
        {
            throw new McpToolException(result.getMessage());
        }
        return result.getData();
    }

    /**
     * 把成败连同原因一起如实报出来。
     * <p>
     * {@code message} 无论成败都带上：OSR 的这几个接口把最有价值的信息就放在这里
     * （「已推送 3 个资源到下载器」「未推送任何资源：98 个候选是非免费种」），
     * 只回一个布尔值等于把它扔了。
     * </p>
     */
    public static Map<String, Object> describe(Result<?> result)
    {
        Map<String, Object> view = new LinkedHashMap<>();
        if (result == null)
        {
            view.put("success", false);
            view.put("message", "接口没有返回任何结果");
            return view;
        }
        view.put("success", result.getCode() == 200);
        view.put("message", result.getMessage());
        view.put("data", result.getData());
        return view;
    }
}
