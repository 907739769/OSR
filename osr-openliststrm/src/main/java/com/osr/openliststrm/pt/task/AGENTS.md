# PT 周期任务

定期补搜的节奏、媒体库对账的并发、正常路径上的高频日志。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- **定期自动补搜的节奏（`AutoSearchService`）有四层修正，每层都对应一个具体故障**：
  1. **未播出的集不参与**（`SearchSupplementService#aired`）——这是 `pt_subscription_episode.air_date` 除追剧日历之外的第二个消费者。一部刚播到第 3 集的 12 集新番，剩下 9 集恒为 MISSING，于是这条订阅每轮都「有缺集」、每轮都打满一整轮索引器请求、每轮都落空，而用户收到的是「未找到可用资源，可检查关键词与索引器配置」——真实原因是**还没播**。省下的请求是次要的，避免这条误导才是主因。`air_date` 为 null 一律按已播出处理（可能未定档、TMDb 未录入，也可能只是存量行还没被每 12 小时的同步任务扫到），取向与「撤档时不清空已有日期」一致：日期本身不够可靠，不能让它单方面否决业务动作。电影压根不参与日期同步，这条对电影恒真。
  2. **候选收窄在 SQL 层**（`listAutoSearchCandidates`：ACTIVE + 开关开着 + 有 MISSING 集；**不含 IN_FLIGHT**，那是已经在下了、补搜对它无事可做）。追完的老剧长期留在 ACTIVE 是常态，拉全部 ACTIVE 再内存过滤等于每轮为它们各查一次集表。
  3. **到期时刻按 id 派生的确定性抖动（0 ~ +20%，只向后）**。首次启动时所有订阅的 `last_search_time` 都是 null、全体同时到期，串行跑完后它们的时间又几乎相同，一个周期后再次聚在一起——**这个抱团是自我维持的，不会自己散开**。只向后偏移保证实际周期不会短于用户配的值；用 id 而不是随机数，同一订阅每轮算出同一个偏移，行为可复现也写得出测试。
  4. **单轮总耗时预算**（`pt.search.auto-search-round-budget-ms`，默认 20 分钟，刻意小于 30 分钟的心跳）。订阅之间是串行的，而一条剧集订阅的墙钟由最慢的索引器决定（`indexer-budget-ms` 还是软上限），最坏 40~50 秒，几十条就能跑过一个心跳周期、让下一次心跳被 `AutoSearchTask` 的重叠保护整个吞掉。超预算的订阅**原样留到下一轮**（`last_search_time` 未改动，下轮天然从断点接着走，不会饿死）。**刻意不用并发解决**：`IndexerRateLimiter` 是全局的，多条订阅同时搜只会在限流器上排队并把等待推到 `max-wait-ms` 之外触发静默跳过——就是 `executePlan` 那条注释里记下的坑。
  
  另有两条关于「落空」的：**按连续落空次数退避**（`last_auto_search_no_result` 已从 char(1) 的 0/1 改成计数，24 → 48 → 96 小时，封顶一周；命中一次立刻回到基准）——片源确实不存在的老剧原本会永远每 24 小时打满一整轮请求，而这件事用户从现象上根本看不出来，日志里每轮都「正常地」搜了一遍；**落空通知除「首次落空」外，原因种类变了要再发一次**（`last_auto_search_reject_sign` 存排序去重的 reason_code 指纹）。指纹**不含计数**：摘要里「98 个非免费种」的数字每轮都在变，含进去等于每轮都判「原因变了」、去重彻底失效。「压根没搜到候选」记作 `NO_CANDIDATE` 参与比较——它与「候选全被 freeOnly 淘汰」处置方向完全相反，这个翻转恰恰是用户最需要知道的一次变化，不能被去重吃掉。
- **不要记「缓存命中」这类正常路径上的高频事件**。`TmdbCacheService` 曾对每次命中打一行 DEBUG，实测一次对账 236 行、占全量日志的 **22%**，而它零诊断价值——没人会因为「命中了」去查什么。未命中那一侧由 `TMDbApiService` 的「回源请求」标记（两级缓存都没命中才走到那里），想看命中率应该走指标而不是逐条日志。判断标准是「这条日志会让谁在什么时候做什么决定」，答不上来就别打。
  **同一把尺子量出来的另外三处**（都在一份真实生产日志上量过）：
  1. **「一切正常」的每轮播报要改成「不正常才说」。** `RssPollService` 的「索引器[x]拉取窗口正常……余量 22.9 小时」是每索引器每轮无条件打的一句报平安，7 个索引器按 11 分钟一轮算就是 **917 行/天**；而实测余量是 4.2~23.6 小时、轮询周期只有 10 分钟，也就是 25 倍以上的富余——那个数字不构成任何信息。现在只在余量收窄到 `COVERAGE_MARGIN_ALERT_ROUNDS`（3）轮以内时才打，从「报平安」变成「早期预警」，文案也相应改成「余量偏紧」。**阈值必须按实际间隔算而不是写死秒数**：`fail_count` 的退避能把间隔放大 32 倍，写死的话退避越狠、预警越迟钝，正好在最该提醒的时候失灵。`effectiveIntervalSeconds` 因此被 `isDue`、覆盖不全的 WARN 文案、余量判据三处共用——各写一份的话「默认 600」和退避倍数迟早漂移，而漂移的表现是判据与日志里报出来的数对不上。**「这一轮到底拉到了多少」不靠这行**，`pollOne` 那句 INFO 每轮都有。
  2. **传输层不要复述调用方 4 毫秒后要说的话。** `TorznabClient#fetch` 的「索引器[x]返回 N 条种子」与 `RssPollService#pollOne` 的「索引器[x]拉取到 N 条种子」是同一个数字、同一个索引器、相隔几毫秒——与当初 `ApiInterceptor` 和 `RequestLogFilter` 对同一个请求各打两行是同一个毛病，已删前者。**`search` / `searchByExternalId` 的两行保留**：那两条路径的调用方（`SearchSupplementService`）不会逐索引器再记一次计数，删了就真没有了。判据是「这条日志有没有另一个视角更完整的同义句」，不是「哪一层更靠近数据」。
  3. **批量注册/加载只报总数，例外逐条报。** `WatchServiceMonitor#registerAll` 原先每注册一个目录打一行，一台真实机器启动时刷 **229 行、215 毫秒内出完**，且**随媒体库规模线性增长**（大库上千很正常）。改成结束时一行「已注册 N 个目录监听：<根>」——`handleCreate` 发现新目录时也走同一个方法，那时 N 通常是 1，信息量与改造前逐条记完全相等。被跳过的临时目录**照旧逐条 INFO**：那是例外事件，恰恰是这里唯一有诊断价值的东西。
  这三处加上第三方库的启动噪音（telegrambots 的 `Ability` 每个 Bot 指令打一行英文 INFO，本项目 17 条，已在 logback 里单独压成 warn）**都是每次重启才发生一次，对日均字节几乎没有影响**——它们真正的代价是**重启后的头几百行全是废话，而重启往往正是为了确认某个改动生没生效**。降噪不只看总量，也看「最需要读日志的那一刻，前几屏是什么」。

  **那条回源日志打 `req.url().encodedPath()` 而不是完整 URL**：query 里带着 `api_key`，打完整 URL 等于把密钥写进保留 7 天的日志文件。路径（`/3/tv/1399/season/23`）本身已经足够认出是哪部剧的哪一季。它也**不能放回 `TmdbCacheService`**——那里手里只有 MD5 缓存键，一轮播出日期同步刷 55 行 `key=<hash>`，一部剧都认不出来，这正是下面「日志必须能认出是哪部剧」那条要治的毛病。
- **`LibrarySyncService` 能用并发而 `AutoSearchService` 刻意不用，两者不矛盾**——差别在下游是谁。补搜打的是索引器、受全局 `IndexerRateLimiter` 约束，并发只会在限流器上排队并把等待推过 `max-wait-ms` 触发静默跳过；对账打的是 Emby（局域网自建服务）与 TMDb（`TMDbApiService` 里已有全局 `Semaphore(4)`，且详情有两级缓存，很少真的发请求）。**并发度的真正卡点是数据库连接**：`SubscriptionService#refresh` 带 `@Transactional`，事务里夹着一次 Emby 网络往返，所以每条并发对账都会在整个往返期间占住一个连接（Druid `maxActive=50`）；`pt.library.sync-concurrency` 默认 4，要调到几十请先想清楚那是在跟所有在线请求抢连接。`refreshAll` **必须等齐才返回**：调用方紧接着跑 `StuckEpisodeSweepService`，那一步依赖「本轮刚被推进 IN_LIBRARY 的集」已经落库，提前返回会让这些集在同一轮里被当成卡死的在途集、发出一批本不该发的 `LIBRARY_STUCK`。
- **`DownloadTrackService.track()` 现在跑两批记录**：`trackActive` 管 PUSHED/DOWNLOADING，`trackSeeding` 管 COMPLETED 且 `hr_state=PENDING`。后者用 `IPtDownloadRecordPlusService#listSeedingPending` 而不是内联 QueryWrapper——两者是语义完全不同的集合，混在同一个泛化 `list()` 调用里读不出意图，测试里 27 个 `list(any(Wrapper))` 通用桩也无从区分
- **`trySelectFiles` 发现「包内一个目标集都没有」时必须中止，绝不能照常下发排除指令**：把全部视频文件 `prio=0` 发给 qB 会得到一个 0 字节、永远挂着的任务，占着 `max_concurrent` 名额直到僵尸超时（默认 24h）；某些 qB 版本还会把「全部文件不下载」直接判成 completed，那时记录转 COMPLETED 连僵尸兜底都够不着，只能等 12 小时后的 `StuckEpisodeSweepService`。中止走 `FailReasonCode.NO_TARGET_EPISODE`（不可重试——判据来自下载器的真实文件列表，这个包对该订阅确实没用）且**不累加 `fail_count`**（占位范围估错 ≠ 这一集补不到货，口径同 `reconcileClaims`）。判定的三条保守约束也与 `reconcileClaims` 同源：`actualEpisodes` 为空、电影订阅、订阅已删除，一律不判。**中止后 `trackActive` 必须 `continue`**——后面的 `markDownloading` 是无条件 `updateById`，会把刚置 FAILED 的记录复活成 DOWNLOADING，这是 `trySelectFiles` 返回 boolean 的唯一原因
- **`deleteTorrent` 是「OSR 从不删种」唯一的例外，边界必须可证明**：`DownloadTrackService#removeUselessTorrent` 只删 `!isCompleted() && seedingSeconds == 0` 的种子。H&R 考核从下载完成才开始计，这样的种子根本不在考核范围内。两个条件任一不成立就留着让用户处置——留一个垃圾任务的代价，远小于误删一个正在保种的种子。别处一律不要调用 `IDownloaderClient#deleteTorrent`
- **回退集时 `download_id` 必须走 `UpdateWrapper.set("download_id", null)`**：实体字段的 `null` 会被 MyBatis-Plus 当作「不更新」跳过，`set.setDownloadId(null)` 是一句空操作。漏掉的话退回 MISSING/IN_LIBRARY 的集仍指着那条 FAILED 记录，用户在下载记录页手动重试它时会把这些集又拖回在途。四处回退路径（`reconcileClaims`、`releaseInFlightEpisodes`、`revertUpgradingEpisodes`、`StuckEpisodeSweepService#sweep`）口径必须一致
- **`PtStatusWebSocket#pushDownloadEvent` 的 `completedTime` / `failReasonCode` / `hrState` 取自 `record` 本身**，所以 `complete` / `doFail` 在条件更新成功后、推送前要把刚落库的值写回 `record`（落库用的是另一个 `set` 对象，`record` 上原本还是旧值）。漏写不报错，下载记录页原地更新后「完成时间」一直空着、失败卡片没有分类标签，刷新才对得上。
- **下载记录页的「已由 #x 接替」**（`DownloadRecordAdminService#markSuperseded`）：重试与补搜成功时都是**新建**记录，失败那条原样留着。判定为同订阅、id 更大、覆盖同一集；**失败的季包不因后续逐集推送算作被接替**——拿不准整季是否都补上了，宁可留着重试按钮。被接替的记录前端不再给重试，批量重试的生效范围也同步收掉。
