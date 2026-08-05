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
