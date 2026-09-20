# PT 媒体服务器接入

多台服务器的查询语义、被动连通状态、对账源不可用时的降级。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- **媒体服务器是「启用的<b>全部</b>参与查询，任一台命中即算已入库」，判据只有 `IPtMediaServerPlusService#listActive()` 一份**。早先是 `getActive()`（`enabled='1' ORDER BY id LIMIT 1`），而配置页是个能添任意多台、每台一个独立启用开关的列表——用户配上 Emby + Jellyfin 两台、两台都显示「启用」、两台都能测试连接成功，**实际只有 id 最小的那台参与入库判定**，另一台是彻底的死配置，页面上没有任何一处看得出来。症状是「我明明配了 Jellyfin，订阅进度却按 Emby 走」，而日志里每一步都正常。现在的语义与页面呈现一致，也顺带支持了「电影在 Emby、剧集在 Jellyfin」「新旧两台并存迁移中」这两种真实用法。四条不要改坏的：
  1. **每台各跑一遍那三条集号规则，不要几台共用一个 `wholeSeries` 变量**（`SubscriptionService#queryLibraryOn`）。`listAllEpisodeNumbers` 的懒加载是按服务器各自持有的，共用会让 A 的全剧编号被拿去判 B 的命中。
  2. **一台不通只是少命中几集，不影响其余服务器，也不影响对账本身**——对账只升不降，少命中的集维持原状态，下一轮它恢复了再补上。所以异常要 `continue` 而不是整个 return。
  3. **这条订阅要的集已经全齐时跳出循环**（`coversAllRequested`），常见的单台配置一次多余请求都不发。
  4. **退回单台语义不会让任何功能报错**，所以 `SubscriptionServiceTest` 里那三条（并集 / 一台不通另一台顶上 / 电影第二台有）是它唯一的守卫——实测把 `listActive()` 截成第一台，正好红这三条。
- **媒体服务器的连通状态是<b>被动</b>记录的：对账每次真正用到某台服务器时把结果写回**（`pt_media_server.last_check_time/ok/error`，收口在 `MediaServerHealthRecorder`，20260797）。媒体服务器是入库判定的唯一数据来源，而它挂掉的**全部**可见症状是「订阅进度不动」——配置页上原先一个信号都没有（三行写的是 类型 / 地址 / **创建时间**），隔壁索引器至少有 `fail_count` 可看。四条：
  1. **被动而不是另起一个周期任务去打 `/System/Info`**：探针通了不代表查得到数据（反代只转发了一部分路径、API Key 权限不足、库没挂上，都是探针 200 而业务查询空手而归），而这里要回答的恰恰是「对账用它的时候好不好使」——那也正是下一条里卡死清扫用来决定跳不跳过的判据。代价是库里一条 ACTIVE 订阅都没有时状态不刷新。
  2. **落库必须节流**。`queryLibrary` 是**每条订阅**调一次的，实测 95 条订阅、对账每 10 分钟一轮，不节流就是每 10 分钟 95 次 UPDATE 刷一张几乎不变的配置表。规则是「状态变了立刻写，没变则按 10 分钟刷一次时间」，与 `EpisodeAirDateSyncTask` 只写日期确实变了的行同一条取向，也保证了「刚刚坏掉」「刚刚恢复」这两个唯一有信息量的时刻一定落得进库。决策在 `ConcurrentHashMap#compute` 的重映射函数里做、**落库在函数外做**（那里持着 bin 锁，官方文档禁止耗时操作，`IndexerCapabilityCache` 踩过）。
  3. **写回走 `updateProbeResult` 的 `LambdaUpdateWrapper`，绝不能 `updateById(实体)`**：连通成功时要把 `last_check_error` 清空，而 NOT_NULL 策略会跳过 null，清空根本写不进去——故障恢复之后页面上永远挂着上一次的错误原因；而且 `api_key` 带 `EncryptedStringTypeHandler`，调用方手里那份可能是脱敏过的。同 `updateAutoSearchMissState` 那条坑。
  4. **`last_check_ok` 为 NULL 是「还没被用到过」，不是「不通」**（后端 `PtMediaServerPlus#lastCheckFailed()`、前端 `composables/mediaServerHealth.ts` 共用这条判据，两端卡片也共用后者）。混成一个 boolean 会让一台刚添的、完全正常的服务器在页面上显示成红色的「不可用」。
- **卡死在途集清扫在「对账源不可用」时整体跳过，判据是<b>通不通</b>而不只是<b>配没配</b>**（`StuckEpisodeSweepService#sweep`）。该类的注释早就写着「没有媒体服务器 = 没有对账依据，清扫等于把每一次成功的下载都判成卡死」，而那段推理对「配了但全都连不上」逐字成立——只是原先没覆盖：Emby 宕机超过 `stuck-episode-timeout-hours`（默认 12 小时）之后，未 `file_confirmed` 的在途集会被退回 MISSING 并累加 `fail_count`，连续 `max-consecutive-failures` 次熔断成 BLOCKED，而故障期间每一轮都满足同样的条件。判据取上一条那个被动写回的连通状态（不是现打一次探针——探针通了不代表查得到数据），时序上也正好：`LibrarySyncTask` 每轮先 `refreshAll` 写回状态再调本方法。**要求「全部」启用项都失败才跳过**，还有一台通就说明对账有依据；**`last_check_ok` 为 null 不算失败**，否则新装库上清扫会整体停摆。删掉这个分支不会让任何别的用例变红，`StuckEpisodeSweepServiceTest` 里那三条是它唯一的守卫。
- **`queryLibrary` 的两条 WARN 已按服务器加 `FaultThrottle` 节流**：不加的话遍历会让它们乘以台数，而一个还没配媒体服务器的新装库本来就是每天 `订阅数 × 144` 行逐字相同的 WARN（实测 95 条订阅 ≈ 13700 行/天）。节流的 key 是 serverId（一台都没配时是一个固定常量），**不能与 `missingInLibrary` 那个按 tmdbId 分组的实例混用**——那样一次宕机会被记成几十个互不相干的故障，每个各自放行一条「首次失败」，节流形同虚设。
- **仍未修的一条**：**「测试连接」把真实原因吞成一句泛化文案**（`EmbyClient#testConnection` 返回 boolean，Controller 只能回「连接失败，请检查地址、API Key 与网络」）。401（Key 错）、连接超时（地址/网络错）、反代返回 HTML 错误页三种情况的处置方向完全不同，现在全压成同一句，真实原因只留在后端日志里，而用户手上就是那个弹窗。顺带：成功那侧已经把版本号取出来了却只 `log.info`，回显出来能让用户确认连的是不是自己以为的那台。
