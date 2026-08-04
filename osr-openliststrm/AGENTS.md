# OpenList-strm 核心业务模块知识库

## OVERVIEW
OSR (OpenList STRM Relay) 核心业务层，负责 STRM 生成、文件夹同步、Telegram Bot、文件重命名、任务调度、第三方回调、PT 订阅管理、重命名一致性检查等业务逻辑。17 个子包按功能域划分。

## STRUCTURE
```
com/osr/openliststrm/
├── api/              # OpenList API 客户端 (网盘操作封装)
├── config/           # 业务配置类 (OpenlistConfig 等)
├── controller/       # REST API 端点 (STRM/同步/任务配置/回调)
├── controller/api/   # 第三方开放 API (qb/callback 等)
├── enums/            # 业务枚举 (任务状态、类型等)
├── helper/           # 辅助工具 (文件操作、路径处理)
├── monitor/          # 任务监控与状态追踪 (MediaRenameProcessor 等)
├── mybatisplus/      # ★ MP 风格数据层 (domain/mapper/service)
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
└── upload/           # 文件上传处理
```

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| STRM 生成逻辑 | `task/` + `service/` | OpenListStrmTask, IStrmService |
| 文件夹同步 | `service/` | ICopyService, 增量/全量同步 |
| Telegram Bot | `tg/` | StrmBot (7 个指令), TgBotRegister |
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
- **OSR 从不删种**。`hr_state=VIOLATED` 是"已经发生"的事实（用户手删或下载器自动管理清掉了），只发告警，系统不会也无法自动补救。主动防线只有推送后按站点规则下发 `setShareLimits`——**Transmission 的 RPC 没有"最短做种时长"概念**（`seedIdleLimit` 是"空闲多久后停"，语义不同，不能拿来充数），该维度对 Transmission 只能靠 OSR 侧追踪告警兜底
- **`FilterCriteria` 一律用 `FilterCriteria.builder()` 构造**，不要用位置参数：16 个分量里有 9 个是 `List<String>`，顺序写反编译器发现不了；新增维度时 builder 调用方也不必补占位参数
- **`TorrentFilterEngine` 只有 2 参与 4 参两种签名，不要再加三参重载**。历史上 `(…, TorrentBlacklist)` 与 `(…, String originalLanguage)` 两个三参重载只靠第三参类型区分，`SearchSupplementService` 调错了版本，导致手动搜索候选列表不受黑名单约束，用户选中后推送侧再拦下，只回一个没有原因的失败
- **种子的 `parsedTags` 是 `MediaInfo.tags` + 视频编码 + 音频编码的并集**（见 `SubscriptionEngine#collectTags`）。extractor 按 Resolution → Codec → SourceAndGroup 顺序跑，`CodecExtractor` 会先把 `Atmos`/`H265`/`DTS-HD` 匹进 `audioCodec`/`videoCodec` 并从标题里抹掉，只读 `tags` 的话「必须带 Atmos」这类配置会一条都匹配不上

## ANTI-PATTERNS
- 不要在 Controller 中写业务逻辑
- 不要混用 XML Mapper 和 MP BaseMapper (本模块只用 MP)
- 不要在 Service 中直接操作 HTTP 请求，封装到 api/ 或 helper/
- Telegram Bot handler 不要超过 50 行，复杂逻辑抽到独立方法
- PT 订阅 RSS 轮询不要逐条查 TMDb (配额爆炸)，用 `parseLocal()` 仅本地正则
- 孤儿扫描不要重复提醒已忽略项 (`status=2` 直接 SKIP)
