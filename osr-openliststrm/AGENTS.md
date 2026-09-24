# OpenList-strm 核心业务模块知识库

> 本文件只剩**模块总则**。**下列子包各有自己的 `AGENTS.md`，改到那个目录才载入**，
> 新增知识点写进对应子包，不要搬回这里：
> `pt/`（PT 总则）、`pt/subscription/`、`pt/task/`、`pt/indexer/`、`pt/downloader/`、`pt/filter/`、
> `pt/clean/`、`pt/upgrade/`、`pt/transfer/`、`pt/media/`、`pt/health/`、`pt/autoadd/`、`pt/stats/`、`pt/calendar/`、
> `rename/`、`rename/cleanup/`、`scrape/`、`orphan/`、`tmdb/`、`notify/`、`mcp/`、`monitor/`、
> `service/`、`helper/`、`controller/`、`mybatisplus/`、`wecom/`、`chat/`。
> 全局约定与日志纲领见仓库根 `AGENTS.md`。

## OVERVIEW
OSR (OpenList STRM Relay) 核心业务层，负责 STRM 生成、文件夹同步、Telegram Bot、企业微信、文件重命名、任务调度、第三方回调、PT 订阅管理、重命名一致性检查等业务逻辑。22 个子包按功能域划分。

## STRUCTURE
```
com/osr/openliststrm/
├── api/              # OpenList API 客户端 (网盘操作封装)
├── chat/             # 聊天指令 (企微与 TG 共用的 PT 订阅指令：PtChatCommandService)
├── config/           # 业务配置类 (OpenlistConfig 等)
├── controller/       # REST API 端点 (STRM/同步/任务配置/回调)
├── controller/api/   # 第三方开放 API (qb/callback、企微回调 等)
├── dashboard/        # 首页概览统计
├── enums/            # 业务枚举 (任务状态、类型等)
├── helper/           # 辅助工具 (文件操作、路径处理)
├── mcp/              # MCP 服务端 (端点 /mcp，令牌鉴权 + 工具集，供本地 AI 助理连接)
│   └── tool/         # 五组工具：订阅 / 追剧 / 下载 / 任务 / 运维
├── monitor/          # 任务监控与状态追踪 (MediaRenameProcessor 等)
├── mybatisplus/      # ★ MP 风格数据层 (domain/mapper/service)
├── notify/           # 通知渠道抽象 (INotifier + TG/Webhook/企微实现)
├── openai/           # AI 相关功能 (OpenAIClient)
├── orphan/           # 重命名一致性检查 (孤儿扫描/清理/忽略)
├── pt/               # PT 订阅管理 (downloader/indexer/subscription/media server)
│   ├── autoadd/      # 热门自动订阅 (TMDb 榜单 / RSSHub 豆瓣榜单 → 过滤 → 建订阅)
│   ├── clean/        # 自动删种 (体积区间 + 做种时长分级，辅种整组同删)
│   ├── health/       # 缺集体检 (逾期未入库的分档诊断 + 每日聚合提醒)
│   └── transfer/     # 转移做种 (qB → TR 搬种，IYUU「转移」的自建实现)
├── rename/           # 影视文件重命名 (MediaParser/TitleProcessor/PebbleRenderer)
│   └── cleanup/      # 产物清理 (ArtifactPaths 纯逻辑 + RenameCleanupService 实际 I/O)
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
| 复制任务监控 | `helper/` | AsynHelper（内存监控链）+ CopyRecoveryTask（重启兜底）+ CopyMonitorRegistry（心跳分工） |
| Telegram Bot | `tg/` | StrmBot（任务指令 + PT 订阅指令）, TgPtCommandHandler（PT 指令与内联按钮）, TgBotRegister |
| 订阅聊天指令 | `chat/` | PtChatCommandService，企微与 TG 共用 |
| 企业微信 | `wecom/` + `controller/api/WeComCallbackController` | 收发消息、订阅指令、成员绑定 |
| 通知渠道 | `notify/` | INotifier / NotifierManager / NotifyTarget |
| TMDB 查询 | `tmdb/` | TMDbClient, 元数据获取/增强 |
| 文件重命名 | `rename/` | MediaParser + OpenAI + Pebble 模板 |
| 重命名一致性检查 | `orphan/` | RenameOrphanScanServiceImpl（双向扫描）, OrphanReconciler, OrphanReason |
| 重命名产物清理 | `rename/cleanup/` | RenameCleanupService（purge/purgeRelocated/回收空目录）, ArtifactPaths |
| PT 订阅管理 | `pt/` | Downloader/Indexer/Subscription/MediaServer |
| PT 自动删种 | `pt/clean/` | TorrentCleanService（判定+执行）, TorrentCleanTask（默认每 60 分钟） |
| PT 缺集体检 | `pt/health/` | EpisodeHealthService（纯查询分档+诊断）, EpisodeHealthNotifyService/Task（每 24 小时） |
| 文件刮削 | `scrape/` | ScrapeService, TMDb 刮削/文件删除 |
| 任务监控 | `monitor/` | MediaRenameProcessor 等处理器 |
| 任务配置 | `mybatisplus/domain/` + `controller/` | 所有 *Plus 实体 |
| MCP 服务端 | `mcp/` | McpServerConfig（装配）/ McpAuthFilter（令牌）/ McpCallContext（身份绑定）/ McpToolRegistry（横切）/ tool/（工具声明） |
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

## ANTI-PATTERNS
- 不要在 Controller 中写业务逻辑
- 不要混用 XML Mapper 和 MP BaseMapper (本模块只用 MP)
- 不要在 Service 中直接操作 HTTP 请求，封装到 api/ 或 helper/
- Telegram Bot handler 不要超过 50 行，复杂逻辑抽到独立方法
- PT 订阅 RSS 轮询不要逐条查 TMDb (配额爆炸)，用 `parseLocal()` 仅本地正则
- 孤儿扫描不要重复提醒已忽略项 (`status=2` 直接 SKIP)
- 不要提供「删除源文件」的入口。目标库里的主文件是 `Files.copy` 出来的副本，删了重跑任务就回来；源目录（`original_path`）是网盘挂载或下载器保种目录，删它等于毁保种。**`pt/clean` 的自动删种不是这条的例外而是它的补集**：那条路径由下载器的 `deleteTorrent` 执行、只动已过 H&R 考核的保种文件、且要用户逐个下载器显式开启；重命名/孤儿/刮削这些模块一律不许自己去删源文件
