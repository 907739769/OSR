# PT 订阅统计仪表盘设计

**日期**：2026-07-24
**作者**：Jack（与 Claude 协作）
**前置阅读**：
- `docs/superpowers/specs/2026-07-21-pt-subscription-download-design.md`（订阅/推送/下载追踪整体链路）
- `docs/superpowers/specs/2026-07-24-pt-realtime-status-push-design.md`（订阅/下载记录页已有的 WebSocket 实时推送设计，本设计明确不复用）

## 1. 背景与目标

### 1.1 问题

PT 订阅下载link路已经有三张数据表在持续积累数据——`pt_download_record`（每次推送/完成/失败）、`pt_search_log`（每次过滤择优的通过/淘汰裁决）、`pt_subscription`（订阅本身的命中时间）——但用户目前只能通过两个明细列表页（`ptDownloadRecord`、订阅详情里的"搜索日志"弹窗）逐条翻阅。想知道"最近这个月下载量涨没涨""哪个索引器总是白搭""失败大多是什么原因""哪些订阅最活跃"，只能自己在明细页里数，没有汇总视图。

### 1.2 目标

新增一个只读的 PT 统计仪表盘页面，覆盖 5 个维度：

1. 近 30 天下载量趋势（推送/完成/失败按天计数）
2. 各索引器命中率/淘汰率
3. 平均下载耗时
4. 失败原因分布
5. Top 活跃订阅

### 1.3 成功标准

1. 打开页面后，5 个维度的数据全部来自后端聚合接口，前端不做整表拉取后本地统计（避免把全量 `pt_download_record`/`pt_search_log` 传到浏览器）。
2. 新增的统计接口不引入新表、不新增字段、不改动现有三张表的读写路径（`SubscriptionEngine`/`DownloadTrackService`/`SearchLogService` 都不用改动）。
3. 前端不引入新图表依赖，复用项目已有的 `echarts`（`openlist-web/package.json` 已声明 `"echarts": "^5.6.0"`，现有 `views/dashboard/desktop.vue` 已按需引入 pie 图，本设计追加 line/bar 引入，不加整包依赖）。
4. 单个订阅/索引器的数据缺失或口径极端（比如某索引器从未产生日志）不能让整个页面报错，只影响对应的图表分区显示"暂无数据"。

### 1.4 范围限定

- 只做只读统计展示，不做导出（PDF/Excel）、不做自定义时间范围选择器（用固定挡位）。
- 不做实时推送。订阅列表页/下载记录页的实时状态已经有独立的 WebSocket 设计（见前置阅读第 2 篇），本仪表盘是"事后汇总视图"，不是"实时监控大屏"，用户打开页面时拉一次 + 手动刷新按钮即可，没有必要为统计聚合接口维护一条新的 WebSocket 连接（这类聚合查询本身有一定 IO 成本，高频推送反而是负担）。
- 不新增数据库表或字段，包括不做"统计结果缓存表"——数据量级评估见 2.2，认为没有必要。
- 不改动 `pt_search_log` 的保留策略（每订阅最多 200 条），本设计的索引器命中率统计要在这个既有约束下工作，见 2.2 的口径说明。

## 2. 架构

### 2.1 数据来源

| 维度 | 数据来源 | 关键字段 |
|---|---|---|
| 下载量趋势 | `pt_download_record` | `pushed_time`、`state` |
| 索引器命中率/淘汰率 | `pt_search_log` + `pt_indexer`（补名称） | `indexer_id`、`accepted`、`create_time` |
| 平均下载耗时 | `pt_download_record`（`state=COMPLETED`） | `pushed_time`、`completed_time` |
| 失败原因分布 | `pt_download_record`（`state=FAILED`） | `fail_reason` |
| Top 活跃订阅 | `pt_download_record` 分组 + `pt_subscription` 补标题 | `sub_id`、`state` |

三张表结构、枚举值均已读过（`PtDownloadRecordPlus`/`PtSearchLogPlus`/`PtSubscriptionPlus`），不需要新增字段：
- `fail_reason` 只由 `DownloadTrackService.fail()` 写入，且只有两种固定文案（"下载器中已找不到该种子…"、"下载超过 N 小时仍未完成，判定为僵尸种子"，N 是常量不是变量插值），直接按原始字符串 `GROUP BY` 就是干净的小基数分布，不需要额外做原因归一化/正则分桶。
- `pt_search_log.reason` 字段是淘汰原因，本设计的"失败原因分布"用的是下载记录的 `fail_reason`（下载/种子层面的失败），不是搜索日志的淘汰原因（过滤层面的淘汰，那是另一个概念，属于"为什么这个种子没被选中"而不是"为什么下载失败"），两者不混用。

### 2.2 后端聚合方式：MyBatis-Plus QueryWrapper，不用原生 SQL/XML Mapper

**决策**：全部用 `QueryWrapper.select(原生表达式).groupBy(...)` + `IService.listMaps(...)` 完成聚合，不新建 XML Mapper，不用 JdbcTemplate 写原生 SQL。

**理由**：
1. `ruoyi-openliststrm` 模块的既有 ANTI-PATTERN 明确"不要混用 XML Mapper 和 MP BaseMapper（本模块只用 MP）"。新增一个 XML Mapper 只为了统计聚合，会在模块里开一个不一致的先例。
2. 现有 `OpenlistDashboardRestController` 已经验证了这个模式在本项目里可行：`Wrappers.<T>query().select("status as status, count(*) as count").groupBy("status")` 配合 `service.listMaps(wrapper)` 拿到分组计数，本设计的日按天分组、按索引器分组、按失败原因分组都是同一模式的直接延伸（区别只是 `select` 里的表达式从 `status` 换成 `DATE_FORMAT(pushed_time,'%Y-%m-%d')`、`indexer_id`、`fail_reason`）。这些表达式是后端硬编码的字符串常量，不拼接任何用户输入，没有注入风险。
3. MyBatis-Plus 的结构化 API（`eq`/`groupBy(SFunction)`）不支持 `DATE_FORMAT`、`AVG(TIMESTAMPDIFF(...))` 这类函数表达式，只能退化到 `QueryWrapper` 的原生字符串 `select`/`groupBy`——这本身就是 MP 处理"分组聚合"场景的标准写法，不是绕过 MP。

**性能考量（数据量级评估）**：
- 这是个人自建/小规模自托管场景（单用户或极少数用户共用一套部署），`pt_subscription` 订阅数量级是几十到上百；`pt_search_log` 每订阅硬性保留 ≤200 条（`SearchLogService.RETENTION_PER_SUBSCRIPTION`），即使 100 个订阅同时在跑，总量上限约 2 万行；`pt_download_record` 没有保留策略上限，但增长速度受"每次 RSS 轮询/补搜命中才插入一行"约束，长期运行（按天算）大概率在几千到低万行量级。
- 现有索引：`pt_download_record` 有 `idx_sub_episode(sub_id,episode)`、`idx_state(state)`；`pt_search_log` 有 `idx_sub_id(sub_id,id)`。均没有覆盖 `pushed_time`/`indexer_id`/`fail_reason` 的分组查询，这些查询会退化为全表扫描——但按上面的量级估算（几千至两万行），MySQL 8 对这个体量做 `GROUP BY` 全表扫描是毫秒级，不需要为一个"偶尔打开看一眼"的统计页面新增索引或做定时预聚合表。如果未来订阅规模数量级上涨（比如上千订阅、数十万下载记录），再按需加 `idx_pushed_time`/`idx_indexer_id` 即可，属于 YAGNI。
- 因此不引入统计结果缓存表、不加定时预聚合任务，接口每次调用直接查库，响应时间可控。

### 2.3 API 组织：拆分成多个只读端点，而非一个大而全的接口

**决策**：仿照 `dashboard.ts` 现有模式（`getDashboardStatsApi`/`getCopyStatsApi`/`getStrmStatsApi`/`getRenameStatsApi` 分开），新增 5 个独立的 GET 端点，而不是一个返回全部 5 维度数据的巨型接口。

**理由**：
- 与现有 `OpenlistDashboardRestController` 的拆分风格保持一致，前端可以 `Promise.all` 并行加载，某一个维度查询失败/变慢不阻塞其余 4 个图表渲染（`desktop.vue` 现有的 `try/catch` per-chart 兜底模式可以直接复用）。
- 5 个维度的参数形状不同（趋势要 `days`，Top 订阅要 `days`+`limit`，索引器命中率不需要 `days`——见 2.1 的口径说明），拆分后每个接口的参数语义清晰，合并成一个接口反而需要把所有参数塞进一个大 DTO。

### 2.4 索引器命中率不做时间范围筛选

`pt_search_log` 本身按订阅保留 ≤200 条最近记录，不是按时间保留。如果再加一个"近 30 天"的时间筛选，对高频轮询的订阅（可能几天就把 200 条名额用完）和低频订阅（可能 200 条能覆盖好几个月）口径会不一致——同样选"近 30 天"，一个订阅可能只反映最近 2 天的真实情况（其余被过滤淘汰的记录早就被 30 天时间线覆盖但没被保留策略淘汰，是双重截断），另一个订阅完整覆盖 30 天。

**决策**：索引器命中率接口不加 `days` 参数，直接对现存的 `pt_search_log` 全量做分组统计——保留策略本身已经是"最近一段时间"的近似窗口，没必要叠加一层不一致的时间筛选。这一点在页面上通过分区说明文字"基于每订阅最近 200 条匹配记录"向用户挑明，避免误解成"近 30 天精确统计"。

### 2.5 前端页面归属：新增独立页面，不在现有 Dashboard 加分区

**决策**：新建独立页面 `openlist/ptStatsDashboard`，挂在 PT 模块菜单分组下，不往 `views/dashboard/` 现有的系统首页塞 PT 专属图表。

**理由**：
1. **权限边界不一致**：`/dashboard` 是 `constantRoutes` 里的常量路由（`router/index.ts` 第 34-38 行），不受 `sys_menu` 权限控制，所有登录用户都能看到；而 PT 模块的其余页面（`ptSubscription`/`ptIndexer`/`ptDownloadRecord` 等）都是走 `sys_menu` 的 `perms` 字段控制可见性（如 `openliststrm:ptDownloadRecord:view`）。把索引器命中率、失败原因这类 PT 运维细节塞进人人可见的首页，会让没有 PT 权限的用户也看到这些信息，权限模型上不一致。
2. **关注点分离**：现有 Dashboard 展示的是 COPY/STRM/Rename 三个通用任务模块的统计，PT 是一个独立的、可插拔的功能域（自建 PT 站点订阅不是每个部署都会用）。5 个新维度加进去会让首页信息过载，且与首页现有 3 张统计卡片 + 3 个饼图的既定布局风格（`el-row :gutter=16` 三栏）不匹配——PT 维度里有趋势线图、排行表格，跟首页"当日/昨日/全部"切换的饼图定位不同。
3. **与其余 PT 页面保持同构**：`ptSubscription`、`ptDownloadRecord` 等都是各自独立页面、独立菜单项，新增统计页面延续这个既有结构，用户心智负担最小（PT 模块下有"索引器、下载器、媒体服务器、订阅、过滤规则、下载记录、**统计**"7 个子页面，一目了然）。

### 2.6 路由与菜单归属

- 菜单：新增一条 `sys_menu` 记录，`parent_id=2070`（"PT下载管理"分组，`20260736-menu-categories.sql` 已建好），`menu_id` 用下一个可用值 `2071`（现有 PT 相关菜单用到 2061~2066，分组用到 2067~2070，`2071` 未被占用），`order_num=7`（组内排在"PT下载记录"之后）。
- 菜单图标：不能跟同组内其余 6 个已用图标（`fa fa-rss`/`fa fa-download`/`fa fa-server`/`fa fa-bookmark-o`/`fa fa-sliders`/`fa fa-list-ul`）重复，也不跟父分组自己的 `fa fa-bars` 重复（`20260737-fix-menu-group-icon-duplication.sql` 修过的坑）。选 `fa fa-bar-chart`（当前 `useMenuIcon.ts` 的 `iconMap` 里没有这个类名，需要在实现时补一条映射，指向 `@element-plus/icons-vue` 的 `TrendCharts` 或 `DataAnalysis` 图标组件，跟首页 Dashboard 用的 `Odometer` 区分开）。
- 前端路由：仿照其余 PT 页面在 `router/index.ts` 的 `componentMap` 里加一条 `'openlist/ptStatsDashboard/index'`。是否要 PC/移动端两套实现（`createDeviceView`）在 6.3 节说明。

## 3. 数据模型改动

无。不新增表、不新增字段、不新增迁移脚本中的 DDL（只有一条菜单 `INSERT`，见第 4 节）。全部统计数据来自 `pt_download_record`、`pt_search_log`、`pt_subscription`、`pt_indexer` 四张已有表的只读聚合查询。

## 4. 后端组件改动清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `pt/stats/PtStatsService.java` | 新建 | `@Service`，纯构造器注入 `IPtDownloadRecordPlusService`/`IPtSearchLogPlusService`/`IPtSubscriptionPlusService`/`IPtIndexerPlusService`，5 个只读方法，各自对应一个维度，方法体全部是 `QueryWrapper` 聚合 + Java 侧拼装 DTO，不含任何写操作 |
| `pt/stats/dto/PtStatsOverviewDTO.java` | 新建 | 总订阅数/活跃订阅数/下载记录总数/完成数/失败数/成功率/全局平均耗时(分钟) |
| `pt/stats/dto/PtStatsTrendPointDTO.java` | 新建 | 单日的 `date`/`pushedCount`/`completedCount`/`failedCount`/`avgDurationMinutes`（当日无完成记录则为 `null`，前端跳过该点不画线段） |
| `pt/stats/dto/PtStatsIndexerHitRateDTO.java` | 新建 | `indexerId`/`indexerName`/`acceptedCount`/`rejectedCount`/`hitRate`（0~1，分母为 0 时记 0 并置 `hasData=false`） |
| `pt/stats/dto/PtStatsFailReasonDTO.java` | 新建 | `reason`/`count` |
| `pt/stats/dto/PtStatsActiveSubscriptionDTO.java` | 新建 | `subId`/`title`/`season`/`mediaType`/`downloadCount`/`completedCount`/`failedCount`/`lastMatchTime` |
| `controller/api/PtStatsRestController.java` | 新建 | `@RestController`，`/api/openliststrm/pt-stats` 前缀，5 个 `@GetMapping`，只转调 `PtStatsService`，不含业务逻辑（遵循 `ruoyi-openliststrm/AGENTS.md` "不要在 Controller 中写业务逻辑" 的约定；本项目里 `OpenlistDashboardRestController` 把聚合逻辑直接写在 Controller 是历史遗留，pt 包内的既有服务——`SearchLogService`/`DownloadTrackService`——都遵循"Controller 瘦、Service 厚"，本设计延续 pt 包自己的规范，不参照那个例外） |
| `useMenuIcon.ts`（`openlist-web/src/composables/`） | 改动 | `iconMap` 新增 `'fa fa-bar-chart': TrendCharts`（或等效未占用图标），并 `import { TrendCharts } from '@element-plus/icons-vue'` |
| `router/index.ts`（`openlist-web/src/router/`） | 改动 | `componentMap` 新增 `'openlist/ptStatsDashboard/index'` 一条 |
| `views/openlist/ptStatsDashboard/index.vue` | 新建 | 页面本体，见第 6 节 |
| `api/openlist/ptStats.ts` | 新建 | 5 个 API 封装函数，见第 5 节 |
| `20260738-pt-stats-menu.sql`（`ruoyi-common/src/main/resources/sql/`） | 新建 | 新增 `sys_menu` 记录（`menu_id=2071`，`parent_id=2070`），`visible='0'`（前后端同批上线，直接可见，参照 `20260731-pt-download-record-menu.sql` 的先例） |

不改动：`SubscriptionEngine`、`DownloadTrackService`、`SearchLogService`、`pt_download_record`/`pt_search_log`/`pt_subscription`/`pt_indexer` 表结构、`views/dashboard/` 现有首页、`WebSocketConfig`。

## 5. API

统一前缀 `/api/openliststrm/pt-stats`，全部 `GET`，只读，不需要额外的权限注解（本项目 pt 模块 REST 接口现状是靠 `sys_menu.perms` 控权限、菜单可见性门控页面访问，接口层没有 `@RequiresPermissions`/`@PreAuthorize` 注解，本设计与既有的 `PtDownloadRecordRestController` 等保持一致，不额外引入新的权限校验方式）：

| 接口 | 参数 | 返回 |
|---|---|---|
| `GET /overview` | 无 | `Result<PtStatsOverviewDTO>` |
| `GET /trend` | `days`（默认 30，可选 7/30/90） | `Result<List<PtStatsTrendPointDTO>>` |
| `GET /indexer-hit-rate` | 无 | `Result<List<PtStatsIndexerHitRateDTO>>` |
| `GET /fail-reasons` | `days`（默认 30，语义同 `/trend`） | `Result<List<PtStatsFailReasonDTO>>` |
| `GET /top-subscriptions` | `days`（默认 30）、`limit`（默认 10，上限 50） | `Result<List<PtStatsActiveSubscriptionDTO>>` |

`days` 参数在 `/trend`、`/fail-reasons`、`/top-subscriptions` 三个接口里共用同一个前端全局挡位选择器（见 6.2），值域固定为 `7`/`30`/`90`（不做自定义任意天数，避免前端传入超大 `days` 触发全表无边界扫描——虽然按 2.2 的量级评估不至于出问题，但接口层还是显式白名单校验 `days` 只能是这三个值之一，非法值回退到 30）。

## 6. 前端改动

### 6.1 页面结构

`views/openlist/ptStatsDashboard/index.vue`，布局参照 `views/dashboard/desktop.vue` 的既有风格（`el-card` + `--osr-*` CSS 变量），但不复用 `desktop.vue` 本身（职责不同，独立文件）：

1. 顶部：全局时间挡位选择器（`el-radio-group`：近7天/近30天/近90天），驱动 `/trend`、`/fail-reasons`、`/top-subscriptions` 三个接口重新加载；`/overview`、`/indexer-hit-rate` 不受此挡位影响（原因见 2.2、2.4）。
2. 统计卡片行（`/overview`）：总订阅数、活跃订阅数、下载记录总数、成功率、平均下载耗时——沿用首页 `stat-card` 的卡片样式（图标+数值+标签）。
3. 下载量趋势（`/trend`）：ECharts 折线图，3 条线（推送/完成/失败），x 轴为日期。
4. 索引器命中率（`/indexer-hit-rate`）：ECharts 100% 堆叠横向条形图，每个索引器一行，通过/淘汰各占比例；`hasData=false` 的索引器（从未产生过日志）单独列出灰色"暂无数据"提示，不参与图表比例计算避免除零占位失真。
5. 失败原因分布（`/fail-reasons`）：ECharts 饼图，复用 `desktop.vue` 里 `renderChart`/`getColor` 的实现思路（同色系映射：完成/成功用绿、失败用红），但因为是独立页面文件，直接照抄这段逻辑到本页面（约 30 行，抽公共 util 收益不大，两处各自独立演化更简单，参照本项目一贯"调度壳子不共享、业务逻辑才抽"的取舍）。
6. Top 活跃订阅（`/top-subscriptions`）：`el-table`，不用图表——订阅标题是变长中文/英文文本（如 "怪奇物语 S04"），塞进条形图的类目轴标签容易换行错乱或截断，表格才能把标题、季号、完成/失败次数、上次命中时间这些异构信息完整展示；点击行可以后续跳转到 `ptDownloadRecord` 页面按 `subId` 筛选（本设计只列出这个可能性，不在本次实现，见第 8 节）。

### 6.2 状态管理：不用 composable，直接在组件内管理

**决策**：仿照 `views/dashboard/desktop.vue`（本项目里跟本页面形态最接近的先例：卡片+多图表，都是"打开时拉取只读聚合数据"），在 `<script setup>` 里直接管理各图表的 `ref`/加载函数，不新建 `composables/usePtStats.ts`。

**理由**：`composables/useTaskList.ts`/`useRecordList.ts` 这套复用是为"分页表格 + 搜索 + 增删改"这一类 CRUD 列表页设计的（`usePtDownloadRecord.ts` 就是这个模式），本页面没有分页、没有搜索表单、没有增删改，只有"挡位切换 → 重新拉取只读聚合数据"，跟 CRUD 列表的复用点对不上，硬套 composable 反而多一层不必要的抽象。

### 6.3 PC/移动端：只做 PC 端，不做移动端

**决策**：不在 `views-mobile/` 下建对应页面，`router/index.ts` 里这一条不用 `createDeviceView` 包装，直接 `() => import('@/views/openlist/ptStatsDashboard/index.vue')`。

**理由**：折线图、堆叠条形图在小屏上信息密度过高，体验上不如先做好 PC 端。项目里 `ptFilterConfig`、`renameConfig` 这两个"配置类/低频访问"页面同样没有移动端实现（`componentMap` 里可查到它们没走 `createDeviceView`），统计仪表盘跟这两个页面的访问模式类似（不是日常高频操作，更多是偶尔巡检），先例上可以照办。后续如果有移动端诉求，再补 `views-mobile/ptStatsDashboard/index.vue`，不影响本次设计。

## 7. 测试计划

对齐 `pt` 包既有测试风格（构造器注入 + mock，注意 `*Plus` 实体浅层 `equals` 陷阱——本设计的 mock 主要是 `List<Map<String,Object>>` 返回值，不涉及需要用 `same()`/`eq()` 区分实例的场景，风险较低）：

`PtStatsServiceTest`：
- `overview()`：mock `listMaps` 返回的分组结果，校验总数、成功率、平均耗时的算术是否正确；下载记录为空时返回全 0 而不是抛异常/除零。
- `trend(days)`：mock 分组结果只覆盖部分日期（比如 30 天里只有 5 天有数据），断言返回的 `List<PtStatsTrendPointDTO>` 补齐了没有数据的日期（`count=0`，`avgDurationMinutes=null`），而不是只返回有数据的 5 个点——前端折线图需要连续日期轴，缺口会被 ECharts 曲线插值成误导性的连线。
- `indexerHitRate()`：mock 多个索引器的 accepted/rejected 计数，校验 `hitRate` 四舍五入与除零分支（`acceptedCount=rejectedCount=0` 时 `hitRate=0`、`hasData=false`）；索引器在 `pt_indexer` 里存在但从未出现在 `pt_search_log` 里（新增索引器还没跑过）时也要正常出现在列表里（`hasData=false`），不能被静默漏掉。
- `failReasons(days)`：mock 两种固定 `fail_reason` 文案分布，校验计数与排序（按 `count` 降序）。
- `topSubscriptions(days, limit)`：mock 分组结果，校验 `limit` 生效、按下载次数降序；`subId` 在 `pt_subscription` 里查不到（订阅已被删除但历史下载记录还在）时要有兜底展示（标题显示"（订阅已删除）"），不能抛 NPE。

`PtStatsRestController`：只做参数校验层面的测试（`days` 非法值回退 30、`limit` 超过 50 截断），聚合逻辑已在 Service 层覆盖，不重复测。

前端：不新增 Playwright E2E（`ptFilterConfig`/`ptIndexer` 这类配置/展示页现状也没有专门的 E2E 覆盖，本页面延续现状）；手动验证图表在数据为空/单一维度全 0 时的"暂无数据"占位展示是否正常（这是历史上 `desktop.vue` 踩过的坑，`renderChart` 里已有对应处理，本页面照抄同一套判空逻辑）。

## 8. 不做的事情（本次范围之外）

- 不做导出（CSV/Excel/PDF）
- 不做自定义任意时间范围选择（只有 7/30/90 天三档）
- 不做移动端页面（见 6.3）
- 不做 WebSocket 实时推送（见 1.4、2.6）
- 不做统计结果的定时预聚合/缓存表（见 2.2 的数据量级评估结论）
- 不做"Top 活跃订阅"表格行点击跳转到下载记录页并自动带 `subId` 筛选（6.1 提到的后续可能性，本次只出表格，不做跳转联动）
- 不改 `pt_search_log` 的保留策略（200 条/订阅），也不因为本设计的统计需求而放宽这个上限
- 不新增独立的统计权限点（`openliststrm:ptStatsDashboard:view` 沿用标准菜单 `perms` 生成方式即可，不做比这更细粒度的"谁能看哪个图表"的权限拆分）
