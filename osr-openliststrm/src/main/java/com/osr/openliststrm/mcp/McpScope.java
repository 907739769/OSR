package com.osr.openliststrm.mcp;

/**
 * MCP 令牌的权限档。
 * <p>
 * 三档是<b>递进</b>关系（{@link #covers}），不是三个独立开关：一把能删订阅的钥匙必然也能读订阅，
 * 拆成独立勾选只会让「我到底给了它什么」变得说不清。
 * </p>
 * <p>
 * <b>档位划分的依据是「做错了要花多大代价收回来」</b>，不是「读还是写」：
 * <ul>
 *   <li>{@link #READ} —— 查询。做错了没有代价。<b>签发时的默认档。</b></li>
 *   <li>{@link #WRITE} —— 建订阅、暂停/恢复、触发任务、重试下载。做错了在界面上点几下就能撤销。</li>
 *   <li>{@link #ADMIN} —— 删订阅、重置集状态、拉黑种子。撤销要重新配置或重新下载。</li>
 * </ul>
 * 注意 ADMIN 档<b>不等于</b>系统管理员：它只是这个令牌自己的权限上限。令牌实际能做什么，
 * 还要再过一遍 OSR 本身的权限判定（订阅归属、{@code BaseController#isAdmin}）——
 * 给一个普通用户的令牌勾上 ADMIN，它照样删不掉别人的订阅。<b>两道门是与的关系。</b>
 * </p>
 * <p>
 * 还有一批操作<b>任何档都拿不到</b>，因为它们压根没有对应的工具：删网盘实际文件、删种、
 * 改索引器/下载器/媒体服务器/参数设置、用户与角色。理由见 {@code McpToolRegistry} 的类注释。
 * </p>
 *
 * @author Jack
 */
public enum McpScope
{
    /** 只读 */
    READ("read"),

    /** 可写：建订阅、触发任务、重试，不含删除与拉黑 */
    WRITE("write"),

    /** 含删除、重置、拉黑等难以撤销的操作 */
    ADMIN("admin");

    private final String code;

    McpScope(String code)
    {
        this.code = code;
    }

    public String code()
    {
        return code;
    }

    /** 本档是否覆盖 {@code required} 所要求的档位 */
    public boolean covers(McpScope required)
    {
        return required != null && this.ordinal() >= required.ordinal();
    }

    /**
     * 解析库里/入参里的档位字符串。
     * <p>
     * <b>认不出的一律当 {@link #READ}</b>，不抛异常也不当成更高的档：这个值来自数据库的
     * varchar 列，脏数据（历史行、手改、大小写）是可能的，而「认不出就放到最低档」的错误方向
     * 是令牌少能干几件事——用户会立刻发现并去改；反过来把认不出当成 ADMIN，
     * 则是一把权限比签发时更大的钥匙，而且没有任何现象。
     * </p>
     */
    public static McpScope parse(String value)
    {
        if (value == null)
        {
            return READ;
        }
        String normalized = value.trim().toLowerCase();
        for (McpScope scope : values())
        {
            if (scope.code.equals(normalized))
            {
                return scope;
            }
        }
        return READ;
    }
}
