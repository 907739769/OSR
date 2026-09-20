# PT 索引器接入

Torznab 能力探测、限流记账、RSS 覆盖度游标、guid 日志。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **`TorznabClient#getCaps` 探测失败返回 `null`，不要返回 `IndexerCapability.NONE`**。NONE 是合法探测结果（站点确实不支持 imdbid/tmdbid），把失败也塌成 NONE 就分不清「探明了不支持」与「压根没探明」。`IndexerCapabilityCache` 正是靠这个区分做差异化缓存：**成功永久缓存，失败只缓存 `pt.indexer.caps-retry-after-failure-ms`（默认 5 分钟）后重探**。旧实现用 `computeIfAbsent` 一视同仁地永久缓存，一次网络抖动或一次限流冷却（`IndexerRateLimiter` 冷却期内直接快速失败）就足以让该索引器在整个进程生命周期内永远走不到 ID 精确搜索，且没有任何日志说得出为什么。缓存也刻意**不再用 `computeIfAbsent`**——它在 mapping function 执行期间持有 bin 锁，而那里要发 HTTP 请求，`ConcurrentHashMap` 文档明确禁止；改成「查-探-写」后并发首访最多多探一次 caps，GET 幂等且限流器本就按索引器串行化
- **「请求没发出去」必须与「请求失败了」分开记账**。`IndexerRateLimiter` 的两处快速失败（冷却期内 `awaitNextAllowed`、等许可超时 `acquire`）抛 `IndexerBackpressureException`，`RssPollService#pollOne` 为它单开一条 catch 分支，**既不累加也不清零 `fail_count`**。不分开的话 429 分支「不计失败」的设计会被原样绕开：命中 429 → penalize 冷却 300 秒（本次不计失败）→ 冷却期内的后续轮次全部快速失败 → 每轮 fail_count +1 → 退避把周期放大 2~32 倍 → 两次**成功**拉取之间的窗口从几分钟变成几小时 → 报「拉取窗口覆盖不全」→ 用户缩短轮询周期 → 请求更密、更容易撞冷却，正反馈。三条 catch 的顺序是 `IndexerBackpressureException` → `IndexerHttpException`(isThrottled) → 其余，前两者都是 IOException 子类，漏掉任一个都会静默落回通用分支。**不要顺手把 `InterruptedIOException` 也归进背压**——`SocketTimeoutException` 是它的子类，那是货真价实的读超时
- **RSS 覆盖度校验的游标是「时间位置」不是「条目身份」**：记上一轮的 `max(pubDate)`（存 `pt_indexer.last_seen_pub_time`），本轮判 `窗口下沿 <= 上轮游标`。旧的 guid 游标（"上一轮首条种子还在不在本轮结果里"）同时依赖三个前提，任一条不成立就**恒定误报**，而误报表现与真漏拉完全相同、用户唯一能做的反应（缩短周期）还恰好是错的：①**首条即最新**——把"索引器按 pubDate 降序返回"的假设当成事实，置顶/促销置顶会让游标记在非最新条目上；②**那条种子下一轮还在**——删种、审核下架、挪分类（而我们带着 `cat` 过滤）都会让它消失；③**guid 逐轮稳定**——部分索引器 guid 带一次性 token，`TorznabParser` 在 guid 缺失时还会降级用 downloadUrl。`last_seen_guid_hash` 保留，仅在 pubDate 全部不可解析时兜底，且告警文案必须写明"兜底判据"
- **窗口下沿取「`coverage-window-hours`(默认 24) 小时内条目的最早发布时间」，不是整页最老那条**。一条 2015 年的置顶种子会把整页最老时间拉到十年前，判据随之恒成立，真漏拉一起被静音（mteam 实测就是这个形态）。能放心剔除是因为截断线远大于轮询周期（默认 600 秒）——被剔的条目绝无可能影响"上轮到本轮这几分钟有没有被覆盖"，**不损失灵敏度，纯粹去噪**。不要改成分位数：置顶条数是绝对量，P90 在置顶多的站照样被污染、在置顶少的站白白牺牲余量
- **pubDate 超前当前时间 1 小时以上的条目必须剔除**（`FUTURE_TOLERANCE`）。站点时钟不同步或填错时，放任未来时间成为游标会让之后每一轮的 `下沿 <= 上轮游标` 恒成立，覆盖度判定**永久失效且完全静默**——比误报难查得多
- **日志一律只打 guid 的 SHA-256 前 8 位 + 条目标题，绝不能打 guid 原文**——guid 降级来源是 downloadUrl，PT 站的下载链接里常含 passkey。`describeNoise` 输出的"剔除 N 条更早的（最老 X）"就是这一页的置顶条数，排查时不必再去索引器后台翻发布时间分布
- **`searchByExternalId` 拼 `season`/`ep` 前必须判 null**。两者都是 `Integer`，`String.valueOf(Integer)` 解析到 `String.valueOf(Object)`，为 null 时产出字面量 `"null"` 并原样拼进 URL（`&season=null`），索引器多半直接 400。`SubscriptionMatcher` 明确把 `sub.getSeason()==null` 当作可能状态处理，这里不能比它更乐观
- **H&R 是站点属性，不是种子属性**：Torznab 协议没有标准的 H&R 字段，索引器不会逐条告知哪个种子要考核，只能按 `pt_indexer.hr_enabled` 整站判定。`hr_enabled=1` 但两个阈值都为 0 属于不完整配置，`PtIndexerPlus#hitAndRunEnabled()` 会按未启用处理——否则种子会永远停在"保种中"并反复提醒。达标判定是**或**关系（做满 N 小时 **或** 分享率达到 R），与站点通行表述一致
