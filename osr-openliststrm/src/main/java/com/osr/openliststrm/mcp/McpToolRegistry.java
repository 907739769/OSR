package com.osr.openliststrm.mcp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.osr.common.core.domain.Result;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把各 {@link McpToolGroup} 声明的工具转成 MCP SDK 的工具规格，并在<b>一处</b>接上所有
 * 横切关注点：权限档校验、身份绑定、分页上下文、返回值拆包、异常转换、审计日志。
 * <p>
 * <b>为什么横切逻辑必须收在这里而不是让每个工具自己写</b>：这五件事里有三件（绑定、清理、
 * 权限校验）漏掉之后<b>不会报错</b>——漏绑定表现为"查不到任何订阅"，漏清理表现为
 * 偶发的身份串台，漏权限校验表现为只读令牌能删东西。让它们在结构上不可能被漏掉，
 * 比写一条"记得加上"的规矩可靠。
 * </p>
 *
 * <h2>关于这里<b>没有</b>哪些工具</h2>
 * <p>
 * 以下操作在整个 MCP 层没有对应工具，任何权限档都拿不到，这是刻意的：
 * </p>
 * <ul>
 *   <li><b>删除网盘上的实际文件</b>（{@code batchRemoveNetDisk}）——不可逆，且删掉的是
 *       用户网盘里的原始资源，不是 OSR 生成的派生物。</li>
 *   <li><b>删种</b>（删种规则与转移规则的 {@code run}）——会真的删除保种文件，
 *       在站点上记 H&amp;R，代价落在 OSR 之外。</li>
 *   <li><b>索引器 / 下载器 / 媒体服务器 / 参数设置的写与连通性测试</b>——它们持有第三方凭据；
 *       尤其 {@code /test} 会把<b>已保存的</b> apikey 填进请求、发往调用方指定的 url，
 *       等于一个「把密钥送到任意地址」的接口。</li>
 *   <li><b>用户、角色、菜单</b>——与影视管理无关，暴露它只增加受攻击面。</li>
 * </ul>
 * <p>
 * 判据是「做错了要花多大代价收回来」：上面每一条的误操作都不可逆或代价落在 OSR 之外，
 * 而模型的误判在 MCP 上是<b>完全静默</b>的——客户端只会显示一句工具名。
 * 需要做这些事的人去开网页，那里有确认框、有上下文、有撤销路径。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@Component
public class McpToolRegistry {

    /**
     * 审计日志走 {@code access} 这个独立 logger（同 {@code RequestLogFilter}），
     * 落在 sys-access.log 而不是与业务日志混在一起：「这个助理都干了什么」是一条独立的
     * 追溯线索，混进全量日志里等于没有。
     */
    private static final Logger auditLog = LoggerFactory.getLogger("access");

    /** 审计日志里入参摘要的截断长度。种子标题、关键词可以很长，而摘要只是给人认的 */
    private static final int ARGS_SUMMARY_LIMIT = 300;

    private final List<McpToolGroup> groups;

    public McpToolRegistry(List<McpToolGroup> groups) {
        this.groups = groups;
    }

    /** 全部工具声明，按组的注册顺序拼接 */
    public List<McpToolSpec> specs() {
        List<McpToolSpec> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (McpToolGroup group : groups) {
            for (McpToolSpec spec : group.tools()) {
                if (!seen.add(spec.name())) {
                    // 重名工具在 MCP 协议里没有定义行为，SDK 多半后者覆盖前者——
                    // 那意味着某个工具静默消失，启动就拦下比事后查强得多
                    throw new IllegalStateException("MCP 工具名重复：" + spec.name());
                }
                all.add(spec);
            }
        }
        return all;
    }

    /** 转成 SDK 的工具规格，供 {@code McpServerConfig} 注册 */
    public List<McpServerFeatures.SyncToolSpecification> specifications() {
        return specs().stream().map(this::toSpecification).toList();
    }

    private McpServerFeatures.SyncToolSpecification toSpecification(McpToolSpec spec) {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .inputSchema(spec.inputSchema())
                .annotations(McpSchema.ToolAnnotations.builder()
                        .readOnlyHint(spec.readOnly())
                        .destructiveHint(spec.destructive())
                        // 工具全部作用于 OSR 自己的数据，但 OSR 会去打 TMDb/索引器/下载器，
                        // 结果不完全由本次入参决定，所以 openWorld 是 true
                        .openWorldHint(true)
                        .build())
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> invoke(spec, exchange, request))
                .build();
    }

    private McpSchema.CallToolResult invoke(McpToolSpec spec,
                                            io.modelcontextprotocol.server.McpSyncServerExchange exchange,
                                            McpSchema.CallToolRequest request) {
        return invoke(spec, principalOf(exchange), request);
    }

    /**
     * 取到身份之后的那一半。
     * <p>
     * 与上面按 exchange 取身份的那一半分开，是为了让权限档校验、返回值拆包、异常转换这三条
     * 能被单测直接钉住——{@code McpSyncServerExchange} 只能由 SDK 内部构造，
     * 为了造它而引入的那层 mock 会把测试变成「测 mock」。
     * </p>
     */
    McpSchema.CallToolResult invoke(McpToolSpec spec, McpPrincipal principal,
                                    McpSchema.CallToolRequest request) {
        if (principal == null) {
            // 正常情况下走不到这里——McpAuthFilter 认证失败时 Security 已经把请求拦成 401。
            // 留着是因为「传输层换了实现、contextExtractor 忘了接上」这类改动<b>不会报错</b>，
            // 而它的后果是全部工具以匿名身份执行
            return error("未能识别调用方身份，请检查 MCP 令牌是否已配置在 Authorization 头里");
        }
        if (!principal.scope().covers(spec.requiredScope())) {
            return error("当前令牌的权限档是 " + principal.scope().code() + "，而工具 " + spec.name()
                    + " 需要 " + spec.requiredScope().code() + " 档。请在 OSR 的「MCP 令牌」页另发一枚令牌。");
        }

        long started = System.currentTimeMillis();
        try (McpCallContext.Binding ignored = McpCallContext.bind(principal)) {
            McpArgs args = new McpArgs(request.arguments());
            args.applyPaging();
            Object payload = unwrap(spec.handler().apply(args));
            audit(spec, principal, request, started, "OK");
            return success(payload);
        } catch (McpToolException e) {
            // 业务上的"不行"，原样回给模型——它读得懂，也知道下一步该改什么
            audit(spec, principal, request, started, "REJECTED: " + e.getMessage());
            return error(e.getMessage());
        } catch (Exception e) {
            // 一条日志、带上下文、带异常对象；同时占位符里保留 getMessage()，
            // 否则只能靠堆栈找、没法按错误文本 grep
            log.error("MCP 工具 {} 执行失败（{}）：{}", spec.name(), principal.describe(), e.getMessage(), e);
            audit(spec, principal, request, started, "ERROR: " + e.getClass().getSimpleName());
            return error("工具执行失败：" + e.getMessage());
        }
    }

    /**
     * 拆掉 {@link Result} 这层包装。
     * <p>
     * 直接把 {@code {"code":200,"message":"操作成功","data":[...]}} 丢给模型有两个坏处：
     * 每次调用都白烧一遍固定字段的 token；更要紧的是 <b>{@code code=500} 长得像成功</b>——
     * 结构上它和成功响应一模一样，模型多半会当作拿到了结果继续往下做。
     * 拆包之后失败就是失败（{@code isError=true}），不给它误读的机会。
     * </p>
     */
    private Object unwrap(Object value) {
        if (value instanceof Result<?> result) {
            if (result.getCode() != 200) {
                throw new McpToolException(result.getMessage());
            }
            return result.getData();
        }
        return value;
    }

    private McpPrincipal principalOf(io.modelcontextprotocol.server.McpSyncServerExchange exchange) {
        if (exchange == null || exchange.transportContext() == null) {
            return null;
        }
        Object value = exchange.transportContext().get(McpCallContext.PRINCIPAL_KEY);
        return value instanceof McpPrincipal principal ? principal : null;
    }

    private McpSchema.CallToolResult success(Object payload) {
        String text = payload == null
                ? "操作成功"
                // WriteMapNullValue：字段为 null 时也输出。省掉的话模型看到的是"这个字段不存在"，
                // 而它要回答的问题往往正是"这一集有没有播出日期"——缺字段与字段为空，
                // 在它眼里是两件事
                : JSON.toJSONString(payload, JSONWriter.Feature.WriteMapNullValue);
        return McpSchema.CallToolResult.builder().addTextContent(text).build();
    }

    private McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private void audit(McpToolSpec spec, McpPrincipal principal, McpSchema.CallToolRequest request,
                       long started, String outcome) {
        auditLog.info("MCP {} <- {} args={} -> {} ({}ms)",
                spec.name(), principal.describe(), summarizeArgs(request), outcome,
                System.currentTimeMillis() - started);
    }

    /**
     * 入参摘要。
     * <p>
     * 整个 MCP 层不存在会收到密钥的工具（那类端点根本没有对应工具，见类注释），
     * 所以这里只做长度截断，不做字段级脱敏——加一份"敏感字段名清单"反而会给人
     * 「加了新工具往清单里补一下就安全了」的错觉，而正确的做法是那类工具压根不该存在。
     * </p>
     */
    private String summarizeArgs(McpSchema.CallToolRequest request) {
        if (request.arguments() == null || request.arguments().isEmpty()) {
            return "{}";
        }
        String text = JSON.toJSONString(request.arguments());
        return text.length() <= ARGS_SUMMARY_LIMIT
                ? text
                : text.substring(0, ARGS_SUMMARY_LIMIT) + "…(共" + text.length() + "字符)";
    }
}
