# 通知渠道与路由

notify_route 的语义、文案转义、PT 通知的固定首行。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- **通知的「发不发、发给谁」由 `notify_route` 表决定，渠道实现只管「怎么发」**。改造前每个渠道自己读 `openlist.notify.{channel}.types` 判断类型，渠道一多就没法统一配置，也没有收件人这一维。现在 `NotifierManager` 查路由，`NotifyRouteService` 整表缓存（通知是热路径，写入后调 `invalidate()`）。三条容易做错的语义：
  1. **路由缺失按「发送」处理**。新增通知类型或新增渠道时路由行还没补上，宁可多发也不能静默丢——丢通知的故障用户根本发现不了。
  2. **`OWNER` 档在通知无归属时回退默认接收人，不是丢弃**。系统级告警（索引器故障、复制超时）本来就没有归属人，理解成丢弃会让这类告警凭空消失。
  3. **不支持分人的渠道一律退化为广播**。TG 只有一个 chat id、Webhook 只有一个 URL，靠 `INotifier#supportsDirectDelivery()` 声明；配置页对这些渠道**不展示**收件人选项——给出一个不生效的开关比缺少这个功能更糟。
  新增渠道只要实现 `INotifier`（`channelKey()` 一旦发布就不能改，改了等于把用户已有路由配置全丢），配置页会自动多出一列。
- **通知文案统一按 Telegram 的 HTML parse_mode 写，其余渠道靠 `WeComNotifier#toPlainText` 还原**。所有动态内容（种子标题、剧名、索引器名）都要过 `StringUtils.escapeHtml`——TG 用 `ParseMode.HTML`，不转义的话标题里一个 `<` 就能让整条消息发不出去。代价是其余渠道拿到的是转义过的串，所以 `toPlainText` 必须**两步都做**：去标签 + 解实体，且**顺序不能换**（先解实体的话，用户内容里字面的 `&lt;b&gt;` 会先还原成 `<b>`、紧接着被去标签那步删掉，文本凭空少一截）。只做去标签踩过一次：企微/Bark/Gotify 上 `Tom & Jerry` 显示成 `Tom &amp; Jerry`，而 `&` 在 PT 片名和组名里相当常见。新增渠道时照抄 Bark/Gotify 的做法，不要直接透传 `message`。
- **PT 通知的第一行统一是「哪部作品的哪一集」，实现只有 `PtNotifyText#subject` 一份**。命中、完成、失败、季包、H&R 讲的本就是同一集，说法不一致时用户对不上号——原先命中说《三体》S01E05、完成和失败只给种子标题，而国内站的标题常带一长串站点前缀、季包更是整季一个名字。同类的还有 `PtNotifyText#size`（GB 以下自动降到 MB）、`#elapsed`、`#torrentProfile`（分辨率/来源/体积/做种数/站点 + 免费与 H&R 标记）。这些方法里**已经**做过 `escapeHtml`，调用方直接拼接，不要再转一次。**片段缺失时整段不写，不写「未知」**：一句「分辨率：未知」不帮用户做任何判断，只把真正有用的几段挤下去。
- **失败通知必须带 `fail_reason`**。四种 `FailReasonCode` 的处置方向完全不同（僵尸种要换资源、`TORRENT_NOT_FOUND` 要看下载器是不是被清了），原因已经落库且写得足够具体，不带进通知的话用户收到后唯一能做的是打开页面重查一遍。熔断提示（连续失败达阈值）**拼进同一条失败通知**而不是紧跟着再发一条：讲的是同一次失败，分两条既多一次打扰，原先那条还走 GENERAL 类型、在路由上和索引器故障混在一起。
- **拆分已有 `NotificationType` 必须配一条迁移，把新类型的路由行按拆分来源复制一份**（见 `20260783-notify-type-split.sql`）。新增取值本身是安全的（路由缺失按「发送」处理），但拆分会让用户对旧类型的关闭设置落空——明确关掉「下载完成」的人升级后会突然开始收到 H&R 达标提醒，他的设置没变、行为却变了。继承要**逐渠道**做（同一类型在 TG 上开着、企微上关着是常见配置），`recipient_scope` 一并继承。
- **不要发「本轮汇总」类通知**。`RssPollService` 原先每轮发一条「为订阅推送了 N 个种子」，三条理由各自都够删掉它：与逐条「订阅命中」完全重复（推 3 个 → 3 条详情 + 1 条只有数字的汇总）；它是广播（拿不到归属人），多用户下 B 会收到一个自己无从追查的数字；信息量本就为零，同样的内容 `log.info` 已经记了。反例是 `StuckEpisodeSweepService` 的按订阅聚合——那是把**几十条**逐集通知压成一条，方向相反。
- **订阅相关通知必须带 `NotifyTarget`**：`TgHelper.sendMsg(type, msg)` 是广播，只适用于系统级告警（索引器失败、复制任务超时）。凡是「某条订阅」的动态（命中/完成/失败/入库/补搜落空），一律走 `TgHelper.sendMsg(type, msg, NotifyTarget.owner(sub.getOwnerUserId()))`，否则 A 的下载动态会推到 B 的企微上。各 Service 里的 `notifySafely` 私有方法已统一改成带归属参数的签名，新增通知点照抄相邻写法即可。`ownerUserId` 为 null 表示无归属（历史订阅），自动退化为广播
- **通知类型是路由的一维，归错类等于用户关不掉或误关**。`NotificationType` 现有 8 个取值，几条容易踩的归属：H&R 达标/违规走 `HR_STATE` 而**不是** DOWNLOAD_COMPLETE/DOWNLOAD_FAILED（挂着的话，关掉「下载完成」的用户就再也收不到「可以安全删种了」——那一条直接对应一块能腾出来的磁盘和一份能卸下的保种义务）；补搜落空走 `SUBSCRIPTION_SEARCH` 而不是 GENERAL（后者是索引器故障、复制超时那类系统告警，处置方向完全不同）；`StuckEpisodeSweepService` 的全部通知走 `LIBRARY_STUCK` 而不是 SUBSCRIPTION_HIT（内容是「卡住了/退回缺失/已熔断」，挂在「订阅命中」下语义正好相反），也不并进 DOWNLOAD_FAILED——下载本身成功了，卡的是上传网盘或 STRM/刮削那一段，重下解决不了。`NotificationType#urgent()` 决定 Gotify priority 与 Bark level，只有 GENERAL/DOWNLOAD_FAILED/LIBRARY_STUCK 为真：全抬高等于逼用户整渠道静音，连真告警一起丢。
- **缺行格子的默认值只有后端一份（`NotifyRouteService#defaultRoute`），前端不许再自己补**。`/matrix` 下发的 `routes` 已按「类型 × 渠道」补齐：开启；支持分人的渠道是 `OWNER`，其余 `ADMIN`——这正是 `NotifierManager#applyScope` 在路由为 null 时「原样投递」的等价写法（调用方的目标只有 `BROADCAST` 与 `owner(x)` 两种形态，改写结果与 `SCOPE_OWNER` 逐一相同）。原先前端补的是「仅管理员」，而 `EPISODE_OVERDUE` 从来没有行、企微缺行时实际发的是订阅人：页面显示「仅管理员」、实际发订阅人，用户改别处顺手一保存，这一格就真的变成只发管理员，订阅人从此收不到、管理员却收到所有人的缺集提醒，全程无提示。`20260799` 已把缺的行（`EPISODE_OVERDUE` 全部 + Bark/Gotify 全部）按同一规则补齐。**新增通知类型或渠道仍然不必配迁移**（缺行时页面与分发两边的默认值一致），拆分类型照旧要配（见上面那条）。
- **整表保存走 `NotifyRouteService#saveAll`：一次读全表 + 一个事务写入 + 提交之后再失效缓存**。三件事各有来由：原先 Controller 里逐格 `getOne`（9×5 就是 45 次查询）；`saveBatch` 与 `updateBatchById` 分别提交，第二批失败时第一批已经生效；而缓存失效**必须在事务外、提交之后**——放在事务里的话，失效与提交之间的读者会按新代数把尚未提交的旧数据写回缓存。事务用注入的 `TransactionOperations`（而不是 `@Transactional`）正是为了把「失效」摆到提交之后，自调用的 `@Transactional` 也不会生效。
- **路由缓存带代数校验**（`generation`）。读者在保存之前开始查库、在 `invalidate()` 之后才写回，没有这层校验的话旧配置会一直生效到下次保存——用户关掉的通知照发，而且不报任何错。「比较代数」与「写缓存」在同一把锁里，中间插不进一次失效。`NotifyRouteServiceTest` 那条「重建期间发生失效」钉住它。
- **每个渠道都要实现 `INotifier#sendTest`，失败必须如实返回原因**（配置页「发送测试」）。做法是把发送主体抽成返回失败原因的 `deliver(...)`：`send` 拿到原因只记 warn（通知不能影响业务），`sendTest` 原样带回页面。原因要具体到能行动：Gotify 401 点明「application token 而不是 client token」、Telegram 取 `TelegramApiRequestException#getApiResponse()`（`getMessage()` 只有一句 "Error sending message"）、企微走 `WeComApiClient#sendTextDetailed`（接收人**全部**无效时企微仍返回 errcode=0，这种情况也报失败）。**失败原因会进日志，不许带地址原文**：Bark 的 Key 在路径里、不少 Webhook 把密钥放在 URL 上。测试**绕过路由**、发给默认接收人——它问的是「这个渠道通不通」。
- **`NotificationType` 的 `description` 是给配置页看的**，写「什么时候会收到」，不写实现细节。页面原先显示枚举名，而相近类型（补搜落空 / 缺集逾期 / 入库卡住）的区别恰恰决定了用户该关哪一个。新增取值时一并写上。

- **通知上的快捷操作（`NotifyAction`）是一条条聊天指令**（「补搜 12」「重试下载 34」「拉黑种子 34」「进度 12」），不是另一套回调协议：身份、归属校验、执行、回执全部复用 `chat/PtChatCommandService`。Telegram 渲染成内联按钮（`TgSendMsg` 借 `TgPtCommandHandler#keyboard`，回调由同一个 Bot Token 的 `StrmBot` 长轮询收到），企业微信渲染成「可直接回复」的提示行（`WeComNotifier#actionHint`），其余渠道忽略——**正文必须自给自足**。三条不要改坏的：
  1. **没有操作时分发器仍调渠道的三参数 `send`**（`NotifierManager#send` 四参数版里的分支），与引入前逐字节一致；只有带操作时才调 `INotifier#send(..., actions)`。
  2. **指令字符串只在 `NotifyAction` 的几个工厂方法里拼**，要与 `PtChatCommandService` 的解析前缀一致；改了一边没改另一边的症状是「按钮点了回一句看不懂」。
  3. 目前带操作的：下载失败（重试 / 拉黑 / 进度）、补搜落空（立即补搜 / 进度）、入库卡住（进度）、缺集逾期聚合（每部订阅中的剧一个补搜，最多 4 个，已暂停的不给）。这几个服务的通知一律走 `TgHelper` 四参数重载（没有操作时传空表），测试里 `mockStatic` 的断言相应是四参数。
- **`WEEKLY_REPORT`（每周周报）由定时任务 104「openliststrm-每周周报」（`openListStrmTask.weeklyReport()`，每周一 9 点，迁移里默认暂停）触发**，实现在 `dashboard/report/WeeklyReportService`。三条：**数字全部复用现成统计口径**（首页趋势 `DashboardStatsService#trend` 与 `PtStatsService` 的 overview/failReasons/topSubscriptions，`PtStatsScope.ALL`），与页面上的数字对不上的话用户会开始怀疑哪个是真的；**AI 只写点评、不产出数字**，数字段落由代码拼好，AI 拿同一份事实写 2~4 句，调用失败照发不带点评的版本；AI 的回复同样要过 `escapeHtml`（TG 用 HTML parse_mode，一个 `<` 就能让整条发不出去）。周报是广播（无归属人），不支持分人的渠道照常退化。
