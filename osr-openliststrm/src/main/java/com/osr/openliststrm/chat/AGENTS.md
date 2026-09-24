# 聊天指令（企业微信 / Telegram 共用）

PT 订阅的聊天指令：订阅、我的订阅、下载中、最近入库、进度、补搜、暂停/恢复。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## STRUCTURE
- `PtChatCommandService`：指令解析与执行，**唯一的一份**。企微（`wecom/WeComCommandService`）与 TG（`tg/TgPtCommandHandler`）都只做「解析身份 → 调它 → 把回复发回去」。
- `ChatUser`：渠道解析好的身份（会话键、OSR 用户、「我的账号」文案、事后补发回调）。
- `ChatReply`：正文 + 可选按钮。
- `ChatSessionStore`：多轮选择（选片、选季）的内存会话，键带渠道前缀（`wecom:xxx` / `tg:123`）。

## NOTES
- **新指令只加在 `PtChatCommandService` 里**，两个渠道自动都有。TG 若要斜杠命令，在 `StrmBot` 加一个 `ptAbility(...)` 的一行方法并在 `registerCommands` 登记——斜杠命令只是「中文指令 + 参数」的别名（`/sub 三体` ≡ `订阅 三体`），不要在 TG 侧另写解析。企微菜单同理走 `WeComCommandService.MENU_COMMANDS` 映射成指令文本。
- **`ChatReply` 的正文必须自给自足**：企微文本消息没有按钮，只看得到正文。按钮只是 TG 上的快捷方式，它能做的事，用户照正文手打指令也必须能做到（`搜索候选_正文里也列出了序号` 守着这条）。
- **选择类按钮带会话 id**（`#<会话id>:<序号>`），不能直接用裸序号。TG 旧消息上的按钮一直点得到：用户搜过 A 又搜了 B，回头点 A 那一轮的「1」，裸序号会被当成 B 那一轮的第 1 项，订上一部他没选的片。会话 id 对不上就回「已过期」。企微里手打的裸序号仍按当前会话处理。TG 侧选完还会顺手把那组按钮收掉，但那只是体验优化，**判据是会话 id，不是按钮消失**（收按钮的 API 调用可能失败）。
- **TG 按钮的 `callback_data` 上限 64 字节**，超一个整条消息都发不出去。`TgPtCommandHandler#keyboard` 会省掉超长按钮并打 WARN。中文每字 3 字节，把剧名塞进指令里很容易超——按钮指令只放编号。
- **补搜是异步的**：`searchAndPushMissing` 可能跑几分钟（季搜索 + 单集补发各有预算）。聊天里先回「已开始」，搜完经 `ChatUser#laterReply` 补发结果。同一订阅在跑时再触发会被 `searching` 集合拦下，否则 TG 按钮连点几下就是并发搜几轮、同一资源推几次。**`searching.remove` 必须在补发结果之前**：用户收到结果立刻再点一次补搜，不能被判成「正在补搜中」。
- **TG 身份**：Bot 只接受配置里那一个 Telegram 用户（`creatorId`），映射到 OSR 超级管理员（id 1），能看全部订阅，从 TG 建的订阅也归管理员。纯文本和按钮回调都不经过 AbilityBot 的 privacy 校验，`StrmBot#ptText` / `TgPtCommandHandler#handleCallback` 各自校验了发送者，**新增 `Reply` 时别漏掉这一步**。
- **TG 纯文本 Reply 要避开 `/strmdir`、`/syncdir` 的追问回复**：那两个 Ability 自己的 reply 在接用户回的路径，`ptText` 再接一次会把路径当成订阅指令回一句「看不懂」。AbilityBot 会执行**所有**条件满足的 Reply，不是只挑一个。
