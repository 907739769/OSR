# OpenList-strm 核心业务模块知识库

## OVERVIEW
OSR (OpenList STRM Relay) 核心业务层，负责 STRM 生成、文件夹同步、Telegram Bot、企业微信、文件重命名、任务调度、第三方回调、PT 订阅管理、重命名一致性检查等业务逻辑。21 个子包按功能域划分。

## STRUCTURE
```
com/osr/openliststrm/
├── api/              # OpenList API 客户端 (网盘操作封装)
├── config/           # 业务配置类 (OpenlistConfig 等)
├── controller/       # REST API 端点 (STRM/同步/任务配置/回调)
├── controller/api/   # 第三方开放 API (qb/callback、企微回调 等)
├── dashboard/        # 首页概览统计
├── enums/            # 业务枚举 (任务状态、类型等)
├── helper/           # 辅助工具 (文件操作、路径处理)
├── monitor/          # 任务监控与状态追踪 (MediaRenameProcessor 等)
├── mybatisplus/      # ★ MP 风格数据层 (domain/mapper/service)
├── notify/           # 通知渠道抽象 (INotifier + TG/Webhook/企微实现)
├── openai/           # AI 相关功能 (OpenAIClient)
├── orphan/           # 重命名一致性检查 (孤儿扫描/清理/忽略)
├── pt/               # PT 订阅管理 (downloader/indexer/subscription/media server)
├── rename/           # 影视文件重命名 (MediaParser/TitleProcessor/PebbleRenderer)
├── req/              # 请求 DTO
├── scrape/           # 文件刮削 (ScrapeService 等)
├── service/          # 业务服务层 (IStrmService/ICopyService 等)
├── task/             # 定时任务 + 手动任务执行 (OpenListStrmTask)
├── tg/               # Telegram Bot (StrmBot/TgBotRegister/ResponseHandler)
├── tmdb/             # TMDB 电影/剧集信息查询 (TMDbClient)
├── upload/           # 文件上传处理
└── wecom/            # 企业微信自建应用 (API客户端/回调加解密/指令交互)
```

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| STRM 生成逻辑 | `task/` + `service/` | OpenListStrmTask, IStrmService |
| 文件夹同步 | `service/` | ICopyService, 增量/全量同步 |
| Telegram Bot | `tg/` | StrmBot (7 个指令), TgBotRegister |
| 企业微信 | `wecom/` + `controller/api/WeComCallbackController` | 收发消息、订阅指令、成员绑定 |
| 通知渠道 | `notify/` | INotifier / NotifierManager / NotifyTarget |
| TMDB 查询 | `tmdb/` | TMDbClient, 元数据获取/增强 |
| 文件重命名 | `rename/` | MediaParser + OpenAI + Pebble 模板 |
| 重命名一致性检查 | `orphan/` | RenameOrphanScanServiceImpl, OrphanReconciler |
| PT 订阅管理 | `pt/` | Downloader/Indexer/Subscription/MediaServer |
| 文件刮削 | `scrape/` | ScrapeService, TMDb 刮削/文件删除 |
| 任务监控 | `monitor/` | MediaRenameProcessor 等处理器 |
| 任务配置 | `mybatisplus/domain/` + `controller/` | 所有 *Plus 实体 |
| 第三方回调 | `controller/api/` | QB 下载完成通知等开放 API |
| MP Mapper | `mybatisplus/mapper/` | BaseMapper 接口 |
| MP Service | `mybatisplus/service/` | IService 接口 + Impl |

## CONVENTIONS
- **按功能域分包**，非按层分包 (tg/, tmdb/, rename/, orphan/, pt/ 各自独立)
- **数据层**: 使用 MyBatis-Plus (BaseMapper + IService +ServiceImpl)，XML Mapper 在 `resources/mapper/mybatisplus/`
- **Controller 只负责** 参数接收、调用 Service、返回响应，不写业务逻辑
- **枚举优先**: 任务状态、类型等使用 enum，不用魔法数字
- **FastJSON2**: 所有 JSON 序列化/反序列化统一使用 FastJSON2
- **异步任务**: 使用虚拟线程 (Java 25 preview) 处理并发 IO
- **孤儿判定**: `orphan/OrphanReconciler` 纯逻辑无 I/O，方便单测覆盖；`RenameOrphanScanServiceImpl` 负责实际 I/O
- **重命名流程**: `MediaParser.parse()` → 本地正则抽取 → TMDb 增强 → AI 补充 (如需) → Pebble 模板渲染
- **`TMDbClient#search` 的两层循环：外层换标题、内层降级年份，顺序不能反**。标题是强信号、年份是弱信号，该先放宽弱信号而不是先换强信号。旧实现是「所有标题带年份各试一遍 → 所有标题不带年份各试一遍」，于是「次要标题 + 年份碰巧对上」会打败「主标题 + 年份对不上」：`开始推理吧.The.Truth.S04E18.2026...` 里主标题带 2026 搜不到，接着 englishTitle "The Truth" 撞上一部 2026 年首播的同名剧被直接采纳，整部剧重命名成别的作品。`TMDbClientSearchTest` 守着这个顺序
- **剧集搜索一律不传年份，电影才传**。`TMDbApiService#search` 对剧集用的过滤参数是 `first_air_date_year`（**剧集首播年**），而文件名里的年份是发布组随手填的（可能是首播年，也可能是本季/本集播出年），两者只在第一季才相等——对 S2 以上的剧集这个过滤器在构造上就是错的，哪怕发布组填得完全正确也一定过滤不到正确答案。年份对剧集的甄别改由 `scoreCandidate` 的接近度软打分承担（年份对得上 +30，对不上还能靠热度兜底；硬过滤会把同名重启剧的两个版本一起滤掉，反而更差）。电影的 `primary_release_year` 与文件名里的上映年是同一个量，语义正确，保留
- **`TorznabClient#getCaps` 探测失败返回 `null`，不要返回 `IndexerCapability.NONE`**。NONE 是合法探测结果（站点确实不支持 imdbid/tmdbid），把失败也塌成 NONE 就分不清「探明了不支持」与「压根没探明」。`IndexerCapabilityCache` 正是靠这个区分做差异化缓存：**成功永久缓存，失败只缓存 `pt.indexer.caps-retry-after-failure-ms`（默认 5 分钟）后重探**。旧实现用 `computeIfAbsent` 一视同仁地永久缓存，一次网络抖动或一次限流冷却（`IndexerRateLimiter` 冷却期内直接快速失败）就足以让该索引器在整个进程生命周期内永远走不到 ID 精确搜索，且没有任何日志说得出为什么。缓存也刻意**不再用 `computeIfAbsent`**——它在 mapping function 执行期间持有 bin 锁，而那里要发 HTTP 请求，`ConcurrentHashMap` 文档明确禁止；改成「查-探-写」后并发首访最多多探一次 caps，GET 幂等且限流器本就按索引器串行化
- **「请求没发出去」必须与「请求失败了」分开记账**。`IndexerRateLimiter` 的两处快速失败（冷却期内 `awaitNextAllowed`、等许可超时 `acquire`）抛 `IndexerBackpressureException`，`RssPollService#pollOne` 为它单开一条 catch 分支，**既不累加也不清零 `fail_count`**。不分开的话 429 分支「不计失败」的设计会被原样绕开：命中 429 → penalize 冷却 300 秒（本次不计失败）→ 冷却期内的后续轮次全部快速失败 → 每轮 fail_count +1 → 退避把周期放大 2~32 倍 → 两次**成功**拉取之间的窗口从几分钟变成几小时 → 报「拉取窗口覆盖不全」→ 用户缩短轮询周期 → 请求更密、更容易撞冷却，正反馈。三条 catch 的顺序是 `IndexerBackpressureException` → `IndexerHttpException`(isThrottled) → 其余，前两者都是 IOException 子类，漏掉任一个都会静默落回通用分支。**不要顺手把 `InterruptedIOException` 也归进背压**——`SocketTimeoutException` 是它的子类，那是货真价实的读超时
- **「拉取窗口覆盖不全」有三种成因，日志必须带够判据**：①单页容量跟不上发布速度（`pubDate 跨度` 明显小于轮询周期，只有这种情形告警字面意思才成立）；②`fail_count` 不为 0，退避把实际间隔放大了 2~32 倍，游标比的是两次**成功**拉取之间的窗口，改配置里的周期无济于事；③索引器 guid 不稳定（带一次性 token/时间戳，或 `TorznabParser` 在 guid 缺失时降级用了 downloadUrl），同一条目每轮 guid 都不同，游标永远匹配不上，于是**每一轮**都告警，与间隔无关。`RssPollService#describeWindow` 就是为区分这三者而存在的。**告警文案不要再写「建议缩短轮询间隔」**，它只对成因①成立，对②是反效果。**日志一律只打 guid 的 SHA-256 前 8 位 + 条目标题，绝不能打 guid 原文**——guid 降级来源是 downloadUrl，PT 站的下载链接里常含 passkey
- **`searchByExternalId` 拼 `season`/`ep` 前必须判 null**。两者都是 `Integer`，`String.valueOf(Integer)` 解析到 `String.valueOf(Object)`，为 null 时产出字面量 `"null"` 并原样拼进 URL（`&season=null`），索引器多半直接 400。`SubscriptionMatcher` 明确把 `sub.getSeason()==null` 当作可能状态处理，这里不能比它更乐观
- **比较用标题归一化只有一份：`rename/TitleNormalizer#normalizeForCompare`**，PT 订阅匹配（`SubscriptionMatcher#normalize`）与 TMDb 刮削（`TMDbClient#normalizeForCompare`）都委托给它，**任一侧都不要另写**。分叉过一次：刮削侧剥掉全部标点、PT 侧只处理 `. _ -`，于是《神探夏洛克：可恶的新娘》在刮削侧能匹配、在订阅匹配侧却因一个全角冒号漏搜。字符类要覆盖 `\p{Punct}`（ASCII，含 `~ + = < > | $ ^` 这些 Unicode 归为 Symbol 的）、`\p{IsPunctuation}`（全角/CJK 标点、破折号、`_`）、`\p{IsWhite_Space}`（含全角空格 U+3000——Java 的 `\s` 不认它）、以及显式的 `～`(U+FF5E)/`〜`(U+301C)（Unicode 里算 Symbol 不算 Punctuation）。**标点替换成空格而不是删除**：删除会让 `M*A*S*H` 塌成 `mash` 误撞另一部叫 MASH 的作品，也会让 `The Office US` 塌得离 `The Office` 更近。归一化结果**只用于比较，绝不参与任何输出**
- **两侧的比较方式仍然不同，这是有意的**：PT 侧只认全等（结论直接决定推哪个种子，推错就是下错内容），刮削侧允许「长包含短」（只用来决定证据够不够采纳，还有 AI 兜底）。共用的是字符归一化，不是判定策略
- **TMDb 结果有采纳门槛，且门槛与打分必须共用 `titleMatchLevel`**。`doSearchOnce` 打分挑出冠军后还要过 `hasEnoughEvidence`：**标题命中 或 年份差 ≤1**，两者都不满足就当本次未命中返回 null，让 `search()` 降级到下一个候选标题，全部落空则交给 AI 兜底。没有门槛的话「搜到正确答案」与「搜到一堆垃圾、靠 TMDb 相关度排序蒙了个第一」走同一条路。**不要改成「分数 ≥ 某阈值」**：`scoreCandidate` 的分数是候选间排序用的相对量，把 0/100 的离散项、−10~+30 的档位项和无上界的热度项（`log1p(popularity)*2`）加在一起，量纲是混的，设数值门槛等于设了一道「是不是中文作品」的门槛。两处共用同一个 `titleMatchLevel` 也是硬要求——判据不一致时会出现「候选 X 标题命中但年份偏、候选 Y 标题不命中但年份准」，打分选中 Y 再靠年份混过门槛，X 连被检验的机会都没有
- **`titleMatchLevel` 是语言中立的**，别退回 `getOfficialChineseTitle`（那个要求候选 name/title 含中文，英文剧/日番/韩剧恒拿 0 分）。判据是解析出的三个标题 × 候选的规范名与原始名，归一化后全等记 2 分档、一方包含另一方记 1 分档；包含关系要求较短一方达到最小长度（拉丁 4 / CJK 2——单个汉字假名的信息量远高于拉丁字母），否则 `Up`、`It` 会被任意长标题包含
- **TMDb 的辅助请求失败不能拖垮整次刮削**。`TMDbApiService#executeAndReturnString` 在 HTTP 失败（404 / 429 重试耗尽 / 5xx）时返回 **null**，而 `mapper.readTree(null)` 抛 `IllegalArgumentException`。`fetchChineseAlias` 曾因此把一次可有可无的别名查询失败放大成整次刮削作废（异常冒泡到 `enrich` 的 `catch(Exception)`），而且那时 `tmdbId` 已写入 info、`needsAI` 变 false 连 AI 兜底都不触发，只剩一个没有标题和详情的半成品。新增任何 `mapper.readTree(api.xxx(...))` 都要先判 null
- **`TitleProcessor` 的方括号分支要求括号内真的含中文**。原正则捕获组是 `[^\]】]+`，任意内容都收，于是 `[Nekomoe kissaten]`、`[FRDS]` 这类发布组/站点标签被当成中文标题，真正的作品名被挤进 `englishTitle`——PT 侧标题匹配不上（漏搜），重命名侧拿着发布组名去 TMDb 搜索，而且 `MediaParser#needsAI` 的条件是 `tmdbId` 为空，一旦搜到<b>任何</b>结果 AI 兜底就不再触发，错误命名是安静发生的
- **`YearSeasonEpisodeExtractor` 的年份取最后一个匹配，不是第一个**。片名本身就是四位年份的作品（《1917》《2012》《1984》《2046》）在 `1917.2019.1080p...` 这类名字里会让第一个匹配落在片名上，年份取成 1917、标题又被按它的位置截成空串。发行年总排在片名之后，取最后一个才是它；截断后标题为空时年份值照留但不参与截断（`2012.1080p` 这种没有发行年的名字）
- **PT 订阅**: RSS 轮询使用 `MediaParser.parseLocal()` 仅本地正则，不查 TMDb 避免配额耗尽
- **`MediaParser.parseLocal()` 不剥扩展名**（`stripExtension=false`），种子标题本来就没有扩展名。**测试夹具不要给种子标题补 `.mkv`**：补了之后标题以 " mkv" 结尾，`SourceAndGroupExtractor` 的 `GROUP_END` 正则（要求结尾是 `-xxx`）匹配不到发布组，`parsedReleaseGroup` 恒为 null，一切依赖发布组的逻辑（发布组黑名单、发布组优先级）都会静默失效。这个错误前提曾同时写进 `SubscriptionEngineTest` 与 `PtTorrentBlacklistPlusServiceImplTest` 并让两条用例长期红着
- **H&R 是站点属性，不是种子属性**：Torznab 协议没有标准的 H&R 字段，索引器不会逐条告知哪个种子要考核，只能按 `pt_indexer.hr_enabled` 整站判定。`hr_enabled=1` 但两个阈值都为 0 属于不完整配置，`PtIndexerPlus#hitAndRunEnabled()` 会按未启用处理——否则种子会永远停在"保种中"并反复提醒。达标判定是**或**关系（做满 N 小时 **或** 分享率达到 R），与站点通行表述一致
- **`DownloadTrackService.track()` 现在跑两批记录**：`trackActive` 管 PUSHED/DOWNLOADING，`trackSeeding` 管 COMPLETED 且 `hr_state=PENDING`。后者用 `IPtDownloadRecordPlusService#listSeedingPending` 而不是内联 QueryWrapper——两者是语义完全不同的集合，混在同一个泛化 `list()` 调用里读不出意图，测试里 27 个 `list(any(Wrapper))` 通用桩也无从区分
- **`trySelectFiles` 发现「包内一个目标集都没有」时必须中止，绝不能照常下发排除指令**：把全部视频文件 `prio=0` 发给 qB 会得到一个 0 字节、永远挂着的任务，占着 `max_concurrent` 名额直到僵尸超时（默认 24h）；某些 qB 版本还会把「全部文件不下载」直接判成 completed，那时记录转 COMPLETED 连僵尸兜底都够不着，只能等 12 小时后的 `StuckEpisodeSweepService`。中止走 `FailReasonCode.NO_TARGET_EPISODE`（不可重试——判据来自下载器的真实文件列表，这个包对该订阅确实没用）且**不累加 `fail_count`**（占位范围估错 ≠ 这一集补不到货，口径同 `reconcileClaims`）。判定的三条保守约束也与 `reconcileClaims` 同源：`actualEpisodes` 为空、电影订阅、订阅已删除，一律不判。**中止后 `trackActive` 必须 `continue`**——后面的 `markDownloading` 是无条件 `updateById`，会把刚置 FAILED 的记录复活成 DOWNLOADING，这是 `trySelectFiles` 返回 boolean 的唯一原因
- **多集包以暂停态推送，选完目标集文件才启动**（`SubscriptionEngine#shouldPauseOnAdd` → `DownloadTrackService#trySelectFiles` 末尾 `resumeTorrent`）。推送那一刻不知道包里有哪几集，不暂停的话这段窗口期已经在下非目标集了。四条约束：①**单集/电影不暂停**——没有选错文件的可能，暂停只是白等一轮轮询；②**磁力链不暂停**——下载器在暂停态下不下载磁力元数据，`listFiles` 永远为空，种子会永远等不到启动，比不暂停糟得多；③`resumeTorrent` 必须排在 `markFilesSelected` **之前**，否则它失败一次就再也没有第二次机会（标了 selected 就不再进 `trySelectFiles`）；④`resumeTorrent` 对已在下载的种子是幂等空操作，因此**不需要**落库记录"当初是不是暂停加进来的"
- **暂停加种必须有元数据超时兜底，且判据是「超时 + 进度为 0」两条**（`METADATA_TIMEOUT_MILLIS`，30 分钟）。只判时间会误杀：文件选不出来也可能是下载器 API 临时故障而种子正常下载中，那种情况下中止会连带删掉一个下得好好的种子。没有这个兜底，暂停的种子在种子损坏/无人做种/OSR 恰好在启动前重启时会永远停在暂停态，占着并发名额直到 24 小时僵尸超时——而僵尸超时判的是"下载不动"，语义也对不上
- **`deleteTorrent` 是「OSR 从不删种」唯一的例外，边界必须可证明**：`DownloadTrackService#removeUselessTorrent` 只删 `!isCompleted() && seedingSeconds == 0` 的种子。H&R 考核从下载完成才开始计，这样的种子根本不在考核范围内。两个条件任一不成立就留着让用户处置——留一个垃圾任务的代价，远小于误删一个正在保种的种子。别处一律不要调用 `IDownloaderClient#deleteTorrent`
- **qB 5.0 把 `resume`/`paused` 改名成 `start`/`stopped`**：add 时两个参数都发（qB 忽略不认识的字段），启动则先试 `/torrents/resume`、404 再试 `/torrents/start`，成功的端点按 downloaderId 缓存下来，避免之后每次都先撞一个 404。Transmission 没有这个问题（`paused` + `torrent-start` 跨版本稳定）
- **回退集时 `download_id` 必须走 `UpdateWrapper.set("download_id", null)`**：实体字段的 `null` 会被 MyBatis-Plus 当作「不更新」跳过，`set.setDownloadId(null)` 是一句空操作。漏掉的话退回 MISSING/IN_LIBRARY 的集仍指着那条 FAILED 记录，用户在下载记录页手动重试它时会把这些集又拖回在途。四处回退路径（`reconcileClaims`、`releaseInFlightEpisodes`、`revertUpgradingEpisodes`、`StuckEpisodeSweepService#sweep`）口径必须一致
- **OSR 从不删种**。`hr_state=VIOLATED` 是"已经发生"的事实（用户手删或下载器自动管理清掉了），只发告警，系统不会也无法自动补救。主动防线只有推送后按站点规则下发 `setShareLimits`——**Transmission 的 RPC 没有"最短做种时长"概念**（`seedIdleLimit` 是"空闲多久后停"，语义不同，不能拿来充数），该维度对 Transmission 只能靠 OSR 侧追踪告警兜底
- **洗版判定绝不能引入 SEEDERS / SIZE / FREE 维度**（`UpgradeDimension` 只有 RESOLUTION/SOURCE/TAG/RELEASE_GROUP）。那些取值随时间连续变化：同分辨率但做种更多的种子会被判成"更优"，下完之后下一轮又冒出别的做种更多的，于是无限洗版。现有四个维度取值都来自有限集合，字典序比较构成全预序，数学上不存在 A 优于 B 且 B 优于 A 的环——这是"不会来回洗"的唯一保证，`UpgradeEvaluatorTest.比较关系无环_任意两个画像至多一个方向成立` 守着它
- **cutoff（`pt_upgrade_config.target_*`）不是可选优化**：没有终止条件的话，每一集都会永远搜下去把索引器配额烧干。三项全空时 `hasTarget()` 为 false、洗版不激活，这是刻意的安全默认
- **`UPGRADING → IN_LIBRARY` 只能由下载完成驱动，不能交给 Emby 对账**。`SubscriptionService#refresh` 判"在不在库里"靠 Emby 查询，而旧版本本来就在库里、查询恒命中，对账分不出同一集的新旧版本——所以 refresh 刻意跳过 UPGRADING，收尾在 `DownloadTrackService#finishUpgrade`，并在那里同步刷新 `quality` 基线（不刷的话下一轮扫描仍按旧画像判断，会反复洗同一集）
- **洗版失败退回 IN_LIBRARY 而不是 MISSING，且不累加 `fail_count`**。旧文件一直在库里，退成 MISSING 会让这一集显示成缺失并被 RSS 从头重下；累加 fail_count 会让几次洗版失败把一个明明已入库的集熔断成 BLOCKED
- **第一期洗版不碰旧文件**：OSR 从不删种，新旧版本同时存在，清理由用户手动完成。自动清理（第二期）必须先检查旧种子 `hr_state ∈ {SATISFIED, null}`——删掉还在 H&R 考核期内的种子的文件，等于亲手制造一次记过
- **电影年份判定统一走 `SubscriptionMatcher#movieYearMatches`，容差 1 年，两条链路不许各写一份**。RSS 自动匹配（`SubscriptionMatcher` 电影分支）与搜索补集（`SearchSupplementService#filterMovieCandidates`）对「这个候选是不是这部电影」必须给出同一个答案，只改一处会出现「手动搜索能选中、RSS 却当它不匹配」这种说不清的不一致（标题归一化 `normalizeAll` 共用同一份也是这个理由）。容差取 1 是因为电影节首映 vs 正式公映、年末跨年上映会让同一部电影在 TMDb 与发布组标注之间差一年；**不要放宽到 2 及以上**——每放宽一年同名翻拍串台的风险就实打实增加，而"正好差两年"的同一部电影几乎不存在。**任一侧缺年份仍判不匹配**：电影没有季集号可交叉验证，年份是唯一能区分同名作品的信号
- **`FilterCriteria` 一律用 `FilterCriteria.builder()` 构造**，不要用位置参数：16 个分量里有 9 个是 `List<String>`，顺序写反编译器发现不了；新增维度时 builder 调用方也不必补占位参数
- **`TorrentFilterEngine` 只有 2 参与 4 参两种签名，不要再加三参重载**。历史上 `(…, TorrentBlacklist)` 与 `(…, String originalLanguage)` 两个三参重载只靠第三参类型区分，`SearchSupplementService` 调错了版本，导致手动搜索候选列表不受黑名单约束，用户选中后推送侧再拦下，只回一个没有原因的失败
- **种子的 `parsedTags` 是 `MediaInfo.tags` + 视频编码 + 音频编码的并集**（见 `SubscriptionEngine#collectTags`）。extractor 按 Resolution → Codec → SourceAndGroup 顺序跑，`CodecExtractor` 会先把 `Atmos`/`H265`/`DTS-HD` 匹进 `audioCodec`/`videoCodec` 并从标题里抹掉，只读 `tags` 的话「必须带 Atmos」这类配置会一条都匹配不上

- **订阅相关通知必须带 `NotifyTarget`**：`TgHelper.sendMsg(type, msg)` 是广播，只适用于系统级告警（索引器失败、复制任务超时）。凡是「某条订阅」的动态（命中/完成/失败/入库/补搜落空），一律走 `TgHelper.sendMsg(type, msg, NotifyTarget.owner(sub.getOwnerUserId()))`，否则 A 的下载动态会推到 B 的企微上。各 Service 里的 `notifySafely` 私有方法已统一改成带归属参数的签名，新增通知点照抄相邻写法即可。`ownerUserId` 为 null 表示无归属（历史订阅），自动退化为广播
- **`pt_subscription.owner_user_id` 允许为 NULL 且必须继续允许**：该列是后加的，历史订阅全为 NULL。NULL 语义是「无归属的公共订阅，所有人可见」；改成非空或把 NULL 当作「归属于某个不存在的人」，会让升级后所有老订阅从非管理员的列表里整批消失。可见性判定统一为「管理员看全部；其余人看 `owner_user_id = 自己 OR IS NULL`」，Web 端在 `PtSubscriptionRestController`、企微端在 `WeComCommandService#requireAccessible`，两处口径必须一致
- **企微回调是 `@Anonymous` 端点**：请求来自企微服务器，不可能带 JWT。安全性靠签名校验 + AES 解密 + receiveid 比对三重保证，三者都依赖只有配置方知道的 Token/AESKey/corpid。回调<b>不做被动回复</b>而是立即返回空串、异步处理完再主动推送——企微要求 5 秒内响应，而建订阅要串行调 TMDb 搜索+详情+媒体库对账，被动回复必然超时并触发企微重试（同一条指令被执行多次）

## ANTI-PATTERNS
- 不要在 Controller 中写业务逻辑
- 不要混用 XML Mapper 和 MP BaseMapper (本模块只用 MP)
- 不要在 Service 中直接操作 HTTP 请求，封装到 api/ 或 helper/
- Telegram Bot handler 不要超过 50 行，复杂逻辑抽到独立方法
- PT 订阅 RSS 轮询不要逐条查 TMDb (配额爆炸)，用 `parseLocal()` 仅本地正则
- 孤儿扫描不要重复提醒已忽略项 (`status=2` 直接 SKIP)
