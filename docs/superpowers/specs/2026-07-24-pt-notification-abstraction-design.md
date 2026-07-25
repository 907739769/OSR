# 通知渠道抽象设计

**日期**：2026-07-24
**作者**：Jack（与 Claude 协作）
**前置阅读**：
- `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/helper/TgHelper.java`（当前唯一通知渠道，本设计的重构对象）
- `docs/superpowers/specs/2026-07-21-pt-subscription-download-design.md`（PT 订阅整体链路，`DownloadTrackService`/`RssPollService` 的通知语义出自这里）

## 1. 背景与目标

### 1.1 问题

`TgHelper` 是一个纯静态工具类，内部硬编码了"读取 Telegram 配置 → 缓存 Bot 实例 → 调 `TgSendMsg` 发消息"的完整逻辑：

```java
public static void sendMsg(String msg) {
    OpenlistConfig config = SpringUtils.getBean(OpenlistConfig.class);
    ...
    getBot(token, userId).sendMsg(msg);
}
```

当前全仓库有 **5 个业务文件、6 个调用点**直接依赖这个静态方法（均已确认）：

| 文件 | 调用点数 | 是否包了 `try/catch` |
|---|---|---|
| `helper/AsynHelper.java` | 4 | 否（裸调用，异常会向上抛） |
| `monitor/WatchServiceMonitor.java` | 1 | 否（裸调用） |
| `pt/task/RssPollService.java` | 1（经 `notifySafely`） | 是 |
| `pt/task/DownloadTrackService.java` | 1（经 `notifySafely`） | 是 |
| `pt/subscription/SearchSupplementService.java` | 1（经 `notifySafely`） | 是 |

且有 **2 个测试文件**用 `Mockito.mockStatic(TgHelper.class)` 直接对静态方法打桩/断言：`DownloadTrackServiceTest`（3 处 `tg.verify(() -> TgHelper.sendMsg(...))`）、`SearchSupplementServiceTest`（5 处）。

这意味着：
1. 想接入企业微信、Bark、通用 Webhook 等新渠道，唯一的办法是继续往 `TgHelper.sendMsg()` 里塞 if-else，或者到处新增一个 `XxxHelper.sendMsg()` 再到处补调用——两条路都会让通知逻辑越来越乱。
2. `TgHelper` 是纯静态类，`TgSendMsg` 的创建、缓存、失败处理全部耦合在一起，无法在不依赖 Spring 容器的前提下单独单元测试（`SearchSupplementServiceTest` 等消费方测试都是靠 `mockStatic` 绕开这个问题，而不是真的测试 `TgHelper` 本身——事实上仓库里也确实没有 `TgHelperTest`）。

### 1.2 目标

把"发一条通知"这件事从"发一条 Telegram 消息"中解耦出来：新增 `INotifier` 接口与可插拔实现，`TgHelper` 现有逻辑迁移为其中一个实现（`TgNotifier`），并新增至少一个新实现（`WebhookNotifier`）验证这套抽象确实可扩展。**同时保证上述 6 个调用点、2 个测试文件一行都不用改**。

**范围限定**：本次只做接口 + `TgNotifier` + 一个新实现（通用 Webhook）+ 多渠道分发器，不做企业微信/Bark 的专门实现，不做"渠道管理"后台页面，不改 `TgSendMsg`/`StrmBot`/`TgBotRegister` 等 Telegram Bot 双向交互相关代码（这些是 Bot 收指令的功能，与"发通知"是两回事）。

### 1.3 成功标准

1. 新增一个通知渠道时，只需新增一个实现 `INotifier` 的类并交给 Spring 管理，不需要改动分发逻辑，也不需要改动任何现有调用点。
2. `AsynHelper`、`WatchServiceMonitor`、`RssPollService`、`DownloadTrackService`、`SearchSupplementService` 5 个文件的 `TgHelper.sendMsg(msg)` 调用代码原封不动。
3. `DownloadTrackServiceTest`、`SearchSupplementServiceTest` 两个测试文件的 `mockStatic(TgHelper.class)` 断言原封不动、全部通过。
4. 某一个渠道发送失败（如 Webhook 目标不可达）不影响同一次调用中其他渠道正常发出；也不会让调用方感知到异常（维持"发通知失败不影响主流程"的现有语义）。
5. Telegram 未配置、Webhook 未配置时，两者均不发送、不报错——与当前"`token`/`userId` 为空则静默跳过"的行为一致，多渠道场景下"只配了其中一个"是最常见情况，必须默认支持。

## 2. 架构

### 2.1 新增组件与现有链路的关系

新增一个包 `com.ruoyi.openliststrm.notify`（不放进 `tg/`，因为 `tg/` 目前是 Telegram Bot 双向交互专用包，塞一个"渠道无关"的抽象进去语义会拧巴；也不放进 `helper/`，因为 `helper/` 现有的都是纯静态工具类，这次要新增的是一组"接口 + 多个 `@Component` 实现 + 一个持有 `List` 的聚合 Bean"，是一个小的对象模型，不是工具函数）：

```
notify/
├── INotifier.java          # 接口：void send(String message)
├── TgNotifier.java         # @Component，承接 TgHelper 原有逻辑
├── WebhookNotifier.java    # @Component，新增，验证可扩展性
└── NotifierManager.java    # @Component，持有 List<INotifier>，逐个分发
```

`TgHelper` 保留，但内部逻辑清空，改为委托：

```
TgHelper.sendMsg(msg)                       [helper/，静态方法，签名不变]
        │
        ▼
SpringUtils.getBean(NotifierManager.class).send(msg)
        │
        ├─▶ TgNotifier.send(msg)       （token/userId 均非空才真正发送，否则 no-op）
        │       try/catch 包裹，失败只记 warn，不向上抛
        │
        └─▶ WebhookNotifier.send(msg) （webhook.url 非空才真正发送，否则 no-op）
                try/catch 包裹，失败只记 warn，不向上抛
```

`NotifierManager` 内部对每个 `INotifier.send()` 单独 try/catch，任一渠道抛异常只记录、不影响其余渠道，也不会让 `NotifierManager.send()` 本身抛异常——`TgHelper.sendMsg()` 因此维持"调用方不需要关心通知是否成功"的现有隐含契约，5 个业务调用点的写法完全不用变（3 个原本自己包了 `notifySafely`，容错逻辑现在其实是双重保险，冗余但无害；2 个裸调用的（`AsynHelper`、`WatchServiceMonitor`）过去依赖"`TgHelper.sendMsg` 事实上不会抛业务异常"这个隐含前提，重构后这个前提由 `NotifierManager` 显式保证，行为更可靠而非变化）。

`TgNotifier` 内部结构基本照搬 `TgHelper` 现有实现（缓存 `TgSendMsg` 实例、token/userId 变化时重建），差别是：
- 构造函数直接注入 `OpenlistConfig`（`@Component` 正常走 Spring 依赖注入），不再需要 `SpringUtils.getBean(OpenlistConfig.class)` 这个只有纯静态类才需要的绕行写法——这一点与 `EmbyClient`/`AsynHelper` 等现有 `@Component`/`@Service` 类的写法一致。
- 缓存字段从 `static volatile` 改为普通实例字段：`TgNotifier` 本身是单例 Bean，不再需要"整个 JVM 只有一份"的静态缓存语义，普通实例字段配合原有的 `synchronized` 双重检查即可，行为不变。

`WebhookNotifier` 是全新实现，用来验证"新增渠道只需要新增一个类"：
- 构造函数注入 `OpenlistConfig` + 复用 `HttpClientConfig.sharedOkHttpClient()` 这个既有共享连接池 Bean（与 `EmbyClient`/`TorznabClient`/`QbittorrentClient` 完全同款写法，见 `pt/media/EmbyClient.java`）。
- `send(message)`：读取 `openlist.notify.webhook.url`，为空直接返回；非空则用 FastJSON2 拼 `{"text": message}` 作为请求体，`POST` 到该 URL，`Content-Type: application/json`；非 2xx 响应或 `IOException` 记 `warn` 日志、方法内部吞掉，不抛出（与 `TgSendMsg.sendMsg()` 捕获 `TelegramApiException` 后不外抛是同样的设计，保证 `INotifier.send()` 这个契约对所有实现都"绝不抛异常"，`NotifierManager` 的 try/catch 只是兜底防御，不依赖它）。
- 消息体只做最基础的 `{"text": ...}` 包装，不做企业微信/飞书群机器人要求的 `msg_type` 包裹、不做 Bark 的 URL path 拼接——这些是特定服务商的私有协议，属于"以后要接哪个就再加一个 `XxxNotifier`"的范畴，本次只验证抽象本身，不做穷举（见第 8 节）。

### 2.2 关键设计取舍

- **`TgHelper` 保留门面、不重命名、不改调用点，而不是直接替换 6 个调用点**：`DownloadTrackServiceTest` 与 `SearchSupplementServiceTest` 里一共有 8 处 `Mockito.mockStatic(TgHelper.class)` + `verify(() -> TgHelper.sendMsg(...))`——这是实测到的硬约束，不是可选项。`mockStatic` 是按类名和方法签名拦截的，只要 `TgHelper.sendMsg(String)` 这个静态方法还在，测试完全不用动；如果替换调用点为直接注入 `NotifierManager`，这 8 处断言、以及 `AsynHelper`/`WatchServiceMonitor`/`RssPollService`/`DownloadTrackService`/`SearchSupplementService` 5 个文件的构造函数（部分需要新增字段注入）都要跟着改，收益（去掉一层委托）远小于成本（扩大改动范围、重新验证 5 个文件 + 2 个测试文件）。`TgHelper` 从"业务实现"降级为"兼容门面"，职责很薄（一行委托），维护成本可以忽略。
- **`TgHelper` 类名不改**：一个可选方案是保留门面但改名成语义更准确的 `NotifyHelper`，让"发通知"这个动作名副其实。放弃这个方案的原因同上——类名是 `mockStatic` 断言的一部分，改名等价于替换调用点，同样要动 5 个文件 + 2 个测试文件。用 Javadoc 说明"`TgHelper.sendMsg` 现在会分发到所有已启用的通知渠道，不只是 Telegram"即可消解命名上的误导，新代码不应该再新增对它的调用（见下条）。
- **新调用点直接注入 `NotifierManager`，不再新增对 `TgHelper` 的调用**：`TgHelper` 是历史包袱，只为兼容存量调用点保留，本身不含任何业务逻辑；以后任何新代码需要发通知，应该走标准的 Spring 依赖注入（构造函数注入 `NotifierManager`，调 `.send(msg)`），而不是继续调静态方法——这样新代码天然可测（`NotifierManager` 可以直接 `new NotifierManager(List.of(mockNotifier))` 单测，不需要 `mockStatic`）。
- **`INotifier` 接口只有 `send(String message)` 一个方法，启用判断放在各实现内部，而不是接口暴露 `isEnabled()`**：这与 `TgSendMsg.sendMsg()` 现有的"`token`/`userId` 为空就直接 `return`"是同一套语义，`NotifierManager` 不需要关心某个渠道是否配置齐全，只管无脑分发给所有已注册的 `INotifier`；哪个渠道自己没配置好，自己内部判断后 no-op。好处是新增渠道时只需要一个类自洽，不需要同时维护接口方法和调用方的判断逻辑两处。
- **多渠道"同时启用"，而非互斥单选**：不新增"选择通知渠道"这个配置项/下拉框。理由：Telegram 现有的启用方式就是"`token`/`userId` 两个都填了才生效"，本质上是"配置存在即启用"，多渠道场景下这个模式天然支持"想同时开 Telegram 和 Webhook 就都填，只想开一个就只填一个"，不需要额外引入一个"当前激活渠道"的状态位——这个状态位反而是隐患：如果它和"某渠道配置是否为空"脱节（比如选中了 Webhook 但 Webhook URL 后来被清空），谁生效谁不生效会很难从界面上看明白。`NotifierManager` 遍历所有 `INotifier`、各自内部判断是否要发，是当前信息量最小、心智负担最低的方案。
- **`Webhook` 而不是"企业微信"或"Bark"作为验证用的第二渠道**：企业微信群机器人要求 `{"msgtype":"text","text":{"content":...}}` 这种私有 JSON 结构，Bark 是 GET 请求把消息塞进 URL path；这两个都是"给具体服务商写适配"，不是"验证抽象"本身要做的事。通用 Webhook（POST 一个最简单的 `{"text": message}` JSON）不依赖任何特定服务商约定，足够验证"新增一个 `@Component implements INotifier` 就能接入新渠道、且不用改分发逻辑和调用点"这件事，同时给后续接企业微信/Bark 的人一个可以照抄的模板（构造注入 `OpenlistConfig` + 共享 `OkHttpClient`，`send()` 内部判空、拼包体、发请求、吞异常）。
- **`NotifierManager` 用构造函数注入 `List<INotifier>`，不做渠道注册表/优先级排序**：Spring 会自动把所有 `INotifier` 实现类收集进这个 `List`（`@Component` 标注即可，不需要手工在某处 `register()`）。各渠道之间没有先后依赖、没有"用了 A 就不能用 B"的关系，遍历顺序不重要，因此不引入 `@Order` 或独立的注册表类——加了也只是徒增复杂度。

## 3. 数据模型改动

新增 1 个 `sys_config` 配置项，不新增表、不新增字段。新建升级脚本 `ruoyi-common/src/main/resources/sql/2026-07-24-notify-webhook-config.sql`，沿用 `20260718-add-openlist-configs.sql` 的 `INSERT ... SELECT ... WHERE NOT EXISTS` 幂等写法（按 `config_key` 判断是否已存在，不依赖显式主键，与该文件风格一致）：

```sql
INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '通知Webhook地址', 'openlist.notify.webhook.url', '', 'N', 'admin', '2026-07-24 00:00:00', '通用 Webhook 通知地址，POST JSON {"text": message}，留空则不启用'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.notify.webhook.url');
```

`openlist.tg.token` / `openlist.tg.userid` 两个既有配置项不改，`TgNotifier` 直接复用。

## 4. 后端组件改动清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `notify/INotifier.java` | 新建 | 接口，唯一方法 `void send(String message)` |
| `notify/TgNotifier.java` | 新建 | `@Component implements INotifier`；构造注入 `OpenlistConfig`；承接 `TgHelper` 原有的 token/userId 判空、`TgSendMsg` 缓存与重建逻辑（缓存字段改为实例字段） |
| `notify/WebhookNotifier.java` | 新建 | `@Component implements INotifier`；构造注入 `OpenlistConfig` + 共享 `OkHttpClient`（`HttpClientConfig.sharedOkHttpClient` bean，与 `EmbyClient` 同款）；`openlist.notify.webhook.url` 为空则 no-op，否则 POST `{"text": message}`，异常内部吞掉只记 warn |
| `notify/NotifierManager.java` | 新建 | `@Component`；构造注入 `List<INotifier>`（Spring 自动收集所有实现）；`send(String message)` 遍历逐个 try/catch 调用，单渠道失败只记 warn、不影响其余渠道 |
| `helper/TgHelper.java` | 改动 | 清空原有 Telegram 专属逻辑（token/userId 读取、`TgSendMsg` 缓存），`sendMsg(String)` 方法体改为 `SpringUtils.getBean(NotifierManager.class).send(msg)`；类名、方法签名、包路径均不变；Javadoc 更新为"兼容门面，转发到 `NotifierManager`；新代码请直接注入 `NotifierManager`" |
| `config/OpenlistConfig.java` | 改动 | 新增 `getNotifyWebhookUrl()`，读取 `sys_config` 键 `openlist.notify.webhook.url`，风格与 `getOpenListTgToken()` 一致 |

不改动：`tg/TgSendMsg.java`（被 `TgNotifier` 原样复用，逻辑不变）、`tg/StrmBot.java`、`tg/TgBotRegister.java`、`tg/ResponseHandler.java`（Bot 收指令逻辑，与发通知无关）、`helper/AsynHelper.java`、`monitor/WatchServiceMonitor.java`、`pt/task/RssPollService.java`、`pt/task/DownloadTrackService.java`、`pt/subscription/SearchSupplementService.java`（6 个调用点原样保留）、`DownloadTrackServiceTest.java`、`SearchSupplementServiceTest.java`（`mockStatic` 断言原样保留）。

## 5. API

无新增/变更接口。`openlist.notify.webhook.url` 走既有的系统参数管理接口（`/system/config`），与 `openlist.tg.token` 完全相同的路径，不需要专门的后端接口。

## 6. 前端改动

无。`openlist-web/src/views/system/config/index.vue` 是 RuoYi 标准的参数设置通用 CRUD 页面，`openlist.tg.token`/`openlist.tg.userid` 至今都是通过这个通用页面维护、没有任何专属表单代码；新增的 `openlist.notify.webhook.url` 同理，管理员在该页面新增一条 `config_key=openlist.notify.webhook.url` 的记录即可启用 Webhook 通知，不需要为此新增前端组件。

## 7. 测试计划

新增测试，遵循项目现有的构造器注入 + mock 风格（参考 `EmbyClientTest` 用 `MockWebServer` 测 OkHttp 客户端、`SearchSupplementServiceTest` 用纯 mock 测业务逻辑）：

- `NotifierManagerTest`（新建）：
  - 2 个 mock `INotifier`，调用 `send(msg)` 后断言两者的 `send` 都被调用、参数一致
  - 其中一个 mock 的 `send` 抛异常 → 断言另一个仍然被调用（验证"单渠道失败不影响其余渠道"）
  - 空 `List<INotifier>` → `send(msg)` 不抛异常
- `WebhookNotifierTest`（新建，参照 `EmbyClientTest` 用 `MockWebServer`）：
  - `openlist.notify.webhook.url` 为空 → 不发起任何 HTTP 请求（`MockWebServer` 收不到请求）
  - 非空 → 发起一次 `POST`，请求体为 `{"text":"<message>"}`，`Content-Type: application/json`
  - `MockWebServer` 返回 5xx / 连接失败 → `send()` 不抛异常（吞异常、记 warn）
- `TgNotifierTest`（新建，可选，视 `TgSendMsg` 是否方便打桩而定）：
  - token/userId 任一为空 → 不构造 `TgSendMsg`（可通过包内可见的缓存字段或行为侧面验证，不发起真实 Telegram 请求）
  - 不做"真的发到 Telegram"的集成测试（`TgHelper` 原本也没有专门的单测，`TgSendMsg` 内部已经吞了 `TelegramApiException`，行为原样迁移，不需要重新证明）
- 现有测试**不改动**，作为回归验证：
  - `DownloadTrackServiceTest`、`SearchSupplementServiceTest` 的 `mockStatic(TgHelper.class)` 断言应原样通过（验证门面委托没有破坏静态方法的可 mock 性）
  - `RssPollServiceTest` 同理（虽然未见 `mockStatic` 用法，需跑一遍确认没有因为 `TgHelper` 内部实现变化而产生副作用，比如构造 `NotifierManager` bean 失败导致的 `SpringUtils.getBean` 异常路径变化）
- 启动验证（按 AGENTS.md 要求）：新增 3 个 `@Component`（`TgNotifier`/`WebhookNotifier`/`NotifierManager`），必须 `docker compose up -d --build --no-deps backend` 后确认容器 `restarts=0`；重点检查 `NotifierManager` 构造注入 `List<INotifier>` 在只有 0~2 个实现类时是否正常装配（Spring 对空 `List` 注入是合法的，但仍需实测确认，不能只信任单元测试——单元测试是直接 `new NotifierManager(List.of(...))`，绕过了真实的 Spring 装配路径）。

## 8. 不做的事情（本次范围之外）

- 不实现企业微信、Bark、飞书等具体渠道（`WebhookNotifier` 只是验证抽象用的通用实现，具体服务商的私有协议留给未来按需新增）
- 不做"通知渠道管理"后台页面（新增/编辑/启停渠道走通用参数设置页面即可）
- 不做每个渠道独立的"是否启用"布尔开关（沿用"配置非空即启用"的现有语义）
- 不做通知失败重试/退避（渠道内部吞异常、记日志，等下一次业务事件自然触发下一条通知）
- 不改 `TgSendMsg`/`StrmBot`/`TgBotRegister`/`ResponseHandler`（Telegram Bot 收指令的双向交互逻辑，与本次"发通知"单向能力无关）
- 不重命名 `TgHelper`、不替换其 6 个既有调用点（原因见 2.2 节，`mockStatic` 硬约束）
- 不做消息模板/富文本抽象（`INotifier.send(String message)` 直接传纯文本/Markdown 字符串，各渠道自己决定怎么解析，与现状一致）
