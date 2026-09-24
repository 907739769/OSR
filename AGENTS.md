# OSR (OpenList STRM Relay) 项目知识库

## OVERVIEW
影视 STRM 管理系统。Java 25 (Spring Boot 4.0.6) + Vue 3 + Vuetify 3，Docker 双容器部署。核心功能：STRM 文件生成、文件夹同步、Telegram Bot 控制、TMDb 刮削/重命名、第三方回调自动化。

> 本文件是本项目唯一的 AI 知识库，Claude Code 与 opencode 共用。根目录 `CLAUDE.md` 仅做引用，改动请直接改本文件。

## STRUCTURE
```
├── osr-admin/          # 启动模块 (Spring Boot main)，端口 6895
├── osr-common/         # 通用工具 (annotation, utils, exception, mybatisplus)
├── osr-framework/      # 框架配置 (security, config, websocket)
├── osr-system/         # 标准系统管理模块 (user/role/menu/config domain)
├── osr-quartz/         # 定时任务 (job scheduler)
├── osr-openliststrm/   # ★ 核心业务，新功能几乎都写在这里 (22个子包，见下)
├── osr-web/         # Vue 3 前端 (Vite + Pinia + Vuetify 3 + PWA)
├── Dockerfile.backend    # Java 25 JRE + --enable-preview
├── Dockerfile.frontend   # Node 20 build → Nginx Alpine
├── docker-compose.yml    # MySQL 8.0 + backend + frontend
└── nginx.conf            # SPA + API proxy + WebSocket proxy
```

`osr-openliststrm` 按功能域分包（22 个）：
`api/ chat/ config/ controller/ dashboard/ enums/ helper/ mcp/ monitor/ mybatisplus/ notify/ openai/ orphan/ pt/ rename/ req/ scrape/ service/ task/ tg/ tmdb/ upload/ wecom/`

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| STRM 生成 | `osr-openliststrm/src/main/java/com/osr/openliststrm/` | task/, helper/, tmdb/, rename/ |
| 文件夹同步 | `osr-openliststrm/src/main/java/com/osr/openliststrm/` | api/, upload/, service/ |
| Telegram Bot | `osr-openliststrm/src/main/java/com/osr/openliststrm/tg/` | bot commands & handlers；PT 订阅指令逻辑在 `chat/`，与企微共用 |
| 企业微信 | `osr-openliststrm/src/main/java/com/osr/openliststrm/wecom/` | 自建应用 API、回调加解密、订阅指令交互 |
| 通知渠道 | `osr-openliststrm/src/main/java/com/osr/openliststrm/notify/` | INotifier 抽象 + TG/Webhook/企微/Bark/Gotify 五个实现；路由由 `notify_route` 表决定 |
| 刮削 | `osr-openliststrm/src/main/java/com/osr/openliststrm/scrape/` + `tmdb/` | TMDb 刮削、文件删除 |
| 定时任务 | `osr-openliststrm/src/main/java/com/osr/openliststrm/task/` + `osr-quartz/` | 自定义 task + job；调度/手动执行/执行记录见 `osr-quartz/AGENTS.md` |
| 重命名一致性检查 | `osr-openliststrm/src/main/java/com/osr/openliststrm/orphan/` | 双向孤儿扫描、清理、忽略 |
| 重命名产物清理 | `osr-openliststrm/src/main/java/com/osr/openliststrm/rename/cleanup/` | 删主文件+刮削+回收空目录，重命名换位时清旧位置 |
| PT 订阅管理 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/` | downloader/indexer/subscription/media server |
| PT 自动删种 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/clean/` | 按体积区间+做种时长分级删种，辅种整组同删 |
| PT 转移做种 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/transfer/` | 把 qB 上做够时长的种子搬到 TR 继续做种（IYUU「转移」的自建实现，不含辅种） |
| PT 热门自动订阅 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/autoadd/` | TMDb 榜单 + RSSHub 豆瓣榜单，按规则过滤后自动建订阅 |
| PT 统计仪表盘 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/stats/` + `osr-web/src/composables/usePtStats.ts` | 聚合查询（含归属隔离）+ 两端共用的图表选项 |
| 追剧日历 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/calendar/` | 播出日期同步 + 按日期区间查排播 |
| 缺集体检 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/health/` | 逾期未入库的分档诊断 + 每日聚合提醒 |
| MCP 服务端 | `osr-openliststrm/src/main/java/com/osr/openliststrm/mcp/` | 端点 `/mcp`，令牌鉴权 + 33 个工具，供本地 AI 助理连接 |
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

前端与各业务域另有**按需加载**的分片知识库：下表每一份都由同目录的 `CLAUDE.md` 自动引入，
**改到那个目录，那份才进上下文**。本文件只留全局约定与跨模块纲领，域内的踩坑记录一律写进对应分片。

| 分片 | 覆盖 |
|------|------|
| `osr-web/src/AGENTS.md` | 前端全部：组件与设计系统、路由、菜单与图标、参数设置页、列表排序、PT 统计取数 |
| `osr-openliststrm/AGENTS.md` | 核心业务模块**总则**（只剩分包与数据层约定，域内细节全在下面的子包里） |
| `…/openliststrm/pt/AGENTS.md` | PT 总则：对账方向、日志里怎么称呼订阅 |
| `…/pt/subscription/` | 订阅匹配与补搜：集号三套、description 解析、外部 ID、同名剧年份、季包、手动推送、搜索日志 |
| `…/pt/task/` | 周期任务：定期补搜的节奏、对账并发、下载追踪与删种边界、正常路径上的高频日志 |
| `…/pt/indexer/` | 索引器接入：能力探测、限流记账、RSS 覆盖度游标、guid 日志、H&R 是站点属性 |
| `…/pt/downloader/` | 下载器接入：qB/TR 差异、角色划分、`listAll` 与 `listByTag` 的分工 |
| `…/pt/filter/` | 过滤规则：两套关键词、`FilterCriteria`、种子画像、`files` 属性 |
| `…/pt/clean/` | 自动删种：以辅种组为单位、体积区间开闭 |
| `…/pt/upgrade/` | 洗版：维度、cutoff、状态流转与失败退回 |
| `…/pt/transfer/` | 转移做种 |
| `…/pt/media/` | 媒体服务器接入：多台查询语义、被动连通状态、对账源不可用时的降级 |
| `…/pt/health/` | 缺集体检 |
| `…/pt/autoadd/` | 热门自动订阅 + RSSHub 地址 |
| `…/pt/stats/` | 统计仪表盘（后端口径与归属隔离） |
| `…/pt/calendar/` | 追剧日历 |
| `…/openliststrm/tmdb/` | TMDb 匹配与刮削：打分与两道检验、英文规范名、NFO 标题、中文别名、缓存清理 |
| `…/openliststrm/rename/` | 重命名解析管线、标题归一化、括号/年份/发布组抽取 |
| `…/openliststrm/rename/cleanup/` | 产物清理：删产物与删记录的边界、清理顺序、空目录回收 |
| `…/openliststrm/orphan/` | 双向孤儿扫描：去重键、mtime 基线、截断 |
| `…/openliststrm/scrape/` | 刮削预览与执行必须同一份判定 |
| `…/openliststrm/service/` | 同步与 STRM 生成：按记录重试、`fail_reason` 写法、任务级覆盖 |
| `…/openliststrm/helper/` | 复制监控与重启兜底、媒体扩展名来源 |
| `…/openliststrm/notify/` | 通知路由与文案 |
| `…/openliststrm/mcp/` | MCP 服务端 |
| `…/openliststrm/monitor/` | 目录监听与在途产物 |
| `…/openliststrm/controller/` | REST 端点：继承来的增删改开放范围、统计接口、批量删除 |
| `…/openliststrm/mybatisplus/` | 数据层 Wrapper 的陷阱 |
| `…/openliststrm/wecom/` | 企业微信回调鉴权 |
| `…/openliststrm/chat/` | 企微与 TG 共用的订阅聊天指令：按钮会话 id、补搜异步、TG 身份与回调校验 |
| `osr-quartz/AGENTS.md` | 定时任务：两条执行路径、手动执行异步化与并发闸门、执行记录的时间列 |
| `osr-framework/AGENTS.md` | 接口授权、JWT 失效、Filter 注册、访问日志、全局异常、Actuator |
| `osr-common/AGENTS.md` | SQL 迁移注册、分页上下文、日志节流三件套、traceId 封顶 |
| `osr-admin/AGENTS.md` | logback 配置、MyBatis SQL 日志开关、实时日志页 |
| `.github/AGENTS.md` | CI 与发版流程 |

**新增知识点写进对应分片，不要往本文件里加。** 只有真正跨模块、每次改代码都可能踩到的才留在根。

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
- **`HandlerInterceptor` / `Filter` / `@Component` 是单例，绝不能用实例字段存单次请求的状态**。已删除的 `ApiInterceptor` 曾用 `private long startTime` 存请求开始时间，后到的请求在 `preHandle` 里把它覆盖掉，先到那个算出来的耗时变成「现在 − 最后一个请求的起点」。实测一次真实 56.7 秒的搜索被记成 15.2 秒。这个 bug 的隐蔽之处有三层：单请求下完全正确，只有并发才偏；偏的方向是**偏小**，慢接口反而显示得很快，正好瞒过了要靠这条日志找的那类问题；而且它不报错、不抛异常，只是数字不对。请求级状态一律挂 request attribute，或者像 `RequestLogFilter` 那样用**方法内局部变量**（它因此一直是对的）。
  配套的一条：**拦截器的收尾日志要放 `afterCompletion` 而不是 `postHandle`**，后者在 handler 抛异常时根本不会被调用，出错的请求一条耗时都不留，而那正是最需要知道它跑了多久的时候。这两条现在由 `RequestLogFilterTest` 接手守着（filter 的 `finally` 天然覆盖异常路径，并发那条会在有人把局部变量挪回字段时立刻失败）——**「filter 天然不会犯这两个错」只在没人把它改回去的前提下成立**，所以断言留着。
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
- **`nginx.conf` 里 Service Worker 那条 location 的正则，三个分支都必须带 `\.js`**。原先写的是
  `location ~* ^/(sw|registerSW|workbox-[^/]+\.js)$`——前两个分支少了扩展名，于是 `/sw.js` 与
  `/registerSW.js` **根本不匹配这个块**，掉进下面的静态资源块被打上 `Cache-Control: no-store`，
  也拿不到 `Service-Worker-Allowed`。`no-store` 意味着浏览器不保留旧脚本副本，而 PWA 判断
  「要不要弹更新提示」靠的正是新旧 SW 脚本逐字节比对——这条断了，`AppUpdatePrompt` 那套
  （`composables/useAppUpdate.ts`）就永远不会触发，用户只能靠手动清浏览器缓存才拿得到新版前端。
  **这个 bug 极其隐蔽**：`/sw.js` 照样返回 200 和正确的 MIME，`curl` 看不出任何异常，
  在页面里 `fetch('/sw.js')` 也能拿到内容。**判断有没有命中这个块，看响应头里有没有
  `Service-Worker-Allowed` 最直接**：`curl -sI http://localhost/sw.js | grep -i service-worker-allowed`，
  没有就是没命中。顺带：`/registerSW.js` 返回 404 是正常的——本项目用 `useRegisterSW`
  （`virtual:pwa-register/vue`），注册代码打包进了应用，vite-plugin-pwa 不会单独产出这个文件，
  那条规则只是防御性的。
- 容器内 `/data` 目录挂载宿主机，存放 upload/logs/strm 文件
- MySQL 默认数据库名 `osr`，连接信息通过 `.env` 注入
- 后端端口 6895，前端 Nginx 端口 80，前端 dev server 端口 3000
- API 路径统一 `/api/` 前缀，生产由 Nginx、开发由 Vite proxy 转发到后端
- WebSocket 路径 `/websocket/`，超时 86400s (长连接)
- **新增 `@Component`/`@Service` bean 或调度器后必须做启动验证**（`docker compose up -d --build --no-deps backend` 后确认容器 `restarts=0` 且接口能响应）：单元测试常用构造器直接 new 目标类，能绕过 Spring 装配，因此「测试全绿」不代表「能启动」。构造器注入了非 bean 的依赖（如 `MediaParser` 是手动 new 管理、非 bean）会导致 `APPLICATION FAILED TO START`，只有真实启动才暴露。应用崩在 bean 装配时 `MysqlDdl` 迁移也不会执行。
- **后端容器有 healthcheck（`/api/health`）**，`frontend` 的 `depends_on` 用的是 `condition: service_healthy`。因此后端起不来时前端整个不会启动——这是有意的，比 Nginx 起来了却一路 502 更容易定位。首次启动要跑完 70 多个迁移脚本，`start_period` 给了 180s。改健康检查逻辑前先想清楚这层依赖。
- **日志有五种失控形态，三个节流工具都在 `osr-common/src/main/java/com/osr/common/utils/`，案例散在各分片里**。写任何一行日志前对照这五条：
  1. **出错刷屏**——周期任务里的 `catch { log.warn }` 必须过 `FaultThrottle`。持续性故障会按轮询间隔无限重复同一条（下载器离线一天 = 2880 条逐字相同的 WARN），而有信息量的只有**故障开始**与**故障恢复**两个时刻。
  2. **没出错也得留痕**——周期任务正常跑完一轮要有记录（`RoundHeartbeat`），否则「一切正常」与「调度器已经死了」在日志上完全一样。判据必须是**本轮真正发生的业务变化**，不是扫描到的条数——后者恒大于 0，用它判等于每轮都打。
  3. **同一批对象反复处理**——正常路径上每轮重新判一次、每轮得到同一答案的日志上 `LogOnce`；**键取「打印出来的那个东西」**而不是对象主键，否则会写出两行逐字相同、读的人根本分不出来的日志。
  4. **单行体积**——不要把整个响应体或整个 `@Data` 对象扔给 `{}`（实测单行 58 KB）。`@ToString.Exclude` 掉持有响应体的字段，配一个摘要方法，**摘要方法不能叫 `getXxx()`**（会被当成 bean 属性）。
  5. **行首那一坨 traceId**——`createChildTraceId` 已按 `MAX_TRACE_SEGMENTS` 封顶（曾长到 1.3 KB、占全文件 31%）。只有**递归重包**才累积，`scheduleAtFixedRate` 那批不受影响。
  另两条无条件成立：**不许把密钥打进日志**（回源日志只打 `encodedPath`，`SysUser#toString()` 不输出 password/salt——日志文件保留 7 天）；**提到订阅/剧集用 `pt/PtLogText`**，只打 id 没有任何人能认出那是哪部剧。判断一条重复日志该不该去重，**先按 traceId 分组看它来自哪条路径**，别只看总数。
- 后端 Java 异常写在 `/data/logs/sys-error.log`，**不在 docker stdout**（stdout 只有启动 banner）。排查启动失败：`docker cp osr-backend:/data/logs ./tmp` 后看 `sys-error.log`；容器反复重启时先 `docker update --restart=no osr-backend && docker restart osr-backend` 让它崩溃后停住再读日志。
- **日志文案一律中文**，与项目其余部分一致。曾有 105/586（18%）是英文，多数是 `XxxTask started` 这类技术标记（保留），但 `processOnce: sourceDir does not exist or not a directory` 这种是给用户看的故障信息，混在中文日志里读起来格外突兀。**周期任务的 `started` 那行要带上间隔**（`interval=30s` / `心跳间隔=30min`）：14 个任务里原先只有 3 个带，而「这个任务多久跑一次」是排查时第一个要问的问题，去翻代码里的 `Duration.ofMinutes(...)` 纯属浪费时间。心跳间隔与实际业务周期不是一回事时要写清楚（`AutoSearchTask` 的心跳是 30 分钟，但各订阅的实际补搜周期由 `pt_filter_config.auto_search_interval_hours` 决定）。
- **异常日志一律「一条，带上下文，带异常对象」**。三种写坏的方式都在仓库里出现过，且都不报错：`log.error("", e)` （只有堆栈没有上下文，翻到时不知道当时在做什么）；上下文与堆栈拆成两条打（`OpenlistApi` 曾是两条 ERROR、`MediaParser` 更是上下文在 INFO 而堆栈在 ERROR，只看 sys-error.log 不知道是哪个文件，只看 INFO 又没有原因）；以及 `e.printStackTrace()`（写的是 **stdout**，而本项目 stdout 只有启动 banner，异常既不进 sys-error.log 也没人看得到，发生了等于没发生）。**末参传 `ex` 的同时占位符仍要带 `ex.getMessage()`**——SLF4J 在参数比占位符多一个且末参是 Throwable 时会把它当异常处理，两者都要：只有 message 就没有堆栈，只有堆栈就没法按错误文本 grep（这一点在 `ApiInterceptor` 上踩过：第一版改成只传 `ex`，它的测试立刻红了——那个断言是对的，该改的是代码。该类现已合并进 `RequestLogFilter`，但结论不变）。
- **`docker-compose.yml` 的 `mem_limit` 与镜像里的 `JAVA_OPTS=-XX:MaxRAMPercentage=70` 是一对，改一个要想到另一个**。没有容器内存上限时 JVM 看到的是宿主机全部内存，那个百分比会算到一个比默认（25%）更离谱的堆上——实测一台 31G 的宿主机上，无上限时堆被 ergonomic 定到 8.4G 而进程实际只用 174MB。`BACKEND_MEM_LIMIT` 可在 `.env` 覆盖，调它就等于调堆（堆 = 该值 × 70%）。另外 `ENTRYPOINT` 用的是 `sh -c "exec java ..."` 而不是直接 shell 形式：**`exec` 让 java 接管 PID 1**，从而收得到 `docker stop` 的 SIGTERM；收不到的话 Spring 的关闭钩子不跑，logback `AsyncAppender` 队列里没写完的日志会跟着丢——而那恰恰是关停前最后一段、排查问题时最想看的日志。**容器仍以 root 运行、`/data` 仍是 777**，这是权衡后保留的：宿主机 `/data` 里的既有文件都是 root 建的，换成非 root uid 会让所有存量部署在升级那一刻失去写权限，而修复要用户手工去宿主机 chown。
