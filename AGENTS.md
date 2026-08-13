# OSR (OpenList STRM Relay) 项目知识库

## OVERVIEW
影视 STRM 管理系统。Java 25 (Spring Boot 4.0.6) + Vue 3 + Vuetify 3，Docker 双容器部署。核心功能：STRM 文件生成、文件夹同步、Telegram Bot 控制、TMDb 刮削/重命名、第三方回调自动化。

> 本文件是本项目唯一的 AI 知识库，Claude Code 与 opencode 共用。根目录 `CLAUDE.md` 仅做引用，改动请直接改本文件。

## STRUCTURE
```
├── osr-admin/          # 启动模块 (Spring Boot main)，端口 6895
├── osr-common/         # 通用工具 (annotation, utils, exception, mybatisplus)
├── osr-framework/      # 框架配置 (security, config, websocket)
├── osr-system/         # 标准系统管理模块 (user/role/menu/dict domain)
├── osr-quartz/         # 定时任务 (job scheduler)
├── osr-openliststrm/   # ★ 核心业务，新功能几乎都写在这里 (21个子包，见下)
├── osr-web/         # Vue 3 前端 (Vite + Pinia + Vuetify 3 + PWA)
├── Dockerfile.backend    # Java 25 JRE + --enable-preview
├── Dockerfile.frontend   # Node 20 build → Nginx Alpine
├── docker-compose.yml    # MySQL 8.0 + backend + frontend
└── nginx.conf            # SPA + API proxy + WebSocket proxy
```

`osr-openliststrm` 按功能域分包（21 个）：
`api/ config/ controller/ dashboard/ enums/ helper/ monitor/ mybatisplus/ notify/ openai/ orphan/ pt/ rename/ req/ scrape/ service/ task/ tg/ tmdb/ upload/ wecom/`

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| STRM 生成 | `osr-openliststrm/src/main/java/com/osr/openliststrm/` | task/, helper/, tmdb/, rename/ |
| 文件夹同步 | `osr-openliststrm/src/main/java/com/osr/openliststrm/` | api/, upload/, service/ |
| Telegram Bot | `osr-openliststrm/src/main/java/com/osr/openliststrm/tg/` | bot commands & handlers |
| 企业微信 | `osr-openliststrm/src/main/java/com/osr/openliststrm/wecom/` | 自建应用 API、回调加解密、订阅指令交互 |
| 通知渠道 | `osr-openliststrm/src/main/java/com/osr/openliststrm/notify/` | INotifier 抽象 + TG/Webhook/企微/Bark/Gotify 五个实现；路由由 `notify_route` 表决定 |
| 刮削 | `osr-openliststrm/src/main/java/com/osr/openliststrm/scrape/` + `tmdb/` | TMDb 刮削、文件删除 |
| 定时任务 | `osr-openliststrm/src/main/java/com/osr/openliststrm/task/` + `osr-quartz/` | 自定义 task + job |
| 重命名一致性检查 | `osr-openliststrm/src/main/java/com/osr/openliststrm/orphan/` | 双向孤儿扫描、清理、忽略 |
| 重命名产物清理 | `osr-openliststrm/src/main/java/com/osr/openliststrm/rename/cleanup/` | 删主文件+刮削+回收空目录，重命名换位时清旧位置 |
| PT 订阅管理 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/` | downloader/indexer/subscription/media server |
| PT 自动删种 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/clean/` | 按体积区间+做种时长分级删种，辅种整组同删 |
| 追剧日历 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/calendar/` | 播出日期同步 + 按日期区间查排播 |
| 安全/认证 | `osr-framework/src/main/java/com/osr/framework/security/` | Spring Security + JWT（无 Shiro，早期文档写的 shiro/ 目录并不存在） |
| 登录防爆破 | `osr-framework/src/main/java/com/osr/framework/security/LoginAttemptService.java` | 账号桶 + IP 桶双计数，超阈值临时锁定 |
| 健康检查 | `osr-admin/src/main/java/com/osr/web/controller/api/HealthApiController.java` | `/api/health`，匿名、只探数据库，供 Docker healthcheck 用 |
| STRM 任务级覆盖 | `osr-openliststrm/src/main/java/com/osr/openliststrm/service/StrmSettingsFactory.java` | 全局配置 + `openlist_strm_task.strm_override` JSON 合并 |
| 第三方回调 | `osr-openliststrm/src/main/java/com/osr/openliststrm/controller/` | 开放 API 端点 |
| 前端页面 | `osr-web/src/views/` + `views-mobile/` | PC + 移动端 |
| 前端 API 层 | `osr-web/src/api/` | axios 封装 + 模块 API |
| 前端路由 | `osr-web/src/router/index.ts` | 动态路由 |
| 前端状态 | `osr-web/src/stores/` | Pinia (app, user, permission) |
| DB 脚本 | `osr-common/src/main/resources/sql/` | 初始化 + 升级脚本 |
| MyBatis Mapper | `osr-system/src/main/resources/mapper/system/` + `osr-openliststrm/src/main/resources/mapper/mybatisplus/` | XML 映射 |

前端另有独立知识库 `osr-web/src/AGENTS.md`，改前端前先读。

## CONVENTIONS
- **包命名**: `com.osr.{module}.{layer}` — controller/service/mapper/domain 分层
- **OpenList-strm 模块**: 按功能域分包 (tg/, tmdb/, rename/, helper/, monitor/, orphan/, pt/, mybatisplus/)
- **MyBatis-Plus**: `osr-openliststrm` 使用 MP 风格 (BaseMapper + IService)，`osr-system` 使用传统 XML Mapper
- **Spring Security + JWT**: 无状态认证（`SessionCreationPolicy.STATELESS`），`JwtAuthenticationFilter` 解析 token，放行路径靠 `@Anonymous` 注解被 `PermitAllUrlProperties` 扫出来
- **Java 25 Preview**: 编译/测试/运行均带 `--enable-preview` (虚拟线程/结构化并发)
- **FastJSON2**: 统一使用 FastJSON2 做 JSON 序列化
- **密码加密**: 使用 Cipher 加密存储敏感配置 (DB_PASSWORD 等)；密钥与连接信息走 `.env` (见 `.env.example`)，不要硬编码或提交进仓库
- **前端**: unplugin-auto-import + unplugin-vue-components 自动导入，`@` 指向 `src/`
- **`*Plus` 实体上的辅助方法绝不能叫 `getXxx()`/`isXxx()`**：Lombok 已经给字段生成了 `getXxx()`，再加一个返回 boolean 的 `isXxx()`，MyBatis 会认为属性 `xxx` 有两个类型不一致的 getter。**它不会在启动时报错**——`Reflector` 构造时只把该属性登记成 `AmbiguousMethodInvoker`，等到第一次真正取值（也就是第一条 INSERT/UPDATE）才抛 `Illegal overloaded getter method with ambiguous type`，编译、Spring 装配、单测 new 实体全都照过。踩过一次：`PtCleanRulePlus` 的 `String enabled` 字段配上手写的 `isEnabled()`，功能测试全绿、容器正常启动，用户点「新增规则」时才炸。命名参考 `PtIndexerPlus#hitAndRunEnabled()`、`PtCleanRulePlus#enabledOn()`、`PtDownloaderPlus#autoDeleteOn()`。`osr-openliststrm/src/test/java/com/osr/openliststrm/mybatisplus/domain/PlusEntityReflectorTest.java` 守着这条：它**逐个调用** getter 而不是只 `new Reflector(clazz)`——后者一条都拦不住
- **`*Plus` 实体 mock 打桩注意**: `mybatisplus/domain/` 下的 `*Plus` 实体只有 `@Getter @Setter`，没有自己的 `equals()`/`hashCode()`，继承的是 `BaseEntity`（`@Data`）只比较 `createTime`/`updateTime`/`params` 的浅层 equals——不同 id 的两个未落库实例会被判定为"相等"。同一测试方法里对同一 mock 方法用两个不同的 `*Plus` 实例做参数匹配时，必须用 `ArgumentMatchers.same()`/`eq()` 显式按引用区分，不要依赖默认 equals，否则会在 `when()` 调用处炸出令人迷惑的异常（参考 `osr-openliststrm/src/test/java/com/osr/openliststrm/pt/subscription/SearchSupplementServiceTest.java:95-98`）
- **一个 bean 类有多个构造器时，必须给 Spring 该用的那个标 `@Autowired`**：没有任何构造器被标注时 Spring 不会挑，而是退回去找默认构造器，找不到就 `No default constructor found`，整个应用在装配阶段启动失败。最容易踩的场景是「为了测试注入时钟/假依赖，加了一个包级可见的第二构造器」——单元测试直接 `new`，绕开 Spring，测试全绿，只有真起容器才炸。踩过一次：`LoginAttemptService` 加了注时钟的测试构造器后后端崩溃重启 6 次。
- **所有异步/多线程边界必须用 `Threads.wrap()` / `Threads.wrapSupplier()` / `Threads.wrapCallable()` 包装**：traceId（MDC）不会自动跨线程传播。`CompletableFuture.runAsync/supplyAsync`、`scheduler.schedule/scheduleAtFixedRate`、`scheduler.submit`、`ExecutorService.submit` 等任何在新线程执行 Runnable/Supplier/Callable 的地方，都必须在调用处用 `Threads.wrap(…)` / `Threads.wrapSupplier(…)` 包装，否则日志链路断掉，排查困难。
  - `Threads.wrap(Runnable)` → 返回 Runnable（MDC 上下文 + 子 traceId），用于 executor.submit / scheduler.schedule / CompletableFuture.runAsync
  - `Threads.wrapSupplier(Supplier)` → 返回 Supplier（同上），用于 CompletableFuture.supplyAsync
  - `Threads.wrapCallable(Callable)` → 返回 Callable（同上），用于 executor.submit(Callable)
  - 方法定义在 `osr-common/src/main/java/com/osr/common/utils/Threads.java`，被所有模块共用
  - 常见遗漏点：PT 模块的 `SubscriptionEngine`、`SearchSupplementService`、`RssPollService` 的 CompletableFuture；`RssPollTask`/`AutoSearchTask`/`DownloadTrackTask`/`LibrarySyncTask` 的 scheduler.scheduleAtFixedRate；`SubscriptionSearchOnCreateTrigger`/`DownloadCompletionSyncTrigger` 的 scheduler.schedule；`AsynHelper` 的全部 scheduler.schedule + CompletableFuture。新增任何异步代码时复制这些位置的做法。

## ANTI-PATTERNS
- 不要在 `osr-system` 中新增业务模块 (那是标准系统管理模块)
- 业务逻辑全部放在 `osr-openliststrm` 中
- 不要在 Controller 中写业务逻辑，Service 层处理
- MyBatis-Plus 模块使用 `@TableName` + `BaseMapper`，不要混用 XML Mapper
- Java 25 preview 特性仅用于业务代码，框架配置不依赖 preview API

## COMMANDS
```bash
# 后端构建
mvn clean package -DskipTests

# 前端开发 (端口 3000，/api 已代理到 localhost:6895)
cd osr-web && npm run dev

# 前端构建 (含 vue-tsc 类型检查)
cd osr-web && npm run build

# 前端 lint (自动修复)
cd osr-web && npm run lint

# 前端 E2E 测试 (Playwright)
cd osr-web && npm run test:e2e

# Docker 部署（全部：前端+后端+DB）
docker compose up -d --build

# 只部署前端（改了 osr-web/ 时用）：--no-deps 跳过依赖服务，避免连带重启后端
docker compose up -d --build --no-deps frontend

# 只部署后端（改了 Java 代码时用）
docker compose up -d --build --no-deps backend
```

## NOTES
- 打包镜像前需先 `mvn package` 生成 osr-admin.jar
- **`Dockerfile.frontend` 不是多阶段构建，只是 `COPY osr-web/dist` 到 Nginx 镜像里**——它不会自己跑 `npm run build`，改了 `osr-web/` 代码后必须先手动 `cd osr-web && npm run build` 生成最新 `dist`，再 `docker compose up -d --build --no-deps frontend`，否则 `COPY osr-web/dist` 这层会命中 Docker 缓存，容器里跑的还是旧代码（构建日志里这行显示 `CACHED` 就是没生效的信号）
- 容器内 `/data` 目录挂载宿主机，存放 upload/logs/strm 文件
- MySQL 默认数据库名 `osr`，连接信息通过 `.env` 注入
- 数据库初始化由 `com.osr.common.mybatisplus.MysqlDdl` 自动执行（osr-common/src/main/resources/sql/）。**注意：`MysqlDdl.getSqlFiles()` 是硬编码的文件名清单，不是目录扫描**——新增 SQL 迁移脚本后必须手动把文件名追加到该方法返回的列表末尾，否则脚本只是静静躺在目录里，永远不会被执行
- 后端端口 6895，前端 Nginx 端口 80，前端 dev server 端口 3000
- API 路径统一 `/api/` 前缀，生产由 Nginx、开发由 Vite proxy 转发到后端
- WebSocket 路径 `/websocket/`，超时 86400s (长连接)
- **新增 `@Component`/`@Service` bean 或调度器后必须做启动验证**（`docker compose up -d --build --no-deps backend` 后确认容器 `restarts=0` 且接口能响应）：单元测试常用构造器直接 new 目标类，能绕过 Spring 装配，因此「测试全绿」不代表「能启动」。构造器注入了非 bean 的依赖（如 `MediaParser` 是手动 new 管理、非 bean）会导致 `APPLICATION FAILED TO START`，只有真实启动才暴露。应用崩在 bean 装配时 `MysqlDdl` 迁移也不会执行。
- **后端容器有 healthcheck（`/api/health`）**，`frontend` 的 `depends_on` 用的是 `condition: service_healthy`。因此后端起不来时前端整个不会启动——这是有意的，比 Nginx 起来了却一路 502 更容易定位。首次启动要跑完 70 多个迁移脚本，`start_period` 给了 180s。改健康检查逻辑前先想清楚这层依赖。
- **新建菜单时 `sys_menu.icon` 直接写 mdi 名**（如 `mdi-bell-outline`），不要再写 `fa fa-*`。历史上 icon 存的是 Font Awesome 类名（RuoYi 遗留），而前端是 Vuetify、**根本没引入 Font Awesome**，只能靠 `useMenuIcon.ts` 里一张手写字典翻译——于是建菜单要改两处，漏了不报错也不告警，只是那个菜单没图标（侧边栏用 `v-if` 包着 `#prepend`，图标认不出时整个插槽不渲染，该项比同级少一块缩进，肉眼极易忽略）。`sql/` 里 4 个 fix-menu-icon 迁移都是这么来的，20260778 的「通知路由」又栽了一次。20260780 已把库里全部值换成 mdi 名，字典只留作旧库兜底，**不要再往里加新条目**。
- **参数设置页的分组按「配置键前缀」归类**（`SECTION_RULES`，见 `views/system/config/index.vue`），不是按键名里的关键词猜。加同前缀的配置零改动就落到正确分组；全新前缀落进「其他」，看得见但不会错放。旧实现是一串 if-else 匹配子串，41 个配置里有 15 个掉进兜底的「基础配置」——通知类和登录安全类全在里面。
- **通知的「发不发、发给谁」由 `notify_route` 表决定，渠道实现只管「怎么发」**。改造前每个渠道自己读 `openlist.notify.{channel}.types` 判断类型，渠道一多就没法统一配置，也没有收件人这一维。现在 `NotifierManager` 查路由，`NotifyRouteService` 整表缓存（通知是热路径，写入后调 `invalidate()`）。三条容易做错的语义：
  1. **路由缺失按「发送」处理**。新增通知类型或新增渠道时路由行还没补上，宁可多发也不能静默丢——丢通知的故障用户根本发现不了。
  2. **`OWNER` 档在通知无归属时回退默认接收人，不是丢弃**。系统级告警（索引器故障、复制超时）本来就没有归属人，理解成丢弃会让这类告警凭空消失。
  3. **不支持分人的渠道一律退化为广播**。TG 只有一个 chat id、Webhook 只有一个 URL，靠 `INotifier#supportsDirectDelivery()` 声明；配置页对这些渠道**不展示**收件人选项——给出一个不生效的开关比缺少这个功能更糟。
  新增渠道只要实现 `INotifier`（`channelKey()` 一旦发布就不能改，改了等于把用户已有路由配置全丢），配置页会自动多出一列。
- **集号有三套，别假定它们一致**。这是本项目最容易踩空的一处建模：
  - **OSR 本地** `pt_subscription_episode.episode`：季内相对集号 1..N，由 `episodeNumbers()` 按 `episode_count` 生成
  - **PT 种子**：也是季内相对号（实测 `One Piece S23E13`），与本地一致，所以 `SubscriptionMatcher` 那侧没问题
  - **TMDb 主数据**：长篇动画用绝对集号（航海王第 23 季 = 1156..1181）。种子标题自己印证了对应关系：`One Piece S23E13 Episode 1168`
  - **媒体库**：按刮削结果组织，可能是上面任意一种。实测用户的 Emby 把航海王 1172 集全平铺在 Season 1、按绝对号编号
  
  对齐统一走 `TmdbEpisodeAligner.align()`：先按集号精确对，一个都对不上再按位置对，且**位置兜底只在两边集数完全相等时启用**（数量对不上宁可留空，错位的对应会同时污染播出日期和入库判定）。对齐结果落到 `tmdb_episode_number` 列，日历取日期、对账取集号。
  
  入库判定 `SubscriptionService#queryLibrary` 因此有三条规则：本季有本地集号 → 本季有 TMDb 集号 → 整部剧任意季有 TMDb 集号。**第三条仅在两个集号不同时启用**，否则第 2 季第 17 集会把第 1 季第 17 集误判成已入库。全剧编号 (`listAllEpisodeNumbers`) 每次对账最多拉一次。
  
  **搜索侧同理**：PT 站上同一集常有两种命名并存——`One Piece S23E18`（季内相对号）与
  `One Piece S01E1173`（绝对号、季号写死 1）。匹配器原本一见季号不等就淘汰，后者整批搜不到。
  现在 `SubscriptionMatcher#matchByAbsolute` 与 `SearchSupplementService#absoluteEpisodeOf`
  共用同一套判据兜底（约束：订阅确实用绝对编号 + 种子季号缺失或为 1 + 该绝对号属于本季 +
  季包不参与，否则会去拉一千多集）。检索侧还要对这类订阅补一次**不带季号**的外部 ID 搜索，
  否则 `season=23` 会在索引器那一层就把 S01 的资源过滤掉，匹配再宽松也无米下锅。
  
  **已知缺口**：字幕组的 `[Sakurato] One Piece - 1173 [2160p]` 这类裸数字命名仍匹配不上，
  但卡点不在集号——`YearSeasonEpisodeExtractor` 会把标题解析成
  `[ Sakurato ] One Piece - 1173 [ ] [ - ]`，在标题匹配那步就淘汰了。要支持得先修标题截断，
  `AbsoluteEpisodeMatchTest` 里有一条用例把这个现状钉住了。
  
  这个 bug 的症状很隐蔽：下载记录明明 COMPLETED，集状态却永远 MISSING，订阅进度一直卡着。**TMDb 的「剧集组」(episode_groups) 暂时用不上**——航海王那 18 个组没有一个对得上种子的 S23 编号，而主数据里已经有需要的绝对号了。等真碰到「发布组用的编号主数据表达不了」的剧（比如按 Netflix 分季）再考虑加 `pt_subscription.episode_group_id` 让用户手选，那种情况 TMDb 自己也推断不出来。
- **追剧日历的数据来自 `pt_subscription_episode.air_date`，由 `EpisodeAirDateSyncTask` 每 12 小时同步**（首轮兼做存量回填，所以升级上来的库不需要单独迁移动作）。播出日期本来就会变——改档、提前放送、季中休播——一次性回填脚本解决不了，定期同步是必需的而不是图省事。查询侧 `PtCalendarService` 是纯 SQL 范围查询，不打 TMDb。两条容易踩的：TMDb 撤掉日期时**不清空**已有值（撤档信息本身不可靠，清掉会让这集从日历上凭空消失）；同步只写日期确实变了的行，否则每 12 小时把整表 `update_time` 刷一遍，看起来像天天都有变化。
- **STRM 生成的「输出根目录 / 是否下字幕 / 最小体积」可按任务覆盖**，存在 `openlist_strm_task.strm_override`（JSON），合并规则见 `StrmSettingsFactory`——照 `pt_subscription.filter_override` 的约定，只有出现在 JSON 里的键才覆盖，该列为空时行为与引入前完全一致。两条不要改坏的约定：
  1. **`strmDir`/`strmOneFile` 按路径反查任务，不要改成让调用方传任务**。半数调用方手里根本没有任务对象（`AsynHelper` 的复制完成触发、`CopyRecoveryTask` 的兜底恢复、TG 的 `/strm <路径>`），它们退回全局配置就会让同一目录因「谁触发的」而输出到不同根目录，长出两棵 STRM 树，一致性检查还会把其中一棵报成孤儿。匹配走 `pickCoveringTask`：落在路径分隔符上、取最长（最具体）的任务、停用的任务照样参与。
  2. **URL 编码开关与视频/字幕扩展名刻意不可覆盖**。encode 有三个解码侧消费者（`RenameOrphanScanServiceImpl`、`RenameCleanupService`、`StrmSourcePathResolver` 都要从 .strm 内容反解回网盘路径），扩展名是 `sys_dict` 全站字典；两者都是「播放器吃什么」的全局属性，分库配置只会制造解不开的历史数据。
- **`tmdb_cache` 靠 `TmdbCachePurgeTask` 定期清理**（启动后 5 分钟首跑，之后每 6 小时）。这张表只在「同 key 再次被请求」时才会 upsert 覆盖，刮完一次就不再访问的 key 不会自己消失；没有这个任务时表单调增长（实测一个中等规模的库里 276 行有 237 行是过期死行）。删除走 `TmdbCacheMapper.deleteExpired` 分批进行，不要改回一条 DELETE 删干净。
- 后端 Java 异常写在 `/data/logs/sys-error.log`，**不在 docker stdout**（stdout 只有启动 banner）。排查启动失败：`docker cp osr-backend:/data/logs ./tmp` 后看 `sys-error.log`；容器反复重启时先 `docker update --restart=no osr-backend && docker restart osr-backend` 让它崩溃后停住再读日志。
