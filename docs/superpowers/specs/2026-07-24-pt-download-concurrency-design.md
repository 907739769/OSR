# 下载并发/优先级控制设计

**日期**：2026-07-24
**作者**：Jack（与 Claude 协作）
**前置阅读**：
- `docs/superpowers/specs/2026-07-21-pt-subscription-download-design.md`（订阅建立/RSS 推送链路，`SubscriptionEngine.handleGroup` 的原始设计）
- `docs/superpowers/specs/2026-07-22-pt-search-supplement-design.md`（搜索补集，`pushBest()` 复用同一段 `handleGroup`）
- `docs/superpowers/specs/2026-07-23-subscription-create-search-backfill-design.md`（建订阅补搜，同样最终落到 `handleGroup`）
- **本设计与"下载器负载均衡"设计（同批 D 组任务）共同修改 `SubscriptionEngine` 附近代码，第 2.4 节写明了双方的分工边界，请负载均衡设计的实现者也读一遍本节。**

## 1. 背景与目标

### 1.1 问题

[`SubscriptionEngine.handleGroup()`](../../../ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java) 是 RSS 推送、搜索补集、建订阅补搜三条链路共用的唯一推送出口：只要某个 (订阅, 集号) 分组过滤择优后选出了种子，就会立刻 `resolveDownloader()` 选一个下载器并推送，没有任何"这个下载器现在已经有多少任务在跑"的检查。

实际会出现的场景：

- 用户新建一个缺 20+ 集的老剧订阅，`2026-07-23` 的建订阅补搜设计会先搜整季包、再逐集补搜，短时间内可能连续推送十几个种子到同一个下载器；
- 一次 RSS 轮询同时命中多个订阅的多个分组，全部指向同一个（未指定下载器时唯一启用的）下载器；
- 多个 PT 订阅长期共享同一个 qBittorrent 实例，缺集补齐阶段大量任务一拥而上，抢占带宽和磁盘 IO，导致既有下载任务也被拖慢。

当前完全没有"同一下载器同时处于 PUSHED/DOWNLOADING 的记录数上限"这个概念，也没有任何限流。

### 1.2 目标

给 `PtDownloaderPlus` 增加一个可配置的最大并发数，`SubscriptionEngine.handleGroup()` 推送前检查目标下载器当前的在途任务数，达到上限就跳过本轮（不落库、不占位、不推送），留给下一轮 RSS 轮询/周期性补搜/手动补搜自然重试。

**范围限定**：只做"单个下载器的并发上限保护"。不做优先级排序（原因见第 8 节），不做跨下载器的选择/调度（那是负载均衡设计的职责，见 2.4 节），不引入分布式锁、长事务或阻塞等待队列。

### 1.3 成功标准

1. 下载器配置了最大并发数后，其同时处于 `PUSHED`/`DOWNLOADING` 的下载记录数不会超过该值（允许极小概率的短暂超限，见 2.3 节的取舍说明，但不会出现"十几个任务一次性全挤进去"的情况）。
2. 达到上限时跳过的分组，其涉及的集仍保持 `MISSING`，不做任何占位/落库，不产生孤儿记录或状态不一致。
3. 不配置（或配置为 0）的下载器行为与现在完全一致，不做任何限制——现有部署升级后无感知。
4. 检查逻辑是一次轻量的 COUNT 查询，不引入网络调用、不引入长事务、不阻塞等待。

## 2. 架构

### 2.1 检查时机与位置

在 `handleGroup()` 内 `resolveDownloader(sub)` 解析出具体下载器**之后**、原子占位 `claim()` **之前**插入一次容量检查：

```
resolveDownloader(sub) 返回 downloader
        │
        ▼
isOverCapacity(downloader)？
        │
        ├─ 是 → 记 debug 日志 + searchLogService.recordSummary(..., "下载器并发已达上限")
        │        → return false（不占位、不落库、不推送）
        │
        └─ 否 → 照常走 claim() → save() → addTorrent()（原有逻辑不变）
```

放在"解析下载器之后、占位之前"而不是更早或更晚，理由：

- **必须晚于 `resolveDownloader()`**：并发上限是"下载器"级别的属性，必须先知道具体推给哪个下载器才能查它的当前占用量。
- **必须早于 `claim()`**：`claim()` 会把集状态从 `MISSING` 改成 `IN_FLIGHT`，一旦占位就必须走到底（落库+推送或显式回滚）。在占位前拦截，跳过分支不需要任何回滚逻辑，和现有"没有可用下载器"分支（同样在 `claim()` 之前 return false）完全对称，读者一眼就能看出这是同一类"资源不足，本轮放弃"的短路。

### 2.2 容量查询

```java
/** 计入并发占用的记录状态：已推送等待下载器确认 + 下载器确认后正在下载 */
private static final String RECORD_DOWNLOADING = DownloadRecordState.DOWNLOADING.value();

/**
 * 目标下载器是否已达最大并发。maxConcurrent 为 null 或 &lt;=0 视为不限（与
 * pt_filter_config 里 min_size/max_size 用 0 表示"不限"的既有约定一致）。
 */
private boolean isOverCapacity(PtDownloaderPlus downloader) {
    Integer max = downloader.getMaxConcurrent();
    if (max == null || max <= 0) {
        return false;
    }
    long active = recordService.count(new QueryWrapper<PtDownloadRecordPlus>()
            .eq("downloader_id", downloader.getId())
            .in("state", RECORD_PUSHED, RECORD_DOWNLOADING));
    return active >= max;
}
```

这是一次单表 COUNT，命中新增的 `idx_downloader_state` 索引（见第 3 节），没有 JOIN、没有网络调用，符合"不引入长事务或阻塞等待"的要求。

### 2.3 关键设计取舍

- **达到上限就跳过本轮，不做排队/等待**：`handleGroup()` 本身没有 `@Transactional`（类注释已经写明原因——方法体内含推送下载器的网络调用，长事务是反模式），如果为了"排队"引入等待或重试循环，会和这个既有约束直接冲突。跳过后集仍是 `MISSING`，天然会被下一轮 RSS 轮询（`RssPollTask` 每 60 秒心跳，各索引器按自己的 `poll_interval` 到期）、`AutoSearchService` 周期性补搜、或用户手动搜索补集重新捡回，等效于一个"隐式的、由现有调度节奏驱动的重试队列"，不需要再造一套显式队列表和调度器。
- **允许检查窗口内的小概率短暂超限**：`isOverCapacity()` 和后续的 `claim()`/`save()` 之间不是原子的。如果两次几乎同时的调用（例如一次建订阅补搜和一次手动搜索补集同时命中同一个下载器）都在对方提交前完成了计数查询，理论上会各自判定"未满"而同时推送，短暂超出上限 1（RSS 轮询本身由 `RssPollTask` 的 `AtomicBoolean running` 保证同一时刻只有一轮 `process()` 在跑，不存在 RSS 内部的并发；真正会撞上这个窗口的是"RSS 轮询"与"搜索补集/建订阅补搜"这两条不同触发源之间的交叉，概率很低且后果轻微)。这与现有 `excludeAlreadyRecorded()`/`claim()` 一直采用的"乐观检查 + 唯一约束兜底"思路是同一类工程取舍——`maxConcurrent` 是"避免大批量任务一拥而上抢带宽"的软保护，不是需要严格保证的硬件资源锁，为了消除百分之一概率的短暂超限 1 个任务而引入分布式锁/`SELECT ... FOR UPDATE`，收益和复杂度不成比例，本次不做。
- **`maxConcurrent` 默认值 0（不限）**：保证现有下载器升级后行为不变；需要限流的下载器由用户主动去配置页填一个正数。
- **不需要区分 RSS / 搜索补集来源**：`handleGroup()` 已经用 `source` 参数区分调用来源仅用于日志（`SearchLogService`），并发上限检查是"目标下载器当前的真实占用量"，与推送发起的来源无关，两条链路复用同一个检查天然正确，不需要按 source 做特殊处理。

### 2.4 与"下载器负载均衡"设计的关系（分工边界）

负载均衡设计同样会改到 `SubscriptionEngine`（大概率是 `resolveDownloader(sub)` 方法体内部），因为当前 `resolveDownloader()` 在订阅未指定下载器时永远返回 `enabled.get(0)`（第一个启用的下载器），这不是真正的负载均衡，只是一个占位实现。两份设计的边界如下：

| 关注点 | 归属 |
|---|---|
| "推给哪个下载器"——订阅未指定下载器时，在多个启用的下载器里选哪一个 | 负载均衡设计，改 `resolveDownloader()` 方法体内部 |
| "这个已经选定的下载器还能不能再推一个"——单个下载器的并发上限保护 | 本设计，改 `resolveDownloader()` 调用点之后、`claim()` 之前 |

需要负载均衡设计的实现者注意的三点：

1. **复用计数方法，不要重复造查询**：负载均衡选择算法如果需要"某下载器当前有多少个在途任务"作为负载指标（大概率需要，"负载"最自然的定义就是这个），请直接复用本设计新增的 `private long`/`boolean` 系列方法背后那条 COUNT 查询逻辑（如果本设计先落地，直接调用 `isOverCapacity()` 同款查询改造成的 `activeCount(Integer downloaderId)`；如果负载均衡先落地，请把这条查询提成一个双方都能调用的 package-private 方法，本设计届时改为调用它）。不要在 `SubscriptionEngine` 里维护两份几乎相同的 `recordService.count(...)` 查询。
2. **负载均衡选下载器时应该顺带尊重 `maxConcurrent`**：如果负载均衡只看"谁负载最低"而不管"谁已经到上限"，会出现"负载均衡精心选出的下载器，其实已经满载，选完立刻被本设计的 `isOverCapacity()` 拦下白白浪费一次决策"的情况。理想情况下负载均衡的候选池应该优先排除已达 `maxConcurrent` 的下载器（全部候选都满载时再退化到"选负载最低的那个"，反正也会被本设计拦下，不影响正确性，只是效率上不够聪明）。这不是本设计的职责范围（本设计不做多下载器间的择优），但请在负载均衡设计文档的架构章节里明确写清楚这一点，避免两份设计的读者互相以为对方已经处理。
3. **改动位置相邻但不重叠**：本设计的改动点在 `resolveDownloader()` **调用之后**；负载均衡的改动点在 `resolveDownloader()` **方法体内部**。两者预期只在 `handleGroup()` 同一段代码里产生小范围文本冲突（谁先合并，谁在 diff 里多几行 context），不是逻辑冲突。建议后落地的一方基于先落地的一方 rebase。

## 3. 数据模型改动

### 3.1 `pt_downloader` 新增列

沿用 `20260735-pt-downloader-strm-task-link.sql`（同一张表新增列）的既有幂等写法——该表已在真实库存在且可能有数据，用 `INFORMATION_SCHEMA` 探测 + 动态 SQL 的 `ALTER TABLE`，不用 `DROP TABLE + CREATE TABLE` 重建：

```sql
-- 20260738-pt-downloader-max-concurrency.sql
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'max_concurrent');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `pt_downloader` ADD COLUMN `max_concurrent` int NOT NULL DEFAULT 0 '
    || 'COMMENT ''同时处于PUSHED/DOWNLOADING状态的最大记录数，0表示不限'' AFTER `tag`',
    'SELECT ''Column max_concurrent already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

（注：MySQL 字符串拼接需用 `CONCAT()`，上面用 `||` 只是示意，实现时按 `20260735` 脚本的写法用单条字符串拼好整段 `ALTER TABLE` 语句，不要真的写 `||`。）

### 3.2 `pt_download_record` 新增复合索引

`isOverCapacity()` 的查询条件是 `downloader_id = ? AND state IN (...)`，现有索引只有单列 `idx_state(state)`，同一批脚本里追加一个复合索引，避免这条在每次 `handleGroup()` 调用里都会跑一次的查询退化成全表扫描：

```sql
-- 同一份 20260738 脚本追加
SET @idx_exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND INDEX_NAME = 'idx_downloader_state');
SET @idx_sql := IF(@idx_exist = 0,
    'ALTER TABLE `pt_download_record` ADD INDEX `idx_downloader_state` (`downloader_id`, `state`)',
    'SELECT ''Index idx_downloader_state already exists''');
PREPARE stmt2 FROM @idx_sql;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
```

不新增表、不改动 `pt_subscription`（第 8 节说明为何不加优先级字段）。

## 4. 后端组件改动清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `ruoyi-common/src/main/resources/sql/20260738-pt-downloader-max-concurrency.sql` | 新建 | `pt_downloader` 加 `max_concurrent` 列（默认 0=不限）；`pt_download_record` 加 `idx_downloader_state(downloader_id, state)` 索引；追加到 `MysqlDdl.getSqlFiles()` 列表末尾 |
| `mybatisplus/domain/PtDownloaderPlus.java` | 改动 | 新增 `private Integer maxConcurrent;`（`@TableField("max_concurrent")`），注释写明"0表示不限" |
| `pt/subscription/SubscriptionEngine.java` | 改动 | 新增常量 `RECORD_DOWNLOADING`；新增私有方法 `isOverCapacity(PtDownloaderPlus)`；`handleGroup()` 在 `resolveDownloader()` 之后、`claim()` 循环之前插入一次检查，命中则调用 `searchLogService.recordSummary(...)` 记录原因并 `return false` |
| `controller/PtDownloaderController.java`（实际类名以现有为准） | 改动 | 新增/更新字段的表单校验（`maxConcurrent >= 0`），不新增接口——沿用现有 `POST /add`、`PUT /update` |

不改动：`resolveDownloader()` 方法体本身（留给负载均衡设计）、`DownloadTrackService`（并发槽位的"释放"天然发生在它把记录置为 `COMPLETED`/`FAILED` 时，不需要额外通知）、`AutoSearchService`、`SearchSupplementService`、`pt_subscription` 表结构。

## 5. API

无新增接口。`PtDownloaderPlus` 多了一个字段后，现有 `GET /api/openliststrm/pt-downloaders`（列表）、`POST /api/openliststrm/pt-downloaders`（新增）、`PUT /api/openliststrm/pt-downloaders`（修改）三个接口的请求/响应体自动带上 `maxConcurrent` 字段（前端 `data: any` 透传，后端实体新增字段即可，不需要改 Controller 方法签名）。

## 6. 前端改动

只涉及 `openlist-web/src/views/openlist/ptDownloader/index.vue` + `openlist-web/src/composables/usePtDownloader.ts`，风格与现有"关联STRM任务"下拉框（同样是可选的下载器级配置）保持一致：

- **表单**（`index.vue` 新增/编辑对话框）：在"标签"和"状态"之间加一个 `el-form-item label="最大并发数"`，用 `el-input-number :min="0"`，placeholder/说明文案写"0 表示不限"。
- **列表卡片**：`card-row` 里加一行展示当前配置值（`0` 时显示"不限"），与"保存路径""标签"等现有行样式一致。**不做**实时"当前占用/上限"的占用量展示（见第 8 节）。
- **composable**：`initForm()` 里加 `maxConcurrent: 0`；`rules` 里加一条非必填但需 `type: 'number', min: 0` 的校验（沿用 `port` 字段的校验写法）。

不涉及 `usePtSubscription.ts`/`ptSubscription` 页面（第 8 节说明为何不做订阅优先级，因此订阅表单不需要改动）。

## 7. 测试计划

对齐现有 `SubscriptionEngineTest.java` 的 mock 风格（`downloaderService.list(any(Wrapper.class))` 返回启用下载器列表、`recordService` 各方法逐个 mock）：

- `isOverCapacity` / `handleGroup` 集成到并发检查后的行为：
  - `maxConcurrent = 0` → 不查 `recordService.count`（或查了但结果被短路忽略），走完整推送流程，与现有测试结果一致（回归保证不破坏现有用例）
  - `maxConcurrent = null` → 同上，视为不限
  - `maxConcurrent = 2`，`recordService.count(any(Wrapper.class))` 返回 `2` → `handleGroup` 返回 `false`；断言 `episodeService.update`（`claim()`）**未被调用**、`recordService.save`**未被调用**、`downloaderClient.addTorrent`**未被调用**（三个断言确保是在占位前拦截，而不是占位后又回滚）
  - `maxConcurrent = 2`，`recordService.count(...)` 返回 `1`（未达上限）→ 正常走完推送流程，行为与现有测试一致
  - 命中上限时应调用 `searchLogService.recordSummary(subId, episode, source, 含"并发"或"上限"关键字的原因)`——沿用现有"没有可用下载器"分支的断言写法
  - `recordService.count` 的 `QueryWrapper` 断言：`downloader_id` 等于目标下载器 id，`state` 在 `(PUSHED, DOWNLOADING)` 内（可用 Mockito `ArgumentCaptor<Wrapper>` 拿到 SQL 片段做包含性断言，参考本文件同目录其他测试对 `QueryWrapper`/`UpdateWrapper` 的验证写法）
- `PtDownloaderPlusTest`（现有实体测试）：补一条 `maxConcurrent` 的 getter/setter 断言，与其余字段测试风格一致
- 前端：`usePtDownloader.spec.ts`（现有 composable 测试）补一条 `initForm().maxConcurrent === 0` 的断言

## 8. 不做的事情（本次范围之外）

- **不做订阅优先级字段**：跳过分支不会导致某个订阅永久抢不到——RSS 轮询按索引器各自的 `poll_interval`（通常几分钟到几十分钟）周期性重新命中，`AutoSearchService` 周期性补搜兜底，`DownloadTrackService` 完成/失败后及时释放并发槽位——最坏情况只是延后一到两轮，不是饥饿。引入订阅优先级需要新增字段、排序逻辑、前端配置项，相对于"避免几分钟到几十分钟延后"这个收益不成比例，按 YAGNI 本次不做。如果后续真的出现"重要订阅总排在后面"的反馈，思路是给 `pt_subscription` 加一个 `priority` 整数字段，在 `process()` 组装完 `groups`/`groupMatch` 后，对 `entrySet` 按订阅优先级降序重排即可，改动量不大，无需现在预先设计。
- **不做跨下载器的选择/调度**：即"该把任务推给哪个下载器"，这是负载均衡设计的职责，见 2.4 节。
- **不做队列表/异步排队机制**：达到上限直接跳过，不持久化"等待中"的任务，理由见 2.3 节。
- **不做分布式锁/`SELECT ... FOR UPDATE` 消除检查窗口的竞态**：接受极小概率的短暂超限，理由见 2.3 节。
- **不在下载器管理页展示实时占用数**（如"当前 3/5"）：这是"配置最大并发数"这个功能本身不需要的监控能力，如果确实需要查看某下载器当前有多少在途任务，现有"下载记录管理"页已经能按下载器筛选、按状态筛选查看，不需要为这次改动重复造一个实时占用量展示。
- **不做每订阅级别的并发上限**：任务要求的是 `PtDownloaderPlus` 级别的配置，多个订阅共享同一个下载器时的上限是下载器整体的，不细分到订阅粒度。
