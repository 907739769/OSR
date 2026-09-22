# 追剧日历

播出日期同步与集号对齐。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- **追剧日历的数据来自 `pt_subscription_episode.air_date`，由 `EpisodeAirDateSyncTask` 每 12 小时同步**（首轮兼做存量回填，所以升级上来的库不需要单独迁移动作）。播出日期本来就会变——改档、提前放送、季中休播——一次性回填脚本解决不了，定期同步是必需的而不是图省事。查询侧 `PtCalendarService` 是纯 SQL 范围查询，不打 TMDb。两条容易踩的：TMDb 撤掉日期时**不清空**已有值（撤档信息本身不可靠，清掉会让这集从日历上凭空消失）；同步只写日期确实变了的行，否则每 12 小时把整表 `update_time` 刷一遍，看起来像天天都有变化。
- **「待播出」（`UNAIRED`）是前端派生的展示态，后端没有这个值**（`usePtCalendar#displayState`）：MISSING 且 `airDate > today` 的显示为待播出，当天播出算已播出，与 `SubscriptionService#aired` 同口径。它跟着 `today` 重算——页面开着过夜，昨天的待播出今天要变回缺失。日历里「搜这一集」「查看诊断」只对真实的 MISSING（诊断还含 IN_FLIGHT/BLOCKED）出现，待播出的集去了也搜不到、体检里也查不到。
- **月历取数范围是网格的 6×7=42 天（`GRID_DAYS`），不是「月末所在那一周」**：只占 5 周的月份，后者会让网格第 6 行永远是空的。图例计数与「全部 N」**只算本月**，否则网格首尾溢出的上下月几天会让图例上的数与本月格子里数得出来的对不上。
- **订阅页手动「对账」会先同步这一条的播出日期（`EpisodeAirDateSyncService#syncQuietly`），但绝不能挂进 `SubscriptionService#refresh`**：后者也被 `LibrarySyncService` 每 10 分钟对全部订阅调一遍，挂进去等于每轮每条订阅多打一次 TMDb。`syncQuietly` 失败只记 WARN 不抛，日期取不到不该让对账本身报错。
