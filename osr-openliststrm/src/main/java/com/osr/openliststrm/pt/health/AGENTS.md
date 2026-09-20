# 缺集体检

逾期缺集的分档诊断与聚合提醒。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- **缺集体检（`pt/health/`）是「自动补搜默认关」这个设计的配套，不是它的替代**。`auto_search` 的库默认值是 `'0'`，建订阅时也刻意不打开——每条开着的订阅每轮都要向每个索引器打满一整份检索计划（最多 6 步），全量开启会让追完的老剧长期空转。代价是「订阅建完就没再管过、集一直缺着」这个最常见的场景**一条提醒都没有**：补搜落空通知只对开着开关的订阅发，`StuckEpisodeSweepService` 管的是「下完了没入库」，追剧日历只按日期铺格子、不回答"这一格为什么还是灰的"。体检把这批订阅列出来并支持就地开启，方向是补上可见性而不是改默认值。五条不要改坏的：
  1. **分档不是严重程度，是处置方向**（`EpisodeHealthBucket`）：`OVERDUE_MISSING` 去看搜索链路、`OVERDUE_IN_FLIGHT` 去看下载/上传链路、`BLOCKED` 要人工介入、`NO_AIR_DATE` 连"逾期"都算不出来。混成一个「有问题的集」列表，用户看到的是一堆无从下手的条目。判定优先级是 `BLOCKED` > `NO_AIR_DATE` > 按状态分——熔断是终态，有没有播出日期都不改变处置。
  2. **`air_date` 为 NULL 的取向与补搜侧相反，这是有意的**。`SearchSupplementService#aired` 把 null 当"已播出"（不让不可靠的日期否决业务动作）；体检把它单列成 `NO_AIR_DATE` 并把 `overdueDays` 置为 **null 而不是 0**。同一个字段、相反的兜底，因为代价不同：那边多搜一次，这边会在升级后的第一天把一屏未定档/未同步的集报成「逾期无穷天」。0 的语义是"今天刚播"，与"算不出来"混用还会让前端按天数倒序时把这批顶到最前。电影订阅**整体不参与体检**：它没有播出日期、逾期天数恒为 null，全堆在这一档既报不出问题，又会把真正缺集的剧集淹掉——而且那样报是错的，电影上映后几周内没有资源是正常状态不是故障（实测库里电影数比剧集还多）。这只影响体检的可见性，电影的 RSS 匹配/自动补搜/手动搜索一律照常。判据放在 `EpisodeHealthService#scan` 而不是 SQL：电影每条订阅只贡献一行哨兵集记录，推到 SQL 省不下什么，放 Java 侧能被单测盖住。
  3. **诊断挂在集上，不挂在订阅上**（`EpisodeHealthDiagnosis`）。同一条订阅完全可能一部分集缺着、另一部分卡在上传，压成一个"主诊断"就得定一套武断的优先级，而那个优先级对用户没有意义。订阅行展示的是去重后按枚举声明顺序排列的诊断集合。判据全部来自已落库的字段（`auto_search` / `last_auto_search_no_result` / `last_auto_search_reject_sign` / `state` / `file_confirmed`），**整个体检是一次纯 SQL 查询，不打任何外部请求**——与 `PtCalendarService` 同一个姿势，页面刷新不该变成一轮 TMDb/索引器调用。`SEARCH_NO_CANDIDATE` 与 `SEARCH_ALL_REJECTED` 必须分开（一个去改关键词和索引器，一个去松过滤规则，方向完全相反），这个区分在 `last_auto_search_reject_sign` 里已经用 `NO_CANDIDATE` 这个显式取值表达过了。
  4. **通知只发 `OVERDUE_MISSING` 一档，页面展示全部四档**。另外三档各自已经有人管：在途逾期由 `StuckEpisodeSweepService` 发 `LIBRARY_STUCK`、熔断在转 BLOCKED 那一刻通知过、无播出日期那档拿去打扰用户只会稀释信号。这条边界一旦模糊，同一集会从两三个渠道各通知一次，用户很快会把整类通知关掉。`EPISODE_OVERDUE` 也**不能并进 `SUBSCRIPTION_SEARCH`**——那一条只对开着开关的订阅发，而这一条要覆盖的恰恰是没开开关、压根没人在搜的那批；合并的话，用户关掉「补搜落空」（那类确实容易嫌吵）会连带把最需要的这条也关掉。
  5. **按收件人聚合成一条消息，不是每条订阅发一条**。首次启用时积压的订阅可能有几十条，逐条发比不提醒还糟。这与被明令禁止的「本轮汇总」通知的区别在于**这里没有对应的逐条通知可供重复**——不发这条就一条都没有。去重靠 `last_overdue_notify_sign`（集数 + 排序去重的集号，带集数前缀是为了压低 255 字截断后的碰撞——长篇动画的集号串常共享一长串相同前缀）：指纹变了立刻再发，没变则按 `pt.health.notify-repeat-days`（默认 7 天）重提醒。**只按"发过就不再发"是不行的**——一部永远补不上的剧提醒一次之后就再无声息，而它最该被记住。缺集补齐后必须 `updateOverdueNotifyState(id, null, null)` 清空，否则同一部剧下次再缺同一批集时指纹相等、通知被静默吞掉。写这两列走 `updateOverdueNotifyState` 而**不是 `updateById(sub)`**，理由与 `updateAutoSearchMissState` 完全相同（整实体写回会把补搜链路刚更新的 `last_search_time` 覆盖成旧值，让订阅永远"已到期"、每次心跳都重搜，且没有任何错误现象）。
  阈值 `pt.health.overdue-days` 默认 3 天是刻意的宽松：热门剧几小时内就有资源，但冷门剧、原盘小组、等字幕的片子拖一两天是常态，判早了会把一批正在正常走流程的集报成问题，而一个总在误报的看板用户看两次就不看了。
- **`pt/health/` 是只读诊断层，绝不许在里面改集状态或推送下载**。它读 `pt_subscription_episode` 与 `pt_subscription` 算出「缺了几集、缺了多久、为什么还缺」，唯一的写操作是通知去重的两列（`last_overdue_notify_sign` / `last_overdue_notify_time`）。想让它顺手把 `MISSING` 的集退回重搜、或把长期缺集的订阅自动暂停，都会与 `StuckEpisodeSweepService`、`AutoSearchService`、`DownloadTrackService` 抢同一份状态机——那三处的退回逻辑各自带着熔断计数与并发条件更新，多一个写入方就没人说得清一集是被谁改的。页面上的处置动作（开启自动补搜、立即补搜）走 Controller 显式调用既有服务（`searchAndPushMissing`），体检本身不发起任何动作。
- **`EpisodeHealthService#scan(LocalDate)` 的 today 是参数，不是 `LocalDate.now()`**，为的是让逾期天数那套算术能被测试钉住。**刻意不为此加第二个构造器注入 `Clock`**：一个 bean 有多个构造器时 Spring 不会自己挑，没标 `@Autowired` 就退回去找默认构造器、找不到就整个应用装配失败，而单测直接 new、绕开 Spring、全绿——`LoginAttemptService` 踩过这一次。需要注入时钟时优先改成传参。
