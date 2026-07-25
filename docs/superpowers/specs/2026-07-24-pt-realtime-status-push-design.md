# 订阅与下载记录实时状态推送设计

**日期**：2026-07-24
**作者**：Jack（与 Claude 协作）
**前置阅读**：
- `docs/superpowers/specs/2026-07-21-pt-subscription-download-design.md`（订阅/推送/下载追踪整体链路）
- `docs/superpowers/specs/2026-07-23-subscription-create-search-backfill-design.md`（"瘦调度类"拆分模式的原始设计）

## 1. 背景与目标

### 1.1 问题

订阅列表页（`ptSubscription/index.vue`）展示每个订阅的"上次命中时间"（`lastMatchTime`），下载记录页（`ptDownloadRecord/index.vue`）展示每条记录的状态和下载进度条。这两处数据目前都只在用户手动刷新（`base.getList()`）或轮询定时器（若有）触发时才更新，用户看不到"种子刚刚推送成功""下载进度从 30% 涨到 80%""刚命中一次新种子"这些实时变化，只能反复点刷新。

### 1.2 目标

后端在下列四个状态变化点各推一条 WebSocket 消息，前端订阅列表页和下载记录页收到后原地patch本地列表数据，不用整页刷新：

1. `DownloadTrackService.markDownloading()`：下载进度更新
2. `DownloadTrackService.complete()`：下载完成
3. `DownloadTrackService.fail()`：下载失败
4. `SubscriptionEngine.handleGroup()` 末尾 `sub.setLastMatchTime(...)`：订阅命中时间更新

### 1.3 成功标准

1. 下载记录页打开时，某条记录进度变化能在下一轮 `DownloadTrackTask` 轮询后（不用手动刷新）在页面上看到进度条变化。
2. 订阅列表页打开时，某个订阅被 RSS/补搜命中后，"上次命中时间"能自动更新。
3. `DownloadTrackService`、`SubscriptionEngine` 仍然是纯构造器注入、可用 mock 直接单测的类——新增的推送调用不能让它们的现有测试失败，也不能引入需要 Spring 容器才能实例化的新依赖。
4. 一个客户端连接异常（网络抖动、断开、慢客户端）不能影响其他连接收到消息，也不能让后端的状态推进逻辑本身抛异常。
5. 前端断线后能自动重连；token 失效时不进入无限重连死循环。

### 1.4 范围限定

只做订阅列表页 + 下载记录页这两处的状态推送；不做全站通用的 WebSocket 消息总线抽象；不推送给"进度详情弹窗""搜索匹配日志弹窗"（这两处仍是打开时一次性拉取，参见第 8 节）。

## 2. 架构

### 2.1 为什么不新增 WebSocket 配置类

`ruoyi-framework/src/main/java/com/ruoyi/framework/config/WebSocketConfig.java` 已经注册了 `ServerEndpointExporter` 这个 Bean，它的职责是"扫描并注册所有 `@ServerEndpoint` 类"，不关心具体端点是谁。`ruoyi-admin` 下现有的 `LogWebSocket`（`/websocket/log/{logType}`）就是复用这个 Exporter 的例子——新增端点只需要新写一个 `@ServerEndpoint` + `@Component` 类，不需要碰 `WebSocketConfig`。本设计照此办理，新增 `PtStatusWebSocket`，`WebSocketConfig` 不改。

### 2.2 端点放在哪个包

`javax.websocket`（`jakarta.websocket`）容器为每个连接新建一个端点类实例，不是 Spring 管理 bean 的单例语义——这一点 `LogWebSocket` 已经用"实例字段 `tailer`/`executorService`/`closed` 各自持有每个连接自己的状态，`@PostConstruct` 把 `@Autowired` 依赖转存成静态字段供跨实例访问"这套写法处理过。本设计沿用同样的写法，新建：

`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/ws/PtStatusWebSocket.java`

新开一个 `pt/ws/` 子包（而非塞进 `pt/subscription/` 或 `pt/task/`），因为这个端点同时服务订阅和下载记录两类事件，不属于其中任何一个既有子包。

### 2.3 数据流

```
DownloadTrackService.markDownloading(record, progress)
DownloadTrackService.complete(record, downloader)
DownloadTrackService.fail(record, reason)
        │  （写库成功之后，各自追加一行）
        ▼
PtStatusWebSocket.pushDownloadEvent(record, state, progress, failReason)   [静态方法，无 Spring 依赖]
        │
        ├─▶ 拼一个 JSON 字符串（FastJSON2）
        ├─▶ 遍历静态维护的 Session 集合，逐个 sendText
        │       单个 session.sendText 抛异常（客户端已断开等）只记 debug 日志、
        │       从集合里移除该 session，不影响其余 session 收到消息，
        │       更不会向上抛出影响 DownloadTrackService 的状态推进主流程
        ▼
（DownloadTrackService 方法继续往下执行，不等待、不关心推送是否送达成功）


SubscriptionEngine.handleGroup(...) 末尾
    sub.setLastMatchTime(new Date());
    subscriptionService.updateById(sub);
        │  （写库成功之后，追加一行）
        ▼
PtStatusWebSocket.pushSubscriptionEvent(sub)   [同一个静态类，另一个静态方法]


浏览器（订阅列表页 / 下载记录页）
        │
        ▼
usePtStatusSocket({ onDownload, onSubscription })   [openlist-web/src/composables/usePtStatusSocket.ts]
        │
        ├─▶ new WebSocket(`${proto}://${host}/websocket/pt/status?token=${Cookies.get('token')}`)
        ├─▶ onmessage：JSON.parse → 按 type 分发给调用方注册的回调
        ├─▶ onclose 且非鉴权失败：3 秒后重连（与 realtime.vue 现有策略一致）
        └─▶ onUnmounted 时主动 close，清定时器
```

### 2.4 关键设计取舍

- **静态方法直调，而非再套一层"瘦调度类"**：`docs/.../2026-07-23-...-design.md` 里"瘦调度类"（`SubscriptionSearchOnCreateTrigger`）存在的原因，是把"依赖 Spring 容器才能拿到的 `TaskScheduler`"从可单测的 `*Service` 里剥离出去。本次推送走的是另一种早已在本项目验证过的模式——`TgHelper.sendMsg(msg)`：一个纯静态方法、没有构造器/字段注入，`DownloadTrackService.notifySafely()` 直接静态调用它。`PtStatusWebSocket` 的静态广播方法同理：它不依赖任何 Spring bean（Session 集合是它自己的静态字段，序列化用 FastJSON2 静态方法），单测环境下裸调用也不会抛异常（集合为空就是简单地空跑一轮 for 循环），不需要额外包一层调度壳子。
- **广播方法自己吞异常，调用方不用再包 try/catch**：`TgHelper.sendMsg` 因为是外部网络调用（Telegram API），失败是常态，所以调用方 `notifySafely` 必须包 try/catch。而 `PtStatusWebSocket` 的广播是本地内存操作＋WebSocket 帧发送，唯一可能抛异常的地方是"某个客户端连接已经不可用了"——这个异常的正确处理方式是"记日志＋从集合摘除这个 session＋继续下一个 session"，属于广播方法自己的内部职责，不应该冒泡给调用方决定怎么处理。因此 `pushDownloadEvent`/`pushSubscriptionEvent` 内部对每个 session 的发送单独 try/catch，方法签名整体不抛受检异常，`DownloadTrackService`/`SubscriptionEngine` 侧新增的调用是干净的一行，不用额外包装。
- **鉴权只做 token 合法性校验，不做细粒度权限校验**：翻查 `pt/controller` 现有 REST 接口没有一个标了 `@RequiresPermissions`（PT 模块目前是"登录即可用"，没有为订阅/下载记录定义独立的菜单权限点）。`LogWebSocket` 额外校验 `monitor:log:view` 是因为日志查看有专门的菜单权限点；PT 模块没有对应的权限点可查，所以 `PtStatusWebSocket` 的鉴权对齐 REST 接口的实际门槛——只校验 JWT 有效且未过期（`JwtTokenUtil.isTokenExpired`），拿到非法/过期 token 时发送 `"unauthorized"` 纯文本控制帧后关闭连接，写法照抄 `LogWebSocket.onOpen`。
- **广播不做房间/订阅粒度过滤，全量广播给所有已连接客户端**：当前场景下同时在线看这两个页面的通常是管理员自己（本系统是单用户/少数管理员的自用系统，不是多租户 SaaS），按 `subId` 做服务端过滤的复杂度收益不成正比。前端拿到全量事件后自己按当前页面关心的 `subId`/`type` 过滤即可（见 2.3 数据流），如果以后出现大量并发客户端导致带宽问题，再收敛为按需订阅（YAGNI）。
- **消息格式用扁平 JSON，一个 `type` 字段区分两种事件，不做通用消息总线**：`{"type":"download",...}` / `{"type":"subscription",...}`。两种事件字段不同，用一个顶层 `type` 判断分支足够，没有必要抽象出通用的 envelope/payload 嵌套结构或事件注册表——这正是范围限定里明确不做的"全站通用 WebSocket 消息总线"。
- **前端每个页面独立开关连接，不做跨页面单例复用**：订阅列表页和下载记录页是两个独立路由，不会同时挂载在同一个浏览器 tab 里（PC/移动端也是二选一渲染），仿照 `realtime.vue` 现有做法——组件 `onMounted` 连接、`onUnmounted` 断开即可，没有必要为一个大概率不会发生的"多个消费者共享同一条连接"场景引入引用计数的单例连接管理。

## 3. 数据模型改动

无。不新增字段、不新增表。`PtDownloadRecordPlus` 已有 `subId`/`episode`/`id`/`state`/`progress`/`failReason` 字段，`PtSubscriptionPlus` 已有 `id`/`lastMatchTime` 字段，推送消息直接从这些既有字段取值拼 JSON。

## 4. 后端组件改动清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `pt/ws/PtStatusWebSocket.java` | 新建 | `@ServerEndpoint("/websocket/pt/status")` + `@Component`。实例方法 `onOpen`（校验 token，通过则把 `Session` 加入静态 `ConcurrentHashMap.newKeySet()`）、`onClose`/`onError`（从集合移除）；静态方法 `pushDownloadEvent(PtDownloadRecordPlus record, String state, Double progress, String failReason)`、`pushSubscriptionEvent(PtSubscriptionPlus sub)`，内部用 FastJSON2 `JSONObject` 拼消息，遍历静态 Session 集合逐个 `sendText`，单个 session 异常只记 debug 日志并从集合摘除，不外抛 |
| `pt/task/DownloadTrackService.java` | 改动 | `markDownloading()` 末尾追加 `PtStatusWebSocket.pushDownloadEvent(record, STATE_DOWNLOADING, progress, null)`；`complete()` 在"条件更新命中"分支追加 `pushDownloadEvent(record, STATE_COMPLETED, 1.0, null)`；`fail()` 在"条件更新命中"分支追加 `pushDownloadEvent(record, STATE_FAILED, null, reason)`。三处都放在 `recordService.update`/`updateById` 成功之后，与现有 `notifySafely(...)` 紧邻，风格一致 |
| `pt/subscription/SubscriptionEngine.java` | 改动 | `handleGroup()` 里 `subscriptionService.updateById(sub)` 之后追加 `PtStatusWebSocket.pushSubscriptionEvent(sub)` |

不改动：`WebSocketConfig`、`SubscriptionMatcher`、`DownloadRecordState`/`SubscriptionEpisodeState` 枚举、任何 Controller、任何数据库脚本。

## 5. API

### 5.1 WebSocket 端点

```
GET /websocket/pt/status?token={jwt}
```

- 无 `/api` 前缀，与 `LogWebSocket` 的 `/websocket/log/{logType}` 保持同一层级，Nginx `websocket/` 路径已有的反向代理规则直接覆盖，不需要新增 Nginx 配置。
- 鉴权：query string 里的 `token` 经 `JwtTokenUtil.isTokenExpired` 校验，失败发送 `"unauthorized"` 文本帧后 `session.close()`（与 `LogWebSocket` 完全一致的写法，前端识别方式也一致）。
- 连接建立后不发送历史消息（不同于 `LogWebSocket` 会先推 200 行历史日志）——状态数据本来就是靠页面加载时的 REST 接口（`getPtSubscriptionListApi`/`getPtDownloadRecordListApi`）拿到全量快照，WS 只负责"快照之后的增量变化"，重连后如果错过了几条消息，下一次用户手动刷新或轮询时的 REST 请求会自然补齐，不需要 WS 层面做消息重放。

### 5.2 消息格式

下载事件：
```json
{"type":"download","downloadId":123,"subId":45,"episode":3,"state":"DOWNLOADING","progress":0.42}
{"type":"download","downloadId":123,"subId":45,"episode":3,"state":"COMPLETED","progress":1.0}
{"type":"download","downloadId":123,"subId":45,"episode":3,"state":"FAILED","failReason":"下载超过 24 小时仍未完成，判定为僵尸种子"}
```

订阅事件：
```json
{"type":"subscription","subId":45,"lastMatchTime":"2026-07-24 15:30:00"}
```

字段说明：`progress`/`failReason` 只在各自适用的 `state` 下出现，前端按需读取；`lastMatchTime` 用 `yyyy-MM-dd HH:mm:ss` 字符串（与现有列表接口返回的日期格式一致，前端不需要额外解析时区）。

## 6. 前端改动

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `composables/usePtStatusSocket.ts` | 新建 | 封装连接生命周期：`connect()` 建立 `ws://.../websocket/pt/status?token=...`（token 取 `Cookies.get('token')`，与 `request.ts`/`realtime.vue` 同源）；`onmessage` 内 `JSON.parse` 后按 `type` 分发；收到 `"unauthorized"` 走与 `realtime.vue` 相同的处理（清 token、跳登录页、不再自动重连）；普通 `onclose` 3 秒后重连；导出的 hook 签名 `usePtStatusSocket(handlers: { onDownload？: (e) => void; onSubscription？: (e) => void })`，内部 `onMounted`/`onUnmounted` 管理连接（若调用方本身不是在组件 `setup()` 中直接使用，则由调用方自行在合适的生命周期钩子里调用返回的 `disconnect()`） |
| `composables/usePtSubscription.ts` | 改动 | 引入 `usePtStatusSocket`，注册 `onSubscription` 回调：按 `subId` 在 `base.taskList.value` 里找到对应行，`Object.assign(row, { lastMatchTime: e.lastMatchTime })` 原地更新，不重新整页 `getList()` |
| `composables/usePtDownloadRecord.ts` | 改动 | 引入 `usePtStatusSocket`，注册 `onDownload` 回调：按 `downloadId` 在 `taskList.value` 里找到对应行，`Object.assign(row, { state: e.state, progress: e.progress, failReason: e.failReason })`；找不到对应行（比如该记录不在当前分页范围内）直接忽略，不做任何操作 |

前端不改动列表页 `.vue` 模板本身——`lastMatchTime`/`state`/`progress` 字段已经在模板里绑定，composable 层原地改了 ref 数组里对象的字段，Vue 的响应式会自动驱动视图更新，模板不需要新增任何标记或图标。

## 7. 测试计划

- `PtStatusWebSocket`：不写单测。这是货真价实的容器管理类（`@ServerEndpoint` 实例由 WebSocket 容器创建，不是 Spring 也不是普通 `new`），`onOpen`/`onClose` 依赖 `jakarta.websocket.Session`，脱离真实 WebSocket 容器很难有意义地单测，与 `LogWebSocket` 现状一致（该类也没有专属单测）。静态广播方法 `pushDownloadEvent`/`pushSubscriptionEvent` 的正确性通过 `DownloadTrackServiceTest`/`SubscriptionEngine` 相关测试间接覆盖调用点是否触发（见下）+ 手工联调验证实际推送内容。
- `DownloadTrackServiceTest`（已有测试文件基础上补充）：
  - 用 `MockedStatic<PtStatusWebSocket>` 校验 `markDownloading` 触发时机场景下调用了 `pushDownloadEvent(record, "DOWNLOADING", progress, null)`
  - `complete()` 条件更新命中 → 调用 `pushDownloadEvent(record, "COMPLETED", 1.0, null)`；条件更新未命中（并发场景）→ 不调用
  - `fail()` 条件更新命中 → 调用 `pushDownloadEvent(record, "FAILED", null, reason)`；未命中 → 不调用
- `SubscriptionEngine` 相关测试：`handleGroup` 成功推送分支追加断言，用 `MockedStatic<PtStatusWebSocket>` 校验调用了 `pushSubscriptionEvent(sub)`（`sub` 用 `same()` 精确匹配，避免 AGENTS.md 提到的 `*Plus` 浅层 `equals` 陷阱）
- 前端：`usePtStatusSocket.ts` 用 vitest + mock `WebSocket` 全局对象覆盖：收到 `download`/`subscription` 消息时正确分发给对应回调；收到 `"unauthorized"` 时清 token 且不重连；普通 `onclose` 后 3 秒内重新调用 `new WebSocket(...)`
- 手工联调：`docker compose up -d --build --no-deps backend frontend` 后，浏览器打开订阅列表页 + Chrome DevTools Network/WS 面板，触发一次 RSS 轮询或补搜，确认收到 `subscription` 消息且页面"上次命中时间"原地更新；下载记录页同理观察 `download` 消息与进度条联动

## 8. 不做的事情（本次范围之外）

- 不做全站通用 WebSocket 消息总线/事件注册中心抽象
- 不推送给"进度详情弹窗"（`showProgress`/`progress.value`，按集展示）和"搜索匹配日志弹窗"（`showSearchLogs`），这两处仍是打开时一次性拉取 REST 接口，用户需要重新打开弹窗才能看到最新数据
- 不做按 `subId`/`downloadId` 的服务端订阅粒度过滤，全量广播由前端自行按需过滤
- 不做消息重放/离线消息队列，断线期间错过的事件依赖用户下一次手动刷新或页面重新加载时的 REST 快照兜底
- 不做连接数上限、心跳保活（ping/pong）等生产级 WebSocket 治理机制，与 `LogWebSocket` 现状保持一致的复杂度水平
- 不改变 `DownloadTrackTask`/`RssPollTask` 等现有轮询节奏，推送只是轮询/补搜产生的状态变化的"旁路通知"，不是替代轮询的新数据源
