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
| PT 转移做种 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/transfer/` | 把 qB 上做够时长的种子搬到 TR 继续做种（IYUU「转移」的自建实现，不含辅种） |
| 追剧日历 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/calendar/` | 播出日期同步 + 按日期区间查排播 |
| 缺集体检 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/health/` | 逾期未入库的分档诊断 + 每日聚合提醒 |
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
- **`HandlerInterceptor` / `Filter` / `@Component` 是单例，绝不能用实例字段存单次请求的状态**。`ApiInterceptor` 用 `private long startTime` 存请求开始时间，后到的请求在 `preHandle` 里把它覆盖掉，先到那个算出来的耗时变成「现在 − 最后一个请求的起点」。实测一次真实 56.7 秒的搜索被记成 15.2 秒。这个 bug 的隐蔽之处有三层：单请求下完全正确，只有并发才偏；偏的方向是**偏小**，慢接口反而显示得很快，正好瞒过了要靠这条日志找的那类问题；而且它不报错、不抛异常，只是数字不对。请求级状态一律挂 request attribute。`RequestLogFilter` 用的是方法内局部变量，所以一直是对的——两者对照着看就明白差别在哪。
  同一个类里还有一条：**收尾日志要放 `afterCompletion` 而不是 `postHandle`**，后者在 handler 抛异常时根本不会被调用，出错的请求一条耗时都不留，而那正是最需要知道它跑了多久的时候。`ApiInterceptorTest` 守着这两条，其中并发那条会在改回实例字段时立刻失败（实测退回旧写法后 A 的耗时记成 0ms）。
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
- 数据库初始化由 `com.osr.common.mybatisplus.MysqlDdl` 自动执行（osr-common/src/main/resources/sql/）。**注意：`MysqlDdl.getSqlFiles()` 是硬编码的文件名清单，不是目录扫描**——新增 SQL 迁移脚本后必须手动把文件名追加到该方法返回的列表末尾，否则脚本只是静静躺在目录里，永远不会被执行
- 后端端口 6895，前端 Nginx 端口 80，前端 dev server 端口 3000
- API 路径统一 `/api/` 前缀，生产由 Nginx、开发由 Vite proxy 转发到后端
- WebSocket 路径 `/websocket/`，超时 86400s (长连接)
- **新增 `@Component`/`@Service` bean 或调度器后必须做启动验证**（`docker compose up -d --build --no-deps backend` 后确认容器 `restarts=0` 且接口能响应）：单元测试常用构造器直接 new 目标类，能绕过 Spring 装配，因此「测试全绿」不代表「能启动」。构造器注入了非 bean 的依赖（如 `MediaParser` 是手动 new 管理、非 bean）会导致 `APPLICATION FAILED TO START`，只有真实启动才暴露。应用崩在 bean 装配时 `MysqlDdl` 迁移也不会执行。
- **后端容器有 healthcheck（`/api/health`）**，`frontend` 的 `depends_on` 用的是 `condition: service_healthy`。因此后端起不来时前端整个不会启动——这是有意的，比 Nginx 起来了却一路 502 更容易定位。首次启动要跑完 70 多个迁移脚本，`start_period` 给了 180s。改健康检查逻辑前先想清楚这层依赖。
- **发版必须手工写中文更新日志，CI 自动生成的那份不算数**。流程是：推 tag（`vX.Y.Z`，附注标签，message 写成 `vX.Y.Z: 一句话说清这版的主线`）→ `.github/workflows/docker-publish-tag.yml` 构建并推送镜像、然后用 `generate_release_notes: true` 建出 release → **再把更新日志写进 release 正文**（`gh release edit <tag> --notes-file <file>`）。自动生成的正文只有一串 commit 标题和一个 compare 链接，回答不了用户真正要问的三件事：这版对我有什么影响、升级要不要做什么、出问题时能不能退。**漏了这一步等于没发版**——用户不读 commit，只读 release。
  - 正文结构固定为 `## ✨ 新增功能` / `## 🐛 Bug Fixes` / `## ⚙️ Improvements` / `## 📦 Breaking Changes` / `## 📝 Notes`，五节都要在，没有内容就写「无」（少一节读者会以为漏写了，写「无」是一个明确的信号）。末尾保留 CI 给的 `**Full Changelog**: .../compare/<上一版>...<本版>` 那行。
  - **写「为什么」而不只是「改了什么」**。已发布的几版都是这个调子：先说清用户看到的现象，再说清成因，最后才是做法——照抄 v3.3.7、v3.3.8 的写法即可。`📝 Notes` 里固定交代：有没有新增数据库迁移脚本（有的话写明**启动时自动执行、不需要手动跑 SQL**）、升级后第一天有没有需要预期的异常观感、以及本版新增/改动的可调配置项。
  - **版本号按 patch 递增，功能也走 patch**（v3.3.5 整个「转移做种」功能就是一次 patch 升位），不要因为是新功能就自己抬 minor。
  - **构建失败时不要手工补建 release**。`create-release` 逐个判依赖的 success/skipped，构建真失败就跳过——那是有意的，发一个装着旧镜像的版本比不发版糟得多。正确做法是 `gh run rerun <id> --failed`，先确认失败是 GitHub 侧的（429/502/503 一类）还是自己的代码问题。反过来，**只有一侧改动导致另一侧 job 被 skip 是正常的**，那种情况 release 照常建（v3.3.6 就是因为把 skip 也当成失败而漏建过一次）。
  - 顺带：只改了 `osr-web/` 时本地验证要先 `cd osr-web && npm run build` 再 `docker compose up -d --build --no-deps frontend`，否则 `COPY osr-web/dist` 那层会命中缓存（见下方 NOTES 里 `Dockerfile.frontend` 那条）。
- **订阅列表页的进度计数与进度弹窗必须共用 `SubscriptionService#hasFileInLibrary`**。卡片上的「12/26」由列表接口一条 `GROUP BY sub_id, state` 聚合出来（`countStatesBySubscriptions` → `fillProgressCounts`），弹窗那份是逐集算的；两处只要有一处忘了「UPGRADING 也算已入库」，同一条订阅就会在卡片上显示 12、点开弹窗显示 11——**比不显示进度更糟**，用户会开始怀疑哪个数是真的。为此 `hasFileInLibrary` 特意做了 `(String state)` 重载给聚合侧用，不要在聚合那边另写一遍状态判断。计数落在 `PtSubscriptionPlus` 的三个 `@TableField(exist = false)` 瞬态列上，查完列表再填，不落库。
  **排序刻意没有「按缺集数」**：那需要 `ORDER BY (SELECT COUNT(*) …)`，而同一个 wrapper 还要喂给分页的 count 查询（`BaseController#selectPage` 先 `selectCount(wrapper)` 再 `selectPage`），表达式排序在聚合查询里的行为得连着真实 MySQL 验一遍才敢上——排序坏掉是整页打不开，不是少个档位。要加的话先验证 count 路径，`idx_sub_state(sub_id, state)` 本身是够用的。
- **「逐集跑一遍」这类跑批必须在开始前把订阅对象快照下来，不能在循环里现读 `currentSubscription`**。订阅进度弹窗的「一键补齐全部」每集要等一次几十秒的检索（后端单索引器预算 30 秒还是软上限），几十集就是十几二十分钟；这期间弹窗点遮罩就能关，用户去点开另一条订阅的进度会把 `currentSubscription` 换掉——旧实现每轮现读它，于是剩下的集变成「拿 A 的集号、按 B 的标题、推给 B 的订阅」，**界面上没有任何迹象**（loading 挂在已经关掉的弹窗上）。同理收尾回写进度也要判 `currentSubscription?.id === 快照.id`，否则会把用户正在看的另一条订阅的弹窗内容改掉。跑批期间弹窗设 `persistent`、显示「已完成 N/M」并给一个中止入口（置个 flag，当前这一集跑完就停，不打断已发出的请求）。`composables/__tests__/usePtSubscription.spec.ts` 里三条用例钉住这些。
- **PT 菜单分四组，新增菜单挂到语义对应的那一组**（20260785）：`PT 追剧`(2070) 追剧日历/缺集体检/订阅管理/热门自动订阅、`PT 下载`(2079) 下载记录/统计仪表盘/转移做种、`PT 规则`(2080) 过滤规则/洗版规则/黑名单、`PT 接入`(2081) 索引器/下载器/媒体服务器。四个分组都直接挂在 `parent_id=0`，**不要再引入第三级**——20260752 刚把三级收敛成两级，桌面端 `SidebarMenuItem.vue` 也是把分组渲染成一行标题、子项平铺，再套一层只会多出一行没人点的标题。分组标题已带 PT，**子菜单名不要再写 `PT` 前缀**（侧边栏宽 220px）。改分组归属只 `UPDATE parent_id/order_num/menu_name`，`menu_id`/`url`/`perms` 不动，`sys_role_menu` 按 `menu_id` 关联因此不受影响；但**新建 M 分组要把旧分组的角色授权继承过去**（`INSERT IGNORE INTO sys_role_menu SELECT role_id, <新id> FROM sys_role_menu WHERE menu_id=<旧id>`）——非管理员走 `selectMenusByUserId`，父级 M 菜单没授权的话整组子菜单都不显示，而管理员走 `selectMenuNormalAll` 看不出问题。
- **图标是 lucide，`sys_menu.icon` 直接写 lucide 官方名**（kebab-case，如 `bell-ring`、`calendar-days`，全站描边风格），**中间没有翻译层**。前端由 `plugins/lucideIcons.ts` 注册一个 Vuetify 自定义 IconSet，图标按需引入；模板里写的、库里存的、lucide 官网上叫的，是同一个名字。三条不要改坏的：**（1）新图标必须在 `lucideIcons.ts` 的 `icons` 表里登记**——名字从数据库来，打包时静态分析看不见，漏登记的表现是那个菜单显示一个问号（开发模式下另有 console.warn）；**（2）Vuetify 的 63 个 `$` 别名要一并给全**（下拉箭头、勾选框、排序箭头、分页），漏掉的那个别名在对应组件上表现为「图标位置空着」，不报错不告警，而 v-select / v-checkbox / v-data-table 遍布全站；**（3）绝不要再引入 mdi→lucide 的运行时字典。** 这条路走过四次：历史上 icon 存的是 Font Awesome 类名（RuoYi 遗留），而前端是 Vuetify、**根本没引入 Font Awesome**，只能靠 `useMenuIcon.ts` 里一张手写字典翻译——于是建菜单要改两处，漏了不报错也不告警，只是那个菜单没图标（侧边栏用 `v-if` 包着 `#prepend`，图标认不出时整个插槽不渲染，该项比同级少一块缩进，肉眼极易忽略）。`sql/` 里 4 个 fix-menu-icon 迁移都是这么来的，20260778 的「通知路由」又栽了一次。20260780 换成 mdi 名拆掉了 fa 字典，20260791 换成 lucide 名——两次都是**一次性 codemod + 一条 SQL 迁移，跑完即弃**。
  **品牌图标是唯一的例外**：lucide 官方不收 logo（已剥离到 simple-icons），而 Telegram / 企业微信的图标本身就承担「这条通知走哪个渠道」的识别功能。这两个从 simple-icons 取官方路径内联在 `lucideIcons.ts` 里，名字是 `brand-telegram` / `brand-wecom`，是**全站仅有的两个实心图标**——品牌标识按惯例就是实心的，描边版本认不出来。新增通知渠道时照抄，不要为了统一风格把 logo 改成描边。
  另：`mdi-spin` 那类 MDI 字体自带的修饰类随字体一起没了，加载图标的自转改成 `motion.scss` 里针对 `.lucide-loader-circle` 的规则，**它是全站唯一不走 `--osr-dur-*` 令牌的动画**（令牌在 reduced-motion 下被压到 0.01ms，套上去就是每秒转十万圈）。
- **参数设置页的分组按「配置键前缀」归类**（`SECTION_RULES`，见 `views/system/config/index.vue`），不是按键名里的关键词猜。加同前缀的配置零改动就落到正确分组；全新前缀落进「其他」，看得见但不会错放。旧实现是一串 if-else 匹配子串，41 个配置里有 15 个掉进兜底的「基础配置」——通知类和登录安全类全在里面。
- **字典管理已整套下线（20260792），视频/字幕扩展名迁进参数设置，`sys_dict_data`/`sys_dict_type` 两张表已 DROP**。删的理由不只是「像后台管理系统」：经 20260510/20260511 两次清理后字典只剩 2 个类型 13 条数据，唯一消费者是 `OpenListHelper#isVideo/isSrt`，而那个页面**改了不生效**——`SysDictDataHelper` 的缓存靠 `refreshCache(String)` 失效，那个方法全项目零调用方，用户加一个扩展名保存成功、列表刷新，业务侧仍用旧集合，非重启后端不生效且没有任何错误现象（两个 `SysDict*ApiController` 顺带也没有 `adminOnlyWrite`，任何登录用户都能改全站扩展名）。三条不要改坏的：
  1. **`MediaExtensionProvider` 按配置原文缓存，不是按配置键名**。这是新实现不会重演上述 bug 的**结构性**保证：配置一变 key 就不同、自动重算，不存在一个「需要有人记得调用」的失效方法。取原文很便宜——`selectConfigByKey` 那层已有 `CacheUtils` 缓存且 `updateConfig` 会刷新它，这里缓存的是 split + toLowerCase 的结果，省的是目录遍历时每个文件都要做的那一次。
  2. **配置值为空必须退回内置兜底，绝不能退化成空集**（`OpenlistConfig` 的两个 `DEFAULT_*_EXTENSIONS`）。空集的语义是「没有任何文件是视频」，同步与 STRM 生成会安静地一个文件都不处理，而那是本项目的主链路——日志里看不出任何异常，用户只会看到「什么都没生成」。
  3. **`schema.sql` 的建表与 `init.sql` 的 13 条种子数据刻意保留**（与 `sys_notice` 被 20260510 DROP 但 schema.sql 仍建表同一处理）。迁移脚本要从字典表 `GROUP_CONCAT` 出配置值，全新安装靠 init.sql 那份、存量升级靠用户自己维护的那份，两条路径共用同一份默认值定义、不会漂移。把 init.sql 的 INSERT 删掉的话，全新安装会在这一步读到空表、拼出两条空配置，正好触发上一条。
- **PC 列表页的表头排序全部接在 `useDataTable#onSortChange` 上**，落成 `orderByColumn`/`isAsc` 两个参数交给 `BaseController#selectPage`。`v-data-table-server` **只发事件、不自己排数据**（它手里本来就只有当前一页），不接这个事件的表现是「点表头、箭头翻转、一行不动」——10 个 PC 列表页此前全是这个状态，只有定时任务页因为用的是客户端的 `v-data-table` 才碰巧能排。三条不要改坏的：**表头 key 不是数据库列的必须标 `sortable: false`**（`detail`/`config`/`fileInfo` 这类把几个字段拼成一格的合成列，传过去就是个不存在的列名，整页 500，而用户只是点了一下表头）；**各 Controller `buildQueryWrapper` 里的默认排序要留着**——MyBatis-Plus 的分页拦截器把 `Page` 上的排序放在 SQL 自带 ORDER BY 之**前**（`PageOrderPrecedenceTest` 钉住了这个第三方行为，反过来的话所有排序都会被 create_time 静默吃掉），默认排序因此降为次级键，同值行的先后仍然稳定，去掉它翻页会出现重复行与漏行；**`resetQueryParams` 不清排序参数**，理由与 pageSize 相同，且箭头是表格自己的状态、清了参数就会和实际顺序对不上。
- **通知的「发不发、发给谁」由 `notify_route` 表决定，渠道实现只管「怎么发」**。改造前每个渠道自己读 `openlist.notify.{channel}.types` 判断类型，渠道一多就没法统一配置，也没有收件人这一维。现在 `NotifierManager` 查路由，`NotifyRouteService` 整表缓存（通知是热路径，写入后调 `invalidate()`）。三条容易做错的语义：
  1. **路由缺失按「发送」处理**。新增通知类型或新增渠道时路由行还没补上，宁可多发也不能静默丢——丢通知的故障用户根本发现不了。
  2. **`OWNER` 档在通知无归属时回退默认接收人，不是丢弃**。系统级告警（索引器故障、复制超时）本来就没有归属人，理解成丢弃会让这类告警凭空消失。
  3. **不支持分人的渠道一律退化为广播**。TG 只有一个 chat id、Webhook 只有一个 URL，靠 `INotifier#supportsDirectDelivery()` 声明；配置页对这些渠道**不展示**收件人选项——给出一个不生效的开关比缺少这个功能更糟。
  新增渠道只要实现 `INotifier`（`channelKey()` 一旦发布就不能改，改了等于把用户已有路由配置全丢），配置页会自动多出一列。
- **通知文案统一按 Telegram 的 HTML parse_mode 写，其余渠道靠 `WeComNotifier#toPlainText` 还原**。所有动态内容（种子标题、剧名、索引器名）都要过 `StringUtils.escapeHtml`——TG 用 `ParseMode.HTML`，不转义的话标题里一个 `<` 就能让整条消息发不出去。代价是其余渠道拿到的是转义过的串，所以 `toPlainText` 必须**两步都做**：去标签 + 解实体，且**顺序不能换**（先解实体的话，用户内容里字面的 `&lt;b&gt;` 会先还原成 `<b>`、紧接着被去标签那步删掉，文本凭空少一截）。只做去标签踩过一次：企微/Bark/Gotify 上 `Tom & Jerry` 显示成 `Tom &amp; Jerry`，而 `&` 在 PT 片名和组名里相当常见。新增渠道时照抄 Bark/Gotify 的做法，不要直接透传 `message`。
- **PT 通知的第一行统一是「哪部作品的哪一集」，实现只有 `PtNotifyText#subject` 一份**。命中、完成、失败、季包、H&R 讲的本就是同一集，说法不一致时用户对不上号——原先命中说《三体》S01E05、完成和失败只给种子标题，而国内站的标题常带一长串站点前缀、季包更是整季一个名字。同类的还有 `PtNotifyText#size`（GB 以下自动降到 MB）、`#elapsed`、`#torrentProfile`（分辨率/来源/体积/做种数/站点 + 免费与 H&R 标记）。这些方法里**已经**做过 `escapeHtml`，调用方直接拼接，不要再转一次。**片段缺失时整段不写，不写「未知」**：一句「分辨率：未知」不帮用户做任何判断，只把真正有用的几段挤下去。
- **失败通知必须带 `fail_reason`**。四种 `FailReasonCode` 的处置方向完全不同（僵尸种要换资源、`TORRENT_NOT_FOUND` 要看下载器是不是被清了），原因已经落库且写得足够具体，不带进通知的话用户收到后唯一能做的是打开页面重查一遍。熔断提示（连续失败达阈值）**拼进同一条失败通知**而不是紧跟着再发一条：讲的是同一次失败，分两条既多一次打扰，原先那条还走 GENERAL 类型、在路由上和索引器故障混在一起。
- **拆分已有 `NotificationType` 必须配一条迁移，把新类型的路由行按拆分来源复制一份**（见 `20260783-notify-type-split.sql`）。新增取值本身是安全的（路由缺失按「发送」处理），但拆分会让用户对旧类型的关闭设置落空——明确关掉「下载完成」的人升级后会突然开始收到 H&R 达标提醒，他的设置没变、行为却变了。继承要**逐渠道**做（同一类型在 TG 上开着、企微上关着是常见配置），`recipient_scope` 一并继承。
- **不要发「本轮汇总」类通知**。`RssPollService` 原先每轮发一条「为订阅推送了 N 个种子」，三条理由各自都够删掉它：与逐条「订阅命中」完全重复（推 3 个 → 3 条详情 + 1 条只有数字的汇总）；它是广播（拿不到归属人），多用户下 B 会收到一个自己无从追查的数字；信息量本就为零，同样的内容 `log.info` 已经记了。反例是 `StuckEpisodeSweepService` 的按订阅聚合——那是把**几十条**逐集通知压成一条，方向相反。
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
  
  **第三处：种子内文件名也可能是绝对号**。`DownloadTrackService` 读下载器的文件列表判断
  「这个包含哪几集」，文件名 `One Piece S01E1174.mkv` 解析出 1174，而目标集是本地第 19 集，
  两边交不上会被 `isNoTargetEpisode` 判成「种子内不含任何目标集」直接中止——现象是
  「刚推给下载器就没了」。解法是在解析出集号的那一刻就用 `AbsoluteEpisodeMap#toLocalOrSelf`
  归一化成本地编号（映射由 `targets` 自带的 `tmdb_episode_number` 建），下游的排除文件、
  中止判定、认领对账全部不用改。`trySelectFiles` 与完成前补对账两处都要做，漏一处就是
  「能下但集状态不对」。
  
  **已知缺口**：字幕组的 `[Sakurato] One Piece - 1173 [2160p]` 这类裸数字命名仍匹配不上，
  但卡点不在集号——`YearSeasonEpisodeExtractor` 会把标题解析成
  `[ Sakurato ] One Piece - 1173 [ ] [ - ]`，在标题匹配那步就淘汰了。要支持得先修标题截断，
  `AbsoluteEpisodeMatchTest` 里有一条用例把这个现状钉住了。
  
  这个 bug 的症状很隐蔽：下载记录明明 COMPLETED，集状态却永远 MISSING，订阅进度一直卡着。**TMDb 的「剧集组」(episode_groups) 暂时用不上**——航海王那 18 个组没有一个对得上种子的 S23 编号，而主数据里已经有需要的绝对号了。等真碰到「发布组用的编号主数据表达不了」的剧（比如按 Netflix 分季）再考虑加 `pt_subscription.episode_group_id` 让用户手选，那种情况 TMDb 自己也推断不出来。
- **追剧日历的数据来自 `pt_subscription_episode.air_date`，由 `EpisodeAirDateSyncTask` 每 12 小时同步**（首轮兼做存量回填，所以升级上来的库不需要单独迁移动作）。播出日期本来就会变——改档、提前放送、季中休播——一次性回填脚本解决不了，定期同步是必需的而不是图省事。查询侧 `PtCalendarService` 是纯 SQL 范围查询，不打 TMDb。两条容易踩的：TMDb 撤掉日期时**不清空**已有值（撤档信息本身不可靠，清掉会让这集从日历上凭空消失）；同步只写日期确实变了的行，否则每 12 小时把整表 `update_time` 刷一遍，看起来像天天都有变化。
- **缺集体检（`pt/health/`）是「自动补搜默认关」这个设计的配套，不是它的替代**。`auto_search` 的库默认值是 `'0'`，建订阅时也刻意不打开——每条开着的订阅每轮都要向每个索引器打满一整份检索计划（最多 6 步），全量开启会让追完的老剧长期空转。代价是「订阅建完就没再管过、集一直缺着」这个最常见的场景**一条提醒都没有**：补搜落空通知只对开着开关的订阅发，`StuckEpisodeSweepService` 管的是「下完了没入库」，追剧日历只按日期铺格子、不回答"这一格为什么还是灰的"。体检把这批订阅列出来并支持就地开启，方向是补上可见性而不是改默认值。五条不要改坏的：
  1. **分档不是严重程度，是处置方向**（`EpisodeHealthBucket`）：`OVERDUE_MISSING` 去看搜索链路、`OVERDUE_IN_FLIGHT` 去看下载/上传链路、`BLOCKED` 要人工介入、`NO_AIR_DATE` 连"逾期"都算不出来。混成一个「有问题的集」列表，用户看到的是一堆无从下手的条目。判定优先级是 `BLOCKED` > `NO_AIR_DATE` > 按状态分——熔断是终态，有没有播出日期都不改变处置。
  2. **`air_date` 为 NULL 的取向与补搜侧相反，这是有意的**。`SearchSupplementService#aired` 把 null 当"已播出"（不让不可靠的日期否决业务动作）；体检把它单列成 `NO_AIR_DATE` 并把 `overdueDays` 置为 **null 而不是 0**。同一个字段、相反的兜底，因为代价不同：那边多搜一次，这边会在升级后的第一天把一屏未定档/未同步的集报成「逾期无穷天」。0 的语义是"今天刚播"，与"算不出来"混用还会让前端按天数倒序时把这批顶到最前。电影订阅**整体不参与体检**：它没有播出日期、逾期天数恒为 null，全堆在这一档既报不出问题，又会把真正缺集的剧集淹掉——而且那样报是错的，电影上映后几周内没有资源是正常状态不是故障（实测库里电影数比剧集还多）。这只影响体检的可见性，电影的 RSS 匹配/自动补搜/手动搜索一律照常。判据放在 `EpisodeHealthService#scan` 而不是 SQL：电影每条订阅只贡献一行哨兵集记录，推到 SQL 省不下什么，放 Java 侧能被单测盖住。
  3. **诊断挂在集上，不挂在订阅上**（`EpisodeHealthDiagnosis`）。同一条订阅完全可能一部分集缺着、另一部分卡在上传，压成一个"主诊断"就得定一套武断的优先级，而那个优先级对用户没有意义。订阅行展示的是去重后按枚举声明顺序排列的诊断集合。判据全部来自已落库的字段（`auto_search` / `last_auto_search_no_result` / `last_auto_search_reject_sign` / `state` / `file_confirmed`），**整个体检是一次纯 SQL 查询，不打任何外部请求**——与 `PtCalendarService` 同一个姿势，页面刷新不该变成一轮 TMDb/索引器调用。`SEARCH_NO_CANDIDATE` 与 `SEARCH_ALL_REJECTED` 必须分开（一个去改关键词和索引器，一个去松过滤规则，方向完全相反），这个区分在 `last_auto_search_reject_sign` 里已经用 `NO_CANDIDATE` 这个显式取值表达过了。
  4. **通知只发 `OVERDUE_MISSING` 一档，页面展示全部四档**。另外三档各自已经有人管：在途逾期由 `StuckEpisodeSweepService` 发 `LIBRARY_STUCK`、熔断在转 BLOCKED 那一刻通知过、无播出日期那档拿去打扰用户只会稀释信号。这条边界一旦模糊，同一集会从两三个渠道各通知一次，用户很快会把整类通知关掉。`EPISODE_OVERDUE` 也**不能并进 `SUBSCRIPTION_SEARCH`**——那一条只对开着开关的订阅发，而这一条要覆盖的恰恰是没开开关、压根没人在搜的那批；合并的话，用户关掉「补搜落空」（那类确实容易嫌吵）会连带把最需要的这条也关掉。
  5. **按收件人聚合成一条消息，不是每条订阅发一条**。首次启用时积压的订阅可能有几十条，逐条发比不提醒还糟。这与被明令禁止的「本轮汇总」通知的区别在于**这里没有对应的逐条通知可供重复**——不发这条就一条都没有。去重靠 `last_overdue_notify_sign`（集数 + 排序去重的集号，带集数前缀是为了压低 255 字截断后的碰撞——长篇动画的集号串常共享一长串相同前缀）：指纹变了立刻再发，没变则按 `pt.health.notify-repeat-days`（默认 7 天）重提醒。**只按"发过就不再发"是不行的**——一部永远补不上的剧提醒一次之后就再无声息，而它最该被记住。缺集补齐后必须 `updateOverdueNotifyState(id, null, null)` 清空，否则同一部剧下次再缺同一批集时指纹相等、通知被静默吞掉。写这两列走 `updateOverdueNotifyState` 而**不是 `updateById(sub)`**，理由与 `updateAutoSearchMissState` 完全相同（整实体写回会把补搜链路刚更新的 `last_search_time` 覆盖成旧值，让订阅永远"已到期"、每次心跳都重搜，且没有任何错误现象）。
  阈值 `pt.health.overdue-days` 默认 3 天是刻意的宽松：热门剧几小时内就有资源，但冷门剧、原盘小组、等字幕的片子拖一两天是常态，判早了会把一批正在正常走流程的集报成问题，而一个总在误报的看板用户看两次就不看了。
- **转移做种（`pt/transfer/`）只覆盖 IYUU 的「转移」，不覆盖「辅种」**。辅种要拿 infohash 去其它站点找同一份资源，依赖 IYUU 服务端的站点索引与各站 passkey，OSR 没有也造不出这些数据——用户装了 IYUU 是为了辅种的话，这个功能替代不了它。转移本身是跨轮次的状态机（本轮「导出 → 暂停态加种 → 触发校验」，下一轮「读校验结果 → 启动 → 删源端种子」），中间态落在 `pt_transfer_record` 上：目标下载器接手前必须校验一遍本地数据，要跑几分钟到几十分钟，放内存里的话进程一重启，目标端就留下一批暂停态的孤儿种子。五条不要改坏的：
  1. **目标端一律以暂停态加入、校验到 100% 才启动**。直接以运行态加进去的话，保存路径一旦对不上（路径映射配错是本功能最常见的故障），下载器会把整个种子**重新下载一遍**。`DownloaderTorrent#checking` 就是为此加的：只看 `progress` 分不出「校验还没跑完」和「校验完了但数据对不上」，两者都表现为进度不到 1，而前者要继续等、后者必须立刻撤销。
  2. **撤销目标端种子时 `deleteFiles` 恒为 false**。那份文件是源下载器正在做种的数据，传 true 会让源端种子立刻变成"文件丢失"、在它所属的站点上记一次 H&R——这是整个功能里唯一能造成真实数据损失的一步。撤销前还要确认种子是本次**新加**的（`AddTorrentOutcome.DUPLICATE` 说明目标端本来就有，那可能是用户自己加的任务）。qB 对重复种子同样返回 `Ok.`、分辨不出来，所以「加种前先查目标端有没有同 hash」是主防线，枚举只是第二道。
  3. **删源端种子是「OSR 从不删种」的第三个受控例外**（前两个是 `DownloadTrackService#removeUselessTorrent` 与 `TorrentCleanService`）。边界：只在目标端校验通过**且已启动**之后、只删种不删文件、可逐规则关掉。顺序也是硬的——**先启动目标端再删源端**，反过来的话中间那段窗口两边都不在做种，站点看到的是一个突然消失的种子。
  4. **H&R 考核中（`hr_state=PENDING`）的种子不转移**。换下载器后做种时长要从零重新累计（那是下载器自己的计时口径），更要紧的是站点的 H&R 要求是以种子级限额下发到**原**下载器上的（`setShareLimits`），限额不跟着种子搬家。把 PENDING 挡在门外，顺带使得"搬过去要不要重新下发限额"这个问题不存在。同理还有**集停在 `IN_FLIGHT`/`UPGRADING` 的记录**：种子下完 ≠ 活干完，文件还要传网盘，而 `DownloadTrackService#trackActive` 严格按跟踪标签在**本下载器**里认领在途记录，此时把种子搬走会让那条记录再也认不回来。
  5. **Transmission 不能作为转移来源**，靠 `IDownloaderClient#supportsExport()` 声明（做成能力声明而不是让调用方按类型硬判断，与 `INotifier#supportsDirectDelivery()` 同一套路）。它的 RPC 里唯一沾边的 `torrentFile` 字段给的是**服务端本地路径**而不是文件内容，OSR 与它通常不在同一个容器里。前端的源下载器下拉直接把 Transmission 滤掉，后端则在规则开头就整条报错——逐个种子失败会刷出一屏一模一样的记录。
  6. **源端的文件选择必须跟着搬**。qB 的 `progress` 是相对**已选文件**算的，一个「只下了其中几集」的种子照样显示 100%、照样满足转移条件；而 `exportTorrent` 导出的 .torrent **不含文件优先级**，原样加到目标端就是全选，校验后进度必然不到 1，被判成「该路径下没有这份数据」而回滚。这在本项目里根本不是边缘情况——OSR 自己就会给季包 `excludeFiles` 排除非目标集，下载器里留下的正是这种部分下载的种子。做法是加种后、`recheckTorrent` **之前**按源端的 `DownloaderTorrentFile#wanted` 在目标端 `excludeFiles`（顺序错了这次校验就白跑）；`wanted` 在两个客户端里默认 true，字段缺失时宁可多校验几个文件，反过来会把整个种子的文件全排除掉。源端文件列表读不出来时直接判失败，不要当作全选继续。
  7. **失败之后不能无限重试**。失败不改变源端种子的状态，下一轮的判定条件与上一轮完全相同——没有闸门的话，任何**持续性**故障都会变成「每轮转移一次、每轮失败一次、每轮发一条通知」，用户看到的是同一个错误无限刷屏（上面第 6 条就是这么暴露的）。`retryBlockedBy` 给了两档：6 小时冷却（让网络抖动、下载器重启这类一次性故障还能自愈）与累计 3 次后停止重试。两个 `TransferSkipReason` 分开（`RETRY_COOLDOWN` / `TOO_MANY_FAILURES`）是因为处置不同——前者等就行，后者要人去改配置。**停止重试必须配一个解除入口**（记录弹窗的「清除失败记录」→ `DELETE /pt-transfer-records/failed`，只删 FAILED 行），否则配置改对了种子却因为历史失败次数永远不会再被转移，比原来的刷屏更糟。
  「H&R 保护名单」的三路降级匹配（hash → 跟踪标签 → 种子名）被自动删种与转移共用，实现提到了 `pt/model/ProtectedTorrents`。**不要在任一侧复制一份**：漂移的表现是"某一侧偶尔漏保护"，几乎无法从日志追出来。至于"该保护哪些记录"由各业务自己查，那是各自的判断。
- **索引器检索的并发嵌套顺序是「索引器之间并发、单索引器内各步串行」**，实现只有 `SearchSupplementService#executePlan` 一份。一次补搜最多要向每个索引器发 6 次请求（ID 精确、不带季号的 ID、中文关键词、两条绝对号变体、英文/原语言标题），怎么排这两层循环有三种写法，只有一种是对的：
  1. **逐轮串行、轮内并发**（改造前）：每轮 `allOf().join()` 等最慢的索引器返回才开下一轮，一个慢站点把所有站点一起拖住。
  2. **全部一次性提交**（看起来最快，实际最糟）：同一索引器的 6 个请求同时涌向 `IndexerRateLimiter`，抢同一把 `slot.serial` 并各自叠加最小间隔，排最后的那个要等 `5 × (RTT + 间隔)`。限流器每段等待都受 `pt.indexer.max-wait-ms`（默认 30 秒）约束，超时抛 `IndexerBackpressureException` 快速失败——站点稍慢就有请求被静默跳过，只留一行 warn，症状是「搜索结果凭空少一批」，且越慢的站点丢得越多。
  3. **按索引器分线程、线程内串行**（现在）：请求到达限流器时天然排好队，一次排队浪费都没有，且请求总量与改造前逐轮发送完全相同。
  
  因此 `pt.search.max-concurrency` 那层局部闸门已删除——它比 `pt.indexer.global-concurrency` 还小，让全局上限永远轮不到生效，纯属压低扇出。**防封职责全部在 `IndexerRateLimiter`**，`global-concurrency` 现在是搜索扇出的唯一上限，不宜低于用户常用的索引器数量。
  
  **单个索引器跑完整份计划有时间预算**（`pt.search.indexer-budget-ms`，默认 30 秒，0 表示不限制）。并发化之后墙钟由最慢的那个索引器决定，实测 7 个站里 6 个在 31 秒内跑完全部 6 步、第 7 个拖到 56 秒，而它多跑的几步一条有用结果都没带回来——计划里的步骤本就是逐级兜底、越往后命中率越低，慢站点跑不完时放弃尾部是划算的。三条别改坏的：预算**从整份计划开始算起**而不是各索引器自己的起点（所有索引器几乎同时启动，统一起点才能让这个值直接对应「用户最多等多久」）；它是**软上限不是超时**，只在每一步开始前检查、不打断已发出的请求（`TorznabClient` 读超时另有 60 秒），所以最坏耗时是「预算 + 最后一步的实际耗时」；放弃时**必须 warn 出来**说清是哪个索引器、放弃了几步，只写 debug 的话用户看到的是「结果少了几个」而日志里一切正常，根本无从想到是某个慢站点没跑完。

  **有早停的路径不能拼成一份计划**。`supplement()` 的自动推送模式是三级回退、逐级早停（某级推送成功就不再发下一级的请求，命中率高的订阅一次只打 1~2 级），拼成一份计划等于每次把所有级别打满，请求量翻几倍。无早停的两处（`searchSeasonCandidates` 的全季搜索、手动挑选模式）才拼整份计划——它们本来就要把所有级别的结果合并去重后统一匹配。
  
  **每一级放行到下一级的判据是「推送成功」而不是「过滤后有匹配」**。`pushBest` 会因为候选都已推送过、被过滤规则全清、下载器并发已满、该集已被别的轮次占位等原因返回 false（见 `SubscriptionEngine#handleGroup` 的几处 `return false`）。早先第二级按 `matched.isEmpty()` 判，一旦本级有匹配却推送失败，第三级的英文名兜底就被跳过、这一轮空手而归，而那批资源本来可能推得动，只能等下一轮补搜重来。
- **定期自动补搜的节奏（`AutoSearchService`）有四层修正，每层都对应一个具体故障**：
  1. **未播出的集不参与**（`SearchSupplementService#aired`）——这是 `pt_subscription_episode.air_date` 除追剧日历之外的第二个消费者。一部刚播到第 3 集的 12 集新番，剩下 9 集恒为 MISSING，于是这条订阅每轮都「有缺集」、每轮都打满一整轮索引器请求、每轮都落空，而用户收到的是「未找到可用资源，可检查关键词与索引器配置」——真实原因是**还没播**。省下的请求是次要的，避免这条误导才是主因。`air_date` 为 null 一律按已播出处理（可能未定档、TMDb 未录入，也可能只是存量行还没被每 12 小时的同步任务扫到），取向与「撤档时不清空已有日期」一致：日期本身不够可靠，不能让它单方面否决业务动作。电影压根不参与日期同步，这条对电影恒真。
  2. **候选收窄在 SQL 层**（`listAutoSearchCandidates`：ACTIVE + 开关开着 + 有 MISSING 集；**不含 IN_FLIGHT**，那是已经在下了、补搜对它无事可做）。追完的老剧长期留在 ACTIVE 是常态，拉全部 ACTIVE 再内存过滤等于每轮为它们各查一次集表。
  3. **到期时刻按 id 派生的确定性抖动（0 ~ +20%，只向后）**。首次启动时所有订阅的 `last_search_time` 都是 null、全体同时到期，串行跑完后它们的时间又几乎相同，一个周期后再次聚在一起——**这个抱团是自我维持的，不会自己散开**。只向后偏移保证实际周期不会短于用户配的值；用 id 而不是随机数，同一订阅每轮算出同一个偏移，行为可复现也写得出测试。
  4. **单轮总耗时预算**（`pt.search.auto-search-round-budget-ms`，默认 20 分钟，刻意小于 30 分钟的心跳）。订阅之间是串行的，而一条剧集订阅的墙钟由最慢的索引器决定（`indexer-budget-ms` 还是软上限），最坏 40~50 秒，几十条就能跑过一个心跳周期、让下一次心跳被 `AutoSearchTask` 的重叠保护整个吞掉。超预算的订阅**原样留到下一轮**（`last_search_time` 未改动，下轮天然从断点接着走，不会饿死）。**刻意不用并发解决**：`IndexerRateLimiter` 是全局的，多条订阅同时搜只会在限流器上排队并把等待推到 `max-wait-ms` 之外触发静默跳过——就是 `executePlan` 那条注释里记下的坑。
  
  另有两条关于「落空」的：**按连续落空次数退避**（`last_auto_search_no_result` 已从 char(1) 的 0/1 改成计数，24 → 48 → 96 小时，封顶一周；命中一次立刻回到基准）——片源确实不存在的老剧原本会永远每 24 小时打满一整轮请求，而这件事用户从现象上根本看不出来，日志里每轮都「正常地」搜了一遍；**落空通知除「首次落空」外，原因种类变了要再发一次**（`last_auto_search_reject_sign` 存排序去重的 reason_code 指纹）。指纹**不含计数**：摘要里「98 个非免费种」的数字每轮都在变，含进去等于每轮都判「原因变了」、去重彻底失效。「压根没搜到候选」记作 `NO_CANDIDATE` 参与比较——它与「候选全被 freeOnly 淘汰」处置方向完全相反，这个翻转恰恰是用户最需要知道的一次变化，不能被去重吃掉。
- **补搜的检索是季粒度的，单集靠「本地匹配 + 兜底补发」两层**（`SearchSupplementService#searchAndPushMissing`）。`seasonPlan()` 的四步全是季粒度——ID 精确（`season=23&ep=null`）、绝对编号剧的不带季号 ID、关键词 `片名 S23`、关键词 `英文名 S23`，**没有任何一步带 `E{集号}`**。逐集分支只是拿这份候选池在本地按集号过滤一遍（`filterByTarget`），把请求量从逐集搜索的 O(N×M) 压到 O(N+M)，这是它存在的全部理由。代价是候选池里没有的单集，本地匹配再准也无米下锅——而 Jackett/Prowlarr 对多数站点是把 `q` 按空格切词做 AND 匹配，`S23` 这个词命不中标题里的 `S23E05`，单集在索引器那一层就被滤掉了。症状是「后台补搜只会下季包，单集永远要用户自己去逐集手动搜」，长篇动画最常见。四条不要改坏的：
  1. **候选池里一集都没匹配上的集才补发单集检索**（`fallbackPerEpisode`，上限 `pt.search.per-episode-fallback-limit` 默认 5 集、墙钟 `-budget-ms` 默认 180 秒）。补发走 `supplement(subId, ep, 片名 SxxEyy)` 而**不是另拼一份计划**：那条路径已经有 ID 步带 `ep`、关键词带集号、绝对号变体、英文名兜底和三级早停，与用户在订阅页点单集「搜索补集」跑的是同一件事。两份实现漂移的表现正是本条要修的现象本身。本地匹配得上的集一个请求都不多发——补发是兜底，不是常态路径。
  2. **不能以「季包没推成」为前提跳过逐集分支**。站上并存多个切法的季包时（长篇动画的「1-500 合集」「501-1000 合集」各一个），`excludeAlreadyRecorded` 每轮排掉上轮推过的、又推一个新的，`seasonPushed` 轮轮为 true，逐集分支永远轮不到，单集就此永远补不上。防重复推送靠的是**季包推成功后重查一次集状态**（季包会把当时所有 MISSING 集一次占成 IN_FLIGHT，见 `SubscriptionEngine#resolveTargets`），不重查的话下面每一集都会在 `resolveTargets` 处落空、各往 `pt_search_log` 写一行「无可占位的缺失集」，一季几十集就是几十行纯噪音。
  3. **补发的上限与预算都是软上限，且被挡下的集必须 warn 出来**。静默截断会读成「这些集都搜过了、站上没有」，而真相是压根没发出去过请求。它们不会饿死，下一轮从同样的位置接着走。
  4. **缺集体检页的「立即补搜」是同步等结果的**，耗时构成因此从「季搜索 30 秒」变成「季搜索 + 补发 180 秒」，前端 `searchMissingApi` 的超时（240 秒）是按这个上限配的。改这两个配置要一并调那里，否则补发必然被前端判超时——而它恰恰是这个按钮现在最有价值的部分。
- **「未播出的集不参与补搜」在后台与前端是两套入口，判据只有 `SubscriptionService#aired` 一份**。后台侧 `searchAndPushMissing` 开头就把全是未播集的订阅整条跳过；前端「一键补齐全部」的集号来自 `getProgress`，那里**另外**给一份 `unairedEpisodes`（`missingEpisodes` 的子集），由 `fillableMissingEpisodes` 相减后跑批。三条：**缺集串照旧显示全部**（用户要知道这季还缺几集，把未播集藏起来会让缺集数与总集数对不上），只有跑批跳过；**按钮计数用「可补齐」而不是「仍缺」**（写 12 却只跑 3 集会让用户以为漏跑了），且要把跳过数说出来；**判据两处共用**，漂移的表现是「后台跳过了、前端还在搜」，日志里看不出任何异常，只是用户在弹窗前多等十几分钟——一部刚播到第 3 集的 12 集新番会为 9 个注定落空的集各打一整轮索引器请求。`air_date` 为 null 一律按已播出处理，取向与撤档时不清空已有日期一致。
- **写补搜的落空状态必须走 `updateAutoSearchMissState`，绝不能 `updateById(sub)`**。踩过的坑：MyBatis-Plus 默认的 `FieldStrategy.NOT_NULL` 会把实体上所有非 null 字段一并写回，而 `AutoSearchService` 手里那份订阅是**本轮开始时**查出来的，它的 `last_search_time` 早已被 `searchAndPushMissing` 在本次搜索末尾更新过——整实体写回会把刚写入的新时间覆盖成旧值，于是这条订阅永远处于「已到期」状态、**每 30 分钟心跳都重搜一遍**。而通知那侧有去重，用户完全看不出来，只是索引器被默默打了几十倍的请求，落空退避也会因此彻底失效。这个坑的隐蔽之处在于它没有任何错误现象：功能"正常"，只是频率错了几十倍。指纹传 null 表示清空（命中后重置），因此必须走 `LambdaUpdateWrapper` 显式 set，不能改成传一个只填了两列的实体——NOT_NULL 策略下 null 字段会被直接跳过，重置写不进去。
  **同一个坑的另一面：`SearchSupplementService` 收尾写 `last_search_time` 也必须走 `updateLastSearchTime` 而不是 `updateById(sub)`。** 补搜一次调用里订阅表会被**两个不同的实例**写到——`SubscriptionEngine` 推送成功后在它自己查出来的那份上写 `last_match_time`，而 `searchAndPushMissing` 收尾时手上是本轮开头的快照，整实体写回会把推送刚写入的 `last_match_time` 覆盖回旧值：列表页的「最后匹配」时间凭空退回去，而推送其实是成功的。单集补发让这条路径变成了常态（它内部会重新查一份订阅实例交给推送链路）。
- **PT 过滤的关键词有标题、描述两套，判定对象不同、缺失时的取向也相反**。`exclude_keywords` 只匹配标题，`description_exclude_keywords` 只匹配描述（`TorrentFilterEngine#rejectReason`，两条相邻）。加后者是因为有一类属性标题里根本不写——蓝光原盘最典型：国内站只在种子描述里标一句「原盘」，标题与压制版逐字同构，两者都解析成 `source=BluRay`，来源白名单分不开；体积上限虽能挡住原盘，却会连体积区间重叠的 REMUX 一起切掉，而 REMUX 是 mkv、播放器本来吃得下。两条不要改坏的：
  1. **标题为空一律淘汰（`BLANK_TITLE`），描述为空一律放行**。标题是索引器必给的字段，描述不是——不少索引器压根不返回 `<description>`，按「判不出即淘汰」处理会把这些站点的候选整批清光。
  2. **`EXCLUDED_DESCRIPTION_KEYWORD` 与 `EXCLUDED_KEYWORD` 是两个码，别合并**。命中的是标题还是描述，决定用户该去改哪个输入框，聚合成一个就分不出来了。
- **STRM 生成的「输出根目录 / 是否下字幕 / 最小体积」可按任务覆盖**，存在 `openlist_strm_task.strm_override`（JSON），合并规则见 `StrmSettingsFactory`——照 `pt_subscription.filter_override` 的约定，只有出现在 JSON 里的键才覆盖，该列为空时行为与引入前完全一致。两条不要改坏的约定：
  1. **`strmDir`/`strmOneFile` 按路径反查任务，不要改成让调用方传任务**。半数调用方手里根本没有任务对象（`AsynHelper` 的复制完成触发、`CopyRecoveryTask` 的兜底恢复、TG 的 `/strm <路径>`），它们退回全局配置就会让同一目录因「谁触发的」而输出到不同根目录，长出两棵 STRM 树，一致性检查还会把其中一棵报成孤儿。匹配走 `pickCoveringTask`：落在路径分隔符上、取最长（最具体）的任务、停用的任务照样参与。
  2. **URL 编码开关与视频/字幕扩展名刻意不可覆盖**。encode 有三个解码侧消费者（`RenameOrphanScanServiceImpl`、`RenameCleanupService`、`StrmSourcePathResolver` 都要从 .strm 内容反解回网盘路径），扩展名是 `sys_config` 里的全站清单（`MediaExtensionProvider`）；两者都是「播放器吃什么」的全局属性，分库配置只会制造解不开的历史数据。
- **TMDb 匹配里，打分只排序，采纳与否由两道独立检验决定**（`TMDbClient#doSearchOnce`）：正面的 `hasEnoughEvidence`（标题命中 / 年份接近 / 英文规范名命中，三选一）与反面的 `episodeCountContradicts`（候选剧的全剧总集数装不下这一集）。三条容易改坏的语义：
  1. **冠军没通过就往下看次席，不是整批放弃**（上限 `MAX_CANDIDATES_EXAMINED=3`）。正确答案经常只是打分上的第二名——中文作品拿英文名去搜时，它的 name/original_name 全是中文，一分标题分都拿不到。`Perfect.World.S01E282.2021` 被刮成 TMDb 上 2000 年那部 6 集英国喜剧就是这么来的：英国剧 `original_name` 与解析标题逐字相等拿满 +100，国产动画《完美世界》只有年份吻合的加分，还顺带把年份改写成 2000、按 `origin_country=GB` 分到了「欧美剧」。
  2. **集号反证留一倍余量**（`episode > total * 2` 才判矛盾），不是简单的 `episode > total`。集号有三套（见上一条），发布组按绝对集号命名时集号本来就可能略超 TMDb 的记录，新集刚播出时 TMDb 滞后一两集也是常态；只否掉差着数量级的情况，误否的代价（该文件不重命名）才压得住。
  3. **拿不到总集数就不做判断**。反证只在证据确凿时否决，绝不因为「查不到」而拒绝刮削。
  4. **全等命中在排序上自成一档**（`rankCandidates` 先比 `TITLE_MATCH_EXACT` 再比分数），年份与热度再高也跨不过去。单靠分数拉开是不够的：全等与包含只差 40 分（100 vs 60），而年份挡位本身能摆动 85 分。事故：`[梦魇绝镇 第四季].From.2026.S04E10` 被刮成《怪奇物语：1985故事集》——后者原名 `Stranger Things: Tales From '85` 包含 `from`（而 `from` 恰好卡在 `MIN_CONTAINS_LENGTH_LATIN=4` 的下限上）、首播 2026 与文件名年份完全一致、热度又高；真正的 `From (2022)` 标题逐字相等却因为 2026 是**本季播出年**、候选侧是首播年而被扣分，反而输掉。**分档只抬全等、不剔除其余候选**——全等候选被集号反证否决时（`Perfect World` 那类）它们还要接着被检验。
  5. **标题尾部的季号在 `TitleProcessor` 就剥掉**（`SEASON_SUFFIX`：`第四季`、`第 2 部`、`Season 3`）。TMDb 的条目名里从来不带季号，多出的三个字让整次查询落空，于是**最强的信号（中文作品名）作废**、降级到 englishTitle 那个弱得多的候选——上条事故里真正把门打开的就是这一步。剥后缀只动标题、不动抽取管线，且**剥空则不剥**（《第五季》这类以季号为名的作品）。PT 订阅侧同样受益：`SubscriptionEngine` 用 `originalTitle` 当种子的 `parsedTitle` 去比对订阅标题，而订阅存的是不带季号的作品名。
  6. **中文季/集与 `SxxExx` 是互补关系，不是二选一**（`YearSeasonEpisodeExtractor#extract`）。原实现是「中文匹配到了就直接返回」，于是 `[某剧 第4季].Foo.2026.S04E10` 的集号**根本不会被解析**（season=04 而 episode=null），下游表现为重命名后没有集号、刮削也对不上单集；而中文数字写法 `第四季` 因为正则只认 `\d` 反而躲过了这条早退，同一部剧换个写法结果南辕北辙。现在两段都跑，**`SxxExx` 优先、中文只填它没给出的字段**。另一半是**落在方括号内部的中文季/集不参与标题截断**（`cutPointOf`）：部分中文站把「剧名 第X季」整个写进方括号，按季号位置切会把作品名从中间切开、连方括号都只剩半个，而 `TitleProcessor` 的括号正则要求成对，作品名会整个漏给 englishTitle。不截也不会把季号留在标题里——上一条的 `SEASON_SUFFIX` 负责去噪，两者是同一件事的两半。
  年份打分档位（`scoreCandidate`）刻意没扣到能一票否决的程度：文件名里的年份对剧集常常是本季播出年而非首播年（`search()` 注释里有完整推导），`The.Office.S03E05.2019` 这种差 14 年的正常命中必须还能靠标题分活下来。真正的否决只交给集号反证。
- **NFO 里的作品标题必须与重命名同源，取 `info.title` 而不是 TMDb 详情里的 name/title**（`NfoXmlBuilder#preferredTitle`，三个 builder 共用）。详情是按 `openlist.tmdb.metadata.language`（默认 zh-CN）请求的，但 TMDb 缺中文翻译时 name/title 会**直接退回英文**（Apple TV+ / Netflix 的新剧、冷门纪录片、动画常见），而 `TMDbClient#getBestTitle` 的中文别名回退恰好是为这种情况准备的。旧实现三处都写成「details 优先、`info.title` 兜底」，把已经取回的中文别名挤掉了。症状特别容易误判成「刮削没生效」：目录和文件名是「足球教练 (2020)」，同目录 tvshow.nfo 里却是 `<title>Ted Lasso</title>`，而媒体库显示的是 nfo 里那一个——中文目录配英文剧名，比两边都英文更让人怀疑是不是哪里配错了。`originaltitle` 例外，它走 original_name/original_title，本来就该是原语言标题，不参与中文化；`EpisodeNfoBuilder` 的 showtitle 也必须一并改，与 tvshow.nfo 不一致会让 Emby 归组时对不上。改完存量目录不会自己更新，要重新刮削一次才覆盖。
- **挑中文别名的地区优先级只有 `TmdbTitleRegions.CHINESE` 一份**（`CN → TW → HK → SG`），刮削侧 `TMDbClient#fetchChineseAlias` 与 PT 侧 `TmdbSearchService#fetchChineseAlias` 共用。早先刮削侧只认 CN，于是只在台/港登记了中文名的作品（日番、港片常见）在订阅列表里是中文、在媒体库里是英文——这种漂移从日志里根本看不出来。两侧都要**逐条校验「确实含中文」**：别名是众包数据，CN 条目里登记罗马音、拼音、英文副标题的不在少数，只按地区取会把一串英文换成另一串英文。
- **`tmdb_cache` 靠 `TmdbCachePurgeTask` 定期清理**（启动后 5 分钟首跑，之后每 6 小时）。这张表只在「同 key 再次被请求」时才会 upsert 覆盖，刮完一次就不再访问的 key 不会自己消失；没有这个任务时表单调增长（实测一个中等规模的库里 276 行有 237 行是过期死行）。删除走 `TmdbCacheMapper.deleteExpired` 分批进行，不要改回一条 DELETE 删干净。
- 后端 Java 异常写在 `/data/logs/sys-error.log`，**不在 docker stdout**（stdout 只有启动 banner）。排查启动失败：`docker cp osr-backend:/data/logs ./tmp` 后看 `sys-error.log`；容器反复重启时先 `docker update --restart=no osr-backend && docker restart osr-backend` 让它崩溃后停住再读日志。
- **后端的接口鉴权只有「认证」这一层是框架给的，「授权」全靠各 Controller 自己判**。`SecurityConfig` 是 `anyRequest().authenticated()`，而 `sys_menu.perms` 只驱动前端菜单可见性、**不参与后端放行**——所以在没有额外判定的接口上，任何一个登录用户都等价于管理员。现在的分工是：订阅按归属隔离（`PtSubscriptionRestController` 的 `denyIfInaccessible`），系统级配置的**写操作**限管理员（`BaseCrudRestController#adminOnlyWrite` 钩子，索引器/下载器/媒体服务器/删种规则/转移规则五处覆写为 true，外加参数设置的四个写端点直接调 `denyIfNotAdmin`）。五条不要改坏的：
  1. **管理员判据取「`userId==1` 或 `ROLE_admin`」两者之或**（`BaseController#isAdmin`）。两条判据的失效方式不同：前者依赖 `SecurityUserDetailsService` 把 SysUser 写进 request attribute（那段代码自己就吞异常），后者依赖 `sys_role.role_key` 确实是 admin。只留一条的代价是**把管理员锁在配置页外面**，而配置页恰恰是唯一能修好这件事的地方，锁死就只能进数据库改。多出来的「松」是可接受的：两条都指向同一个超级管理员。
  2. **用钩子而不是给子类标 `@PreAuthorize`**。add/edit/delete 是从 `BaseCrudRestController` **继承**来的，注解要落到继承方法上依赖 Spring Security 对 targetClass 的解析细节，而这类机制不生效时是**完全静默**的——接口照常 200，只是权限没了。`AdminOnlyWriteTest` 用真实的 `PtIndexerRestController` 而不是造一个假子类，钉的就是「继承来的那三个也确实被拦住」。
  3. **只拦写不拦读**。这几个页面的 list/getById 要供前端渲染，敏感字段已被 `maskSensitiveFields` 抹掉；把读也拦掉会让非管理员打开页面看到一片 403，而他多半只是想看看当前配了哪些索引器。
  4. **索引器/下载器/媒体服务器的 `/test`、`/categories` 必须在读取密钥之前就拦住**。它们会把**已保存的**密钥填进来（前端留空表示沿用），再发往请求体里**调用方指定的** url——校验放到后面等于给出一个「把 apikey/密码送到任意地址」的接口。`PtCleanRuleRestController#run` 与 `PtTransferRuleRestController#run` 同理，它们会真的删种、搬种。
  5. **`@EnableMethodSecurity` 现在是防呆不是功能**：仓库里一个 `@PreAuthorize` 都没有，它不改变任何行为。留着是因为没有它时，谁往 Controller 上标了 `@PreAuthorize` 也不会报错、不会告警，只是那行注解完全不生效。
- **改密码之后此前签发的令牌必须失效，判据只有 `JwtTokenUtil#isInvalidatedByPasswordChange` 一份**。无状态 JWT 没有「注销」，不做这件事的话「密码可能泄露了，赶紧改一个」这个动作什么也挡不住。水位线取 `sys_user.pwd_update_date`（`resetUserPwd` 的 SQL 里已经带了 `pwd_update_date = sysdate()`），比维护令牌黑名单便宜得多，也不需要 Redis。三条：**四个入口一个都不能漏**——`JwtAuthenticationFilter`（业务接口）、`AuthApiController#extractValidatedUser`（`/info`·`/routers`·`/changePassword` 走的是本类自己的校验，因为整个类是 `@Anonymous`、根本不经过过滤器）、以及 **`/refresh`**；漏掉 refresh 等于整条加固作废，攻击者拿旧刷新令牌换一对新的即可，而新令牌的 iat 永远在水位线之后——**改密码反而成了一次续期**。**比较前要把水位线向下取整到秒**：JWT 的 `iat` 只有秒精度而 `pwd_update_date` 带毫秒，不取整会让「同一秒内先签发、后改密」的令牌被误杀，现象是刚登录成功下一个请求就 401、重试一次又好了。**`pwd_update_date` 为 NULL 一律不失效**，否则升级上来的存量库里没改过密码的人会在部署那一刻集体被登出。过滤器那侧刻意复用 `loadUserByUsername` 已写进 request attribute 的 SysUser，**不再查一次库**——那是每个业务请求都走的路径。
- **新建用户的口令一律 BCrypt**（`AuthApiController#register`）。这里曾经写的是 `encryptPassword`（MD5(loginName+password+salt)，RuoYi 遗留），而 `changePassword` 写的是 BCrypt——于是**每个新注册用户的密码强度都退回到那一版**，且再也不会自己升级（只有改密码才会）。`matches()` 的 MD5 分支保留给存量行，但不该再产生新的 MD5 行。`SysUser#toString()` 同样**不许输出 password/salt**：接口响应那侧有 `@JsonIgnore` 挡着，但 toString 是另一条出口，任何一处 `log.debug("{}", user)` 就把口令哈希写进保留 7 天的日志文件；要看格式用 `passwordFormat()`，它只报 BCRYPT/MD5/NONE。
- **日志级别的唯一来源是 `logback-spring.xml`，`application.yml` 里不要再配 `logging.level.*`**。Spring Boot 的 `logging.level.*` 在 logback 配置加载**之后**应用，两边写了同一个 logger 时永远是 yml 赢。踩过一次：yml 写 `com.osr: debug`、logback 写 `<logger name="com.osr" level="info"/>`，于是整个 `com.osr.*`（连带 osr-system、osr-framework、osr-common）实际跑在 DEBUG，而读 logback 配置的人会得出完全相反的结论——两份配置各自都「看起来是对的」。临时排查用环境变量 `LOGGING_LEVEL_COM_OSR=debug` 覆盖，不必改文件。同一个文件里另外四条：**日志文件是「全量 + 错误」的<包含式>两层，不要再切回按级别互斥的多个文件**——原先 `sys-debug.log`（LevelFilter，只收 DEBUG）与 `sys-info.log`（ThresholdFilter，收 INFO 及以上）加起来才是全量，而 `com.osr.openliststrm`（几乎全部业务代码）跑 DEBUG、`com.osr` 其余部分跑 INFO，于是一次 STRM 生成或一轮补搜的上下文必然横跨两个文件，只能靠时间戳手工交错着读；实时日志页一次只 tail 一个文件，那个切法让它选哪个都看不全（页面上的 Debug/Info/Warn/Error 四个复选框有一半是死的，因为那个文件里根本没有那个级别）。现在只有 `sys-all.log`（ThresholdFilter DEBUG，全收）与 `sys-error.log`，**磁盘写入量与切分时完全持平**（切分时 = DEBUG量 + (INFO+WARN+ERROR)量 + ERROR量 = 全量 + ERROR量），过滤交给展示层。`sys-error.log` 冗余但必须留着：后端异常不进 docker stdout，它是排查启动失败的唯一入口，价值就在于噪音为零。**四个 appender 都要配 `totalSizeCap`**——`maxHistory` 只管天数，`SizeAndTimeBasedRollingPolicy` 下同一天能滚出任意多个 `%i` 分片，此前一个都没配，业务模块常态跑 DEBUG，一次大批量 STRM 生成能把挂宿主机的 `/data` 吃掉。**两个文件 appender 外面包 `AsyncAppender`，且都必须设 `discardingThreshold=0`**（默认值会在队列 80% 满时丢弃 INFO 及以下；合并前 debug 那个留默认是因为 `sys-info.log` 还在旁边一条不丢地兜着 INFO，现在 `sys-all.log` 是唯一的全量记录，再按默认走就意味着日志量激增——也就是最需要日志的时刻——静默丢掉 INFO，与 sys-error.log 那条「悄悄丢比慢一点糟得多」是同一个道理；队列相应提到 4096 吸收 DEBUG 洪水的尖峰）；**dev profile 的 `<root>` 只能挂 console**，`appender-ref` 是追加不是替换，再挂一遍底层 `file_*` 会让同一条日志经由 async 和直连两条路径写进同一个文件，每行出现两次。`includeCallerData` 保持默认 false 是安全的：两个文件 appender 的 pattern 都不含 `%method/%line`（dev 那个带行号的 pattern 是在这些 appender 定义之后才覆盖的，只对 console 生效）。
- **实时日志页（`LogWebSocket` + `views/monitor/log/realtime.vue`）推的是结构化 JSON，级别是解析出来的字段而不是猜的**。早先后端拼 `<div class='log-item log-info'>…</div>` 推给前端，前端再用 DOMParser 把标签剥掉只取 textContent、自己重新判级别重新上色——那层 HTML 纯属白做（每行一次 DOM 解析），还曾是个实打实的 XSS 面（日志里含网盘文件名等非可信数据）。更要命的是两边都用 `line.contains("ERROR")` 猜级别：打印索引器响应体、异常消息文本的 INFO 行会被染红并被 Error 过滤框筛出来。四条不要改坏的：**行解析正则与 `logback-spring.xml` 的 `log.pattern` 是一对隐性耦合**——改了 pattern 而没改正则，页面不报错也不告警，只是每行都落进「续行」分支，时间/级别/logger 全不显示、级别过滤全部失效、整屏一种颜色，`LogWebSocketLineCodecTest` 就是为让这种改动立刻红掉而写的；**异常堆栈的续行必须继承首行级别**（`LineCodec` 持有 lastLevel，因此每个连接一个实例、不能做成静态方法），否则关掉 Error 过滤时堆栈还在刷屏、开着 Error 过滤时又只剩一句异常消息没有堆栈，而堆栈正是故障时唯一有用的东西；**历史日志倒序读出来后必须 reverse 再<正序>逐行编码**，倒序编码会让续行继承到时间上更靠后那条的级别；**关键字过滤匹配整行（含 traceId）**，粘一个 traceId 进去就等于把一次请求的全链路日志从几千行里拎出来，这是那个框最有用的用法，高亮走分段渲染而不是 v-html。
- **`LibrarySyncService` 能用并发而 `AutoSearchService` 刻意不用，两者不矛盾**——差别在下游是谁。补搜打的是索引器、受全局 `IndexerRateLimiter` 约束，并发只会在限流器上排队并把等待推过 `max-wait-ms` 触发静默跳过；对账打的是 Emby（局域网自建服务）与 TMDb（`TMDbApiService` 里已有全局 `Semaphore(4)`，且详情有两级缓存，很少真的发请求）。**并发度的真正卡点是数据库连接**：`SubscriptionService#refresh` 带 `@Transactional`，事务里夹着一次 Emby 网络往返，所以每条并发对账都会在整个往返期间占住一个连接（Druid `maxActive=50`）；`pt.library.sync-concurrency` 默认 4，要调到几十请先想清楚那是在跟所有在线请求抢连接。`refreshAll` **必须等齐才返回**：调用方紧接着跑 `StuckEpisodeSweepService`，那一步依赖「本轮刚被推进 IN_LIBRARY 的集」已经落库，提前返回会让这些集在同一轮里被当成卡死的在途集、发出一批本不该发的 `LIBRARY_STUCK`。
- **`SearchLogService#prune` 是攒批触发的，不要改回「每超一条就删一条」**。它挂在**每一次**日志写入的末尾，而补搜是逐集调 `recordSummary` 的（一季几十集就是几十次），稳态下「删一条」那条路径会被走几十遍、每遍一次 SELECT 加一次 DELETE。现在攒到 `PRUNE_TRIGGER`（保留量的 1.5 倍）才跑一次、一次削回保留量，同样一轮补搜最多触发一次。**所以实际保留上限是 300 而不是 200**，这是有意的——200 从来就是个「够用就行」的量。投影**必须用 `QueryWrapper` 传列名字符串**取 id，不能用 `LambdaQueryWrapper#select(实体::getXxx)`：后者会立刻解析 MyBatis-Plus 的实体 lambda 缓存，而纯单测里直接 new 出本类时那份缓存还不存在，会抛 `can not find lambda cache for this entity`（`eq`/`orderBy` 是惰性的所以不炸，只有 `select` 会）。
- **Actuator 有两道锁，缺一不可**。`application.yml` 的 `management.endpoints.web.exposure.include` 决定「有什么可访问」——只开 health/info/metrics/prometheus，**刻意不开 `env`**（会把含数据库密码在内的全部配置原样吐出来）与 **`heapdump`**（直接给出内存快照）；`SecurityConfig` 的 `ACTUATOR_ANONYMOUS`（默认 **false**）决定「谁能访问」。开第二道锁时第一道就是唯一防线，所以那份白名单不要随手加东西。**端口沿用 6895 而不另开管理端口**：另开的话它不经过主 `SecurityFilterChain`，一旦有人顺手把那个端口也映射出去就是彻底裸奔。Docker 的 healthcheck 仍打 `/api/health`（匿名、只探数据库），**不要改成 `/actuator/health`**——后者默认要 JWT，拿它做探针会让容器永远 unhealthy，而 `frontend` 的 `depends_on` 是 `service_healthy`，整个前端会跟着起不来。
- **`docker-compose.yml` 的 `mem_limit` 与镜像里的 `JAVA_OPTS=-XX:MaxRAMPercentage=70` 是一对，改一个要想到另一个**。没有容器内存上限时 JVM 看到的是宿主机全部内存，那个百分比会算到一个比默认（25%）更离谱的堆上——实测一台 31G 的宿主机上，无上限时堆被 ergonomic 定到 8.4G 而进程实际只用 174MB。`BACKEND_MEM_LIMIT` 可在 `.env` 覆盖，调它就等于调堆（堆 = 该值 × 70%）。另外 `ENTRYPOINT` 用的是 `sh -c "exec java ..."` 而不是直接 shell 形式：**`exec` 让 java 接管 PID 1**，从而收得到 `docker stop` 的 SIGTERM；收不到的话 Spring 的关闭钩子不跑，logback `AsyncAppender` 队列里没写完的日志会跟着丢——而那恰恰是关停前最后一段、排查问题时最想看的日志。**容器仍以 root 运行、`/data` 仍是 777**，这是权衡后保留的：宿主机 `/data` 里的既有文件都是 root 建的，换成非 root uid 会让所有存量部署在升级那一刻失去写权限，而修复要用户手工去宿主机 chown。
