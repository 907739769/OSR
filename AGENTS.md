# OSR (OpenList STRM Relay) 项目知识库

## OVERVIEW
影视 STRM 管理系统。Java 25 (Spring Boot 4.0.6) + Vue 3 + Vuetify 3，Docker 双容器部署。核心功能：STRM 文件生成、文件夹同步、Telegram Bot 控制、TMDb 刮削/重命名、第三方回调自动化。

> 本文件是本项目唯一的 AI 知识库，Claude Code 与 opencode 共用。根目录 `CLAUDE.md` 仅做引用，改动请直接改本文件。

## STRUCTURE
```
├── osr-admin/          # 启动模块 (Spring Boot main)，端口 6895
├── osr-common/         # 通用工具 (annotation, utils, exception, mybatisplus)
├── osr-framework/      # 框架配置 (security, shiro, config, websocket)
├── osr-system/         # 标准系统管理模块 (user/role/menu/dict domain)
├── osr-quartz/         # 定时任务 (job scheduler)
├── osr-openliststrm/   # ★ 核心业务，新功能几乎都写在这里 (17个子包，见下)
├── osr-web/         # Vue 3 前端 (Vite + Pinia + Vuetify 3 + PWA)
├── Dockerfile.backend    # Java 25 JRE + --enable-preview
├── Dockerfile.frontend   # Node 20 build → Nginx Alpine
├── docker-compose.yml    # MySQL 8.0 + backend + frontend
└── nginx.conf            # SPA + API proxy + WebSocket proxy
```

`osr-openliststrm` 按功能域分包（17 个）：
`api/ config/ controller/ enums/ helper/ monitor/ mybatisplus/ openai/ orphan/ pt/ rename/ req/ scrape/ service/ task/ tg/ tmdb/ upload/`

## WHERE TO LOOK
| 任务 | 位置 | 备注 |
|------|------|------|
| STRM 生成 | `osr-openliststrm/src/main/java/com/osr/openliststrm/` | task/, helper/, tmdb/, rename/ |
| 文件夹同步 | `osr-openliststrm/src/main/java/com/osr/openliststrm/` | api/, upload/, service/ |
| Telegram Bot | `osr-openliststrm/src/main/java/com/osr/openliststrm/tg/` | bot commands & handlers |
| 刮削 | `osr-openliststrm/src/main/java/com/osr/openliststrm/scrape/` + `tmdb/` | TMDb 刮削、文件删除 |
| 定时任务 | `osr-openliststrm/src/main/java/com/osr/openliststrm/task/` + `osr-quartz/` | 自定义 task + job |
| 重命名一致性检查 | `osr-openliststrm/src/main/java/com/osr/openliststrm/orphan/` | 孤儿扫描、清理、忽略 |
| PT 订阅管理 | `osr-openliststrm/src/main/java/com/osr/openliststrm/pt/` | downloader/indexer/subscription/media server |
| 安全/认证 | `osr-framework/src/main/java/com/osr/framework/security/` + `shiro/` | Shiro + JWT |
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
- **Shiro + JWT**: 无状态认证，Shiro 管理权限，JWT 传递 token
- **Java 25 Preview**: 编译/测试/运行均带 `--enable-preview` (虚拟线程/结构化并发)
- **FastJSON2**: 统一使用 FastJSON2 做 JSON 序列化
- **密码加密**: 使用 Cipher 加密存储敏感配置 (DB_PASSWORD 等)；密钥与连接信息走 `.env` (见 `.env.example`)，不要硬编码或提交进仓库
- **前端**: unplugin-auto-import + unplugin-vue-components 自动导入，`@` 指向 `src/`
- **`*Plus` 实体 mock 打桩注意**: `mybatisplus/domain/` 下的 `*Plus` 实体只有 `@Getter @Setter`，没有自己的 `equals()`/`hashCode()`，继承的是 `BaseEntity`（`@Data`）只比较 `createTime`/`updateTime`/`params` 的浅层 equals——不同 id 的两个未落库实例会被判定为"相等"。同一测试方法里对同一 mock 方法用两个不同的 `*Plus` 实例做参数匹配时，必须用 `ArgumentMatchers.same()`/`eq()` 显式按引用区分，不要依赖默认 equals，否则会在 `when()` 调用处炸出令人迷惑的异常（参考 `osr-openliststrm/src/test/java/com/osr/openliststrm/pt/subscription/SearchSupplementServiceTest.java:95-98`）
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
- 后端 Java 异常写在 `/data/logs/sys-error.log`，**不在 docker stdout**（stdout 只有启动 banner）。排查启动失败：`docker cp osr-backend:/data/logs ./tmp` 后看 `sys-error.log`；容器反复重启时先 `docker update --restart=no osr-backend && docker restart osr-backend` 让它崩溃后停住再读日志。
