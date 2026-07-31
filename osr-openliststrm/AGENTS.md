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

## ANTI-PATTERNS
- 不要在 Controller 中写业务逻辑
- 不要混用 XML Mapper 和 MP BaseMapper (本模块只用 MP)
- 不要在 Service 中直接操作 HTTP 请求，封装到 api/ 或 helper/
- Telegram Bot handler 不要超过 50 行，复杂逻辑抽到独立方法
- PT 订阅 RSS 轮询不要逐条查 TMDb (配额爆炸)，用 `parseLocal()` 仅本地正则
- 孤儿扫描不要重复提醒已忽略项 (`status=2` 直接 SKIP)
