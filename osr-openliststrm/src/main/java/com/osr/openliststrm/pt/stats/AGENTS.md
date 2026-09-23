# PT 统计仪表盘

归属隔离与聚合口径。前端取数见 `osr-web/src/AGENTS.md`。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- **统计仪表盘的每个端点都要带 `PtStatsScope`，聚合口径按「这件事发生的日期」而不是「推送日期」**。三条各自踩过：
  1. **归属隔离此前整块是漏的**。订阅列表按 `owner_user_id` 隔离，而 `/api/openliststrm/pt-stats/*` 全站裸奔——任何登录用户都能看到全站订阅总数，以及 Top 活跃订阅里<b>别人的剧名</b>，点进去又被订阅页的 `denyIfInaccessible` 挡住，等于一个"看得见摸不着"的泄漏面（首页的 PT 概览卡调的是同一组端点，跟着一起漏）。现在 Controller 解析出 `PtStatsScope`、Service 落成 `sub_id IN (SELECT id FROM pt_subscription WHERE owner_user_id = ? OR owner_user_id IS NULL)`，判据与订阅侧同一条。**删掉那几个 `inSql` 不会让任何功能报错**，所以 `PtStatsRestControllerTest`/`PtStatsServiceTest` 里那几条断言是它唯一的守卫。
  2. **失败原因必须按 `fail_reason_code` 聚合，不能按 `fail_reason` 文案**。文案里嵌着实际值——`"种子内不含任何目标集（包内第 5,6 集，本次要补第 7 集）"`、`"下载超过 24 小时仍未完成"`（小时数来自配置，用户一调又裂出一类）——按文案 GROUP BY 得到的是一堆计数为 1 的碎片，饼图完全读不出"主要卡在哪"。这正是 `RejectCode` 当初存在的理由，而失败侧的 `FailReasonCode` 一直都在，只是统计这边没用上（服务里原本还写着一句"固定两种文案"的过时注释）。历史行 `fail_reason_code` 为 NULL，`COALESCE` 归到 `OTHER`，否则图上会出现一个叫 "null" 的扇形。
  3. **趋势图三条线各按自己的日期列分组**：推送 `pushed_time`、完成 `completed_time`、失败 `update_time`+`state='FAILED'`。全挂在推送日的话，今天推送的种子今天多半还没下完，最后一天的完成数恒定趴在底部——看起来像"今天全挂了"，而那只是记账口径；30 天前推送、昨天才失败的记录也会记到 30 天前那一格。失败没有专属时间列，但**处于 FAILED 状态的记录，它的 `update_time` 就是被判失败的那一刻**（H&R 采样只动已完成的记录，进度回写只动下载中的，重试会把状态改回 PUSHED），是个在这个状态上准确的代理列。失败原因分布同口径。
  配套：`pt_download_record` 此前一条时间列索引都没有（20260796 补了 `idx_pushed_time`/`idx_completed_time`/`idx_state_update_time`），而这张表只增不减、没有保留策略。
- **总览（`/overview`）跟随统计区间，成功率分母是「完成 + 未被接替的失败」**。两件事都踩过：统计卡就摆在「统计范围」挡位正下方，而 overview 原先不收 `days`、恒为全部历史，用户会把全量数字当区间数字读；成功率原先是 `完成 / 全部记录`，分母里混着已推送 / 下载中的记录，下载越活跃成功率越低，已经被后续推送补上的失败也照算（一集失败一次、补上一次就是 50%）。现在：不带 `days` 仍是全部历史（首页 PT 概览卡、MCP `get_pt_stats` 不带时），带了按白名单归一；推送 / 完成 / 失败各按自己的日期列落区间（同趋势图）；订阅数与 `hrViolatedCount` 是当前状态、不受区间影响。
- **「未被接替的失败」的 SQL 判据只有 `pt/task/UnresolvedFailureSql` 一份**，统计总览、Top 订阅的失败数、下载记录列表的「隐藏已接替」筛选共用；它与 `DownloadRecordAdminService#markSuperseded/covers`（给卡片打「已由 #N 接替」标记的内存判定）是**同一条规则的两种写法，改一边必须改另一边**——漂移的表现是列表里标着已接替、统计里却还算失败，不报错，只是数字对不上。它是按表名 `pt_download_record` 引用外层行的相关子查询，**外层不能起别名**。失败原因分布与趋势图的失败线仍统计全部失败：它们回答的是「失败都卡在哪」，被补上的那次失败也是有效样本。
