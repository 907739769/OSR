# PT 订阅下载后端健壮性优化设计

**日期**：2026-07-24
**作者**：Jack（与 Claude 协作）
**前置阅读**：
- `docs/superpowers/specs/2026-07-21-pt-subscription-download-design.md`（`SubscriptionEngine`/`DownloadTrackService`/`FilterCriteria` 覆盖机制的原始设计）
- `docs/superpowers/specs/2026-07-23-subscription-create-search-backfill-design.md`（`episodeCache` 批内缓存的既有写法，本设计沿用其风格）

## 1. 背景与目标

本次是四项独立的**后端内部健壮性优化**，彼此没有依赖关系，用户侧无感知（第 2 项的下载记录分类标签例外，属于展示层的小幅增强）。四个问题分别是：

1. **`SubscriptionEngine.resolveDownloader` 重复查询下载器列表**：`process()` 一次批量处理可能匹配出多个 `(订阅, 集号)` 分组，每个分组命中候选后都会调用 `handleGroup` → `resolveDownloader`，而 `resolveDownloader` 内部每次都执行一次 `downloaderService.list(...)` 查询启用下载器表。同一批次内启用下载器集合不会变化，这是纯粹的重复查询。
2. **下载失败原因是自由文本，无法分类统计/展示**：`DownloadTrackService.fail()` 的 `reason` 参数是拼好的中文句子，直接落到 `pt_download_record.fail_reason`。前端只能原样展示文本，无法按"种子丢失/僵尸超时"等维度筛选、统计或做差异化 UI（如僵尸超时标红提示"考虑调大超时时间"）。
3. **僵尸种子超时是写死的全局常量**：`ZOMBIE_TIMEOUT_MILLIS = 24小时` 硬编码在 `DownloadTrackService` 里，无法适应"高清 4K 剧集包体积大、下载慢，24 小时误判"或"某些订阅要求更快释放重试"的场景，且无法按订阅差异化（合集与单集的合理时长本就不同）。
4. **`resolveDownloader` 的下载器选择策略是"指定优先，否则永远选第一个启用的"**：多下载器场景下，没指定下载器的订阅全部命中同一个下载器，另一个下载器可能一直闲置，造成负载不均。

### 1.1 成功标准

1. `process()` 处理一批种子（无论匹配出多少个分组）时，启用下载器列表只查询一次；负载统计只查询一次。
2. `pt_download_record` 新增结构化失败原因分类字段，前端下载记录页能展示对应标签；历史数据（无分类）不受影响、不强行回填。
3. 僵尸超时有全局默认值（配置项，重启生效）与订阅级覆盖（数据里改、无需发版）两级；不设置时行为与现状完全一致（24 小时）。
4. 多下载器且订阅未指定下载器时，优先选择当前 `PUSHED`/`DOWNLOADING` 记录数最少的下载器；指定下载器的订阅行为不变。

### 1.2 范围限定

- 不改变 `process()`/`pushBest()`/`handleGroup()` 对外可见的返回值语义与调用方（`RssPollService`、`SearchSupplementService`、`AutoSearchService` 等）。
- 不做失败原因的前端管理界面（新增/编辑分类），分类由后端枚举固定，前端只读展示。
- 不做订阅级僵尸超时覆盖的编辑 UI（本次是后端配置能力，覆盖值目前只能通过已有的"过滤规则覆盖"式 JSON 写入，不新增专门表单）。
- 不做下载器负载的实时监控面板、容量上限配置——只做"挑选时避开明显更忙的那个"，见 §2.4 的取舍说明。

## 2. 架构

### 2.1 问题 1：批内缓存启用下载器列表与负载统计

`SubscriptionEngine.process()` 里已经有一份"批内缓存"的先例——`episodeCache`（`Map<Integer, List<PtSubscriptionEpisodePlus>>`，按订阅 id 缓存该订阅的全部集，避免同一订阅出现在多个分组时重复查 `episodeService.listBySubscription`）。本次按同样的思路，把 `resolveDownloader` 依赖的两份数据也提到循环外查一次：

- `List<PtDownloaderPlus> enabledDownloaders`：启用下载器列表，一次批次内不会变化。
- `Map<Integer, Long> downloaderLoadCache`：下载器 id → 当前 `PUSHED`/`DOWNLOADING` 记录数，供问题 4 的负载均衡使用（见 §2.4）。这份缓存不是只读的——批次内每成功推送一次就地 `+1`（见 §2.4），避免同一批次因为负载数据不更新，把大量种子集中推给同一个"看起来最闲"的下载器。

延续现有风格，**不引入新的包装类型**，直接把这两份数据作为新增参数传给 `handleGroup`（`episodeCache` 现在就是这么传的，保持一致比引入 `BatchContext` 这类包装对象更简单，改动面更小）：

```java
boolean handleGroup(MatchResult match, List<TorrentInfo> candidates,
                     PtFilterConfigPlus globalConfig,
                     Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache,
                     List<PtDownloaderPlus> enabledDownloaders,
                     Map<Integer, Long> downloaderLoadCache,
                     String source)
```

`process()` 在循环外查一次 `enabledDownloaders` 与 `downloaderLoadCache`，循环内所有分组共享；`pushBest()`（单次调用，供 `SearchSupplementService`/`DownloadRecordAdminService.retry` 复用）为每次调用各自构建一份新的缓存——这与它今天对 `episodeCache`/`globalConfig` 的做法完全一致（每次调用都是全新的批次，只是这个批次只有一个分组）。

**不做的事**：`pushBest()` 被外部循环多次调用时（如 `SearchSupplementService.supplementOnCreate` 逐集补搜），每次调用仍会各自查一次下载器列表和负载统计，不做跨调用的缓存。这类跨调用批内缓存需要把缓存对象一路传给 `SearchSupplementService` 的调用方并管理生命周期，属于另一个类的改动，本次不做（YAGNI——`SubscriptionEngine.resolveDownloader` 的重复查询问题明确限定在 `process()` 一次批处理内）。

### 2.2 问题 2：失败原因分类枚举

新增 `pt/task/FailReasonCode.java`，风格与既有 `DownloadRecordState`/`SubscriptionEpisodeState` 一致（`value()` 返回落库字符串）：

```java
public enum FailReasonCode {
    /** 下载器里已经找不到对应种子（可能被删除，或磁力元数据解析失败） */
    TORRENT_NOT_FOUND("TORRENT_NOT_FOUND"),
    /** 种子仍在下载器里但超过僵尸超时仍未完成 */
    ZOMBIE_TIMEOUT("ZOMBIE_TIMEOUT"),
    /** 兜底分类：当前代码里没有其他失败路径会产生 FAILED 记录，为将来的失败路径（如未来记录推送失败）预留 */
    OTHER("OTHER");
    ...
}
```

`DownloadTrackService.fail()` 目前只有两个调用点（找不到种子、僵尸超时），恰好对应前两个分类；`OTHER` 现阶段没有任何调用点会用到，只是为分类字段设计一个非空兜底值，避免将来新增失败路径时要再改一次表结构。`fail()` 签名扩展为 `fail(PtDownloadRecordPlus record, FailReasonCode code, String reason)`，两处调用点各自传入对应枚举值，`reason` 文本不变（分类是给程序/UI 用的结构化维度，文本是给人读的具体上下文，两者并存而不是二选一）。

**为什么不把 `SubscriptionEngine.handleGroup` 里"推送到下载器失败"也纳入分类**：那条路径失败时会 `recordService.removeById` 把刚插入的记录整条删掉再回滚占位（见 `SubscriptionEngine.java:211-218`），从未落地过一条 `FAILED` 状态的 `pt_download_record`——没有失败记录也就没有分类的对象。如果将来改成"推送失败也落一条 FAILED 记录供排查"，`PUSH_ERROR` 分类到时候可以直接加进这个枚举，不影响现有两个值和数据库结构（`varchar` 字段，加枚举值不用改表）。本次不做这个改动（缩小改动面，问题描述里的"设计一套失败原因分类"并不要求同时改变现有的推送失败回滚策略）。

### 2.3 问题 3：僵尸超时全局默认值 + 订阅级覆盖

**全局默认值**：仿照现有 `@Value("${pt.download.max-consecutive-failures:3}")` 的写法，新增构造器参数：

```java
public DownloadTrackService(..., 
                            @Value("${pt.download.zombie-timeout-hours:24}") int zombieTimeoutHoursDefault) {
    ...
    this.zombieTimeoutMillisDefault = zombieTimeoutHoursDefault * 3600_000L;
}
```

默认值 24 小时与现状完全一致，不改配置文件的用户不会感知到任何变化。

**订阅级覆盖**：`PtSubscriptionPlus` 新增字段 `downloadOverride`（`@TableField("download_override")`），JSON 格式，语义与解析方式对齐 `filterOverride`/`FilterCriteriaFactory`——**只有 JSON 里出现的键才覆盖，没出现的沿用全局默认值**，格式损坏或字段类型不对时整体回退全局值，绝不让一条脏配置炸掉整轮轮询。当前只支持一个键 `zombieTimeoutHours`（整数，单位小时）：

```json
{ "zombieTimeoutHours": 48 }
```

**为什么不复用 `filter_override` 这同一个字段**：`FilterCriteriaFactory` 的类注释明确写着"键名与 `PtFilterConfigPlus` 的字段名一致"，`filter_override` 在概念上专属于过滤/排序条件；僵尸超时是下载追踪的参数，语义上不属于"过滤规则"。把两类不相关的配置塞进同一个 JSON 字段，将来这个字段的键名空间会变得模糊（无法一眼看出某个键是过滤用的还是追踪用的），也会让 `FilterCriteriaFactory` 和这里新写的解析逻辑各自"各取所需"地读同一份 JSON，容易踩混。新增独立的 `download_override` 字段，两套覆盖各自独立演化，且和 `filter_override` 一样"空表示全用全局"，符合项目里已确立的覆盖字段语义。

**为什么不新建一个独立的 `DownloadOverrideFactory` 类比照 `FilterCriteriaFactory`**：`FilterCriteriaFactory` 要处理 9 个字段的合并，独立成类、配一堆 `intOf`/`longOf`/`strOf` 辅助方法是消化复杂度的合理拆分；本次只有 1 个字段（`zombieTimeoutHours`），照搬一个完整工厂类是过度设计。改为在 `DownloadTrackService` 里加一个私有方法 `resolveZombieTimeoutMillis(PtSubscriptionPlus sub)`，沿用同样的防御性写法（`containsKey` 判断"是否显式覆盖"、`try/catch` 兜底解析异常、非法值回退默认）：

```java
private long resolveZombieTimeoutMillis(PtSubscriptionPlus sub) {
    if (sub == null || StringUtils.isBlank(sub.getDownloadOverride())) {
        return zombieTimeoutMillisDefault;
    }
    try {
        JSONObject patch = JSONObject.parseObject(sub.getDownloadOverride());
        if (patch != null && patch.containsKey("zombieTimeoutHours")) {
            Integer hours = patch.getInteger("zombieTimeoutHours");
            if (hours != null && hours > 0) {
                return hours * 3600_000L;
            }
        }
    } catch (Exception e) {
        log.warn("订阅[{}] 下载追踪覆盖不是合法 JSON，已回退全局默认值：{}", sub.getId(), e.getMessage());
    }
    return zombieTimeoutMillisDefault;
}
```

将来如果确实要覆盖第二个字段（如订阅级 `maxConsecutiveFailures`），再把这个方法升级成和 `FilterCriteriaFactory` 一样的独立工厂类——现在只有一个字段，先不做这个抽象（YAGNI）。

**如何拿到订阅对象**：`DownloadTrackService.track(downloader, torrents)` 目前完全不查订阅表，只操作 `pt_download_record`/`pt_subscription_episode`。新增依赖 `IPtSubscriptionPlusService subscriptionService`，在 `track()` 循环外**批量**查一次本次要处理的记录涉及的全部订阅（`record.getSubId()` 去重后 `subscriptionService.listByIds(...)`），缓存进 `Map<Integer, PtSubscriptionPlus>`，循环内按 `record.getSubId()` 取用——这正是问题 1 强调的"批内缓存"同一个原则，避免给 `track()` 引入新的 N+1 查询。`track()` 一次只处理一个下载器的在途记录，通常记录数不多，一次 `listByIds` 足够。

```java
private Map<Integer, PtSubscriptionPlus> loadSubscriptions(List<PtDownloadRecordPlus> records) {
    List<Integer> subIds = records.stream().map(PtDownloadRecordPlus::getSubId).distinct().toList();
    if (subIds.isEmpty()) return Map.of();
    return subscriptionService.listByIds(subIds).stream()
            .collect(Collectors.toMap(PtSubscriptionPlus::getId, s -> s));
}
```

订阅已被删除（`subCache` 里查不到）时按 `null` 处理，`resolveZombieTimeoutMillis(null)` 直接回退全局默认值——不因为订阅没了就让追踪逻辑报错。

`GRACE_MILLIS`（找不到种子的宽限期）本次不做成可配置项：问题描述明确只针对 `ZOMBIE_TIMEOUT_MILLIS`，`GRACE_MILLIS` 语义是"等 qB 解析磁力元数据"，10 分钟是个很稳的经验值，没有被提出配置需求，不顺带扩大改动范围。

### 2.4 问题 4：下载器负载均衡策略

`resolveDownloader` 的决策顺序调整为：

1. 订阅显式指定了 `downloaderId` 且该下载器仍在启用列表里 → 直接用它（**不变**，用户的显式选择优先级最高，负载均衡不应该覆盖用户的明确意图）。
2. 未指定，或指定的下载器已被禁用/删除 → 从 `enabledDownloaders` 里选 `downloaderLoadCache` 计数最小的一个；并列时选列表里靠前的那个（`enabledDownloaders` 顺序即数据库查询顺序，天然稳定，不需要额外补一个 tie-break 规则）。

```java
private PtDownloaderPlus resolveDownloader(PtSubscriptionPlus sub,
                                            List<PtDownloaderPlus> enabled,
                                            Map<Integer, Long> loadCache) {
    if (enabled.isEmpty()) {
        return null;
    }
    if (sub.getDownloaderId() != null) {
        for (PtDownloaderPlus d : enabled) {
            if (sub.getDownloaderId().equals(d.getId())) {
                return d;
            }
        }
        log.warn("订阅[{}] 指定的下载器 {} 不可用，改用负载最低的启用下载器", sub.getId(), sub.getDownloaderId());
    }
    PtDownloaderPlus best = enabled.get(0);
    long bestLoad = loadCache.getOrDefault(best.getId(), 0L);
    for (int i = 1; i < enabled.size(); i++) {
        PtDownloaderPlus candidate = enabled.get(i);
        long load = loadCache.getOrDefault(candidate.getId(), 0L);
        if (load < bestLoad) {
            best = candidate;
            bestLoad = load;
        }
    }
    return best;
}
```

`downloaderLoadCache` 的初始值来自一次批量查询（**每批次一次**，呼应 §2.1）：

```java
private Map<Integer, Long> loadDownloaderLoadCounts(List<PtDownloaderPlus> enabledDownloaders) {
    if (enabledDownloaders.isEmpty()) {
        return new HashMap<>();
    }
    List<Integer> ids = enabledDownloaders.stream().map(PtDownloaderPlus::getId).toList();
    List<PtDownloadRecordPlus> active = recordService.list(new QueryWrapper<PtDownloadRecordPlus>()
            .in("downloader_id", ids)
            .in("state", RECORD_PUSHED, RECORD_DOWNLOADING));
    Map<Integer, Long> counts = new HashMap<>();
    for (PtDownloadRecordPlus r : active) {
        counts.merge(r.getDownloaderId(), 1L, Long::sum);
    }
    return counts;
}
```

推送成功后（`handleGroup` 里 `addTorrent` 调用不抛异常之后），把这次推送计入缓存：`downloaderLoadCache.merge(downloader.getId(), 1L, Long::sum)`。这一步是必要的：如果不更新，同一批次内连续命中的多个分组会看到同一份"过时"的负载快照，全部涌向批次开始时"看起来最闲"的那个下载器，负载均衡在批次内部就失效了。就地自增让批次内的选择也能互相感知。

**为什么是"当前 PUSHED/DOWNLOADING 记录数最少"，而不是更复杂的策略**：

- 不做基于下载速度/剩余空间/CPU 负载等外部指标的路由——这些指标当前系统完全没有采集（`DownloaderClientFactory`/`IDownloaderClient` 没有暴露任何监控接口），要做这类策略得先建一整套下载器健康监控，这是本次问题范围之外的大工程。
- 不做加权轮询、下载器容量上限配置——当前没有任何用户反馈说明"某下载器该少分一点"，加一个权重字段本质是给一个不存在的需求预留配置项，YAGNI。
- "在途记录数"是现成数据（`pt_download_record` 本来就有 `downloader_id` + `state`），零新增字段、零新增采集逻辑，直接反映"这个下载器手头有多少活"，作为负载的代理指标简单且直接可信；引擎本身也已经在维护这份状态（`DownloadTrackService.track` 就是靠它推进状态机的），复用而非新建口径，也更不容易产生"两套负载数据互相打架"的问题。
- 如果将来真的出现"下载器 A 机器磁盘满了但记录数不多"这类代理指标失真的场景，再引入更细的健康检查，现在没有证据表明这是当前的真实痛点。

## 3. 数据模型改动

### 3.1 `pt_download_record` 新增 `fail_reason_code` 列（对应问题 2）

新增迁移脚本 `ruoyi-common/src/main/resources/sql/20260738-pt-download-record-fail-reason-code.sql`，沿用 `20260734-pt-episode-fail-count.sql` 的幂等写法：

```sql
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'fail_reason_code');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `fail_reason_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''失败原因分类：TORRENT_NOT_FOUND/ZOMBIE_TIMEOUT/OTHER，历史失败记录为 NULL 表示未分类'' AFTER `fail_reason`', 'SELECT ''Column fail_reason_code already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

`PtDownloadRecordPlus` 新增字段 `failReasonCode`（`@TableField("fail_reason_code")`）。`DownloadRecordView` 同步新增 `failReasonCode` 字段（供前端展示，见 §6）。

**不回填历史数据**：迁移脚本只加列，不 `UPDATE` 已有的 `FAILED` 记录。历史失败记录的 `fail_reason` 是自由文本，反推分类只能靠字符串匹配猜测，容易猜错；新列留空即表示"这条记录产生于分类能力上线之前"，前端按 NULL 处理为"未分类"，不强行伪造历史数据的分类。

### 3.2 `pt_subscription` 新增 `download_override` 列（对应问题 3）

新增迁移脚本 `ruoyi-common/src/main/resources/sql/20260739-pt-subscription-download-override.sql`：

```sql
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'download_override');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `download_override` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''订阅级下载追踪覆盖(JSON)，当前仅支持 zombieTimeoutHours 键，空表示全用全局配置'' AFTER `filter_override`', 'SELECT ''Column download_override already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
```

`PtSubscriptionPlus` 新增字段 `downloadOverride`（`@TableField("download_override")`），紧跟在 `filterOverride` 后面声明，体现两者是同类字段。

两个迁移脚本都要追加进 `MysqlDdl.getSqlFiles()` 列表（`ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java`），否则脚本不会被执行——这是本项目所有迁移都必须做的一步，容易漏。

### 3.3 无数据模型改动的问题

问题 1（批内缓存）、问题 4（负载均衡）都是纯内存/查询逻辑调整，不新增字段、不新表、不新配置项（负载均衡没有可调参数，见 §2.4）。

## 4. 后端组件改动清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `pt/subscription/SubscriptionEngine.java` | 改动 | `process()` 循环外新增查询 `enabledDownloaders`/`downloaderLoadCache`，随 `episodeCache` 一起传给 `handleGroup`；`pushBest()` 同样各自构建一份；`handleGroup` 签名新增两个参数；`resolveDownloader` 改签名并实现负载最低优先；新增私有方法 `loadDownloaderLoadCounts`；成功推送后 `downloaderLoadCache.merge(...)` |
| `pt/task/FailReasonCode.java` | 新建 | 枚举 `TORRENT_NOT_FOUND`/`ZOMBIE_TIMEOUT`/`OTHER`，风格同 `DownloadRecordState` |
| `pt/task/DownloadTrackService.java` | 改动 | 构造器新增 `IPtSubscriptionPlusService subscriptionService` 依赖与 `@Value("${pt.download.zombie-timeout-hours:24}")` 参数；移除 `ZOMBIE_TIMEOUT_MILLIS` 静态常量，改为实例字段 `zombieTimeoutMillisDefault`；新增私有方法 `loadSubscriptions`/`resolveZombieTimeoutMillis`；`track()` 循环外批量查订阅缓存，循环内按 `resolveZombieTimeoutMillis(subCache.get(record.getSubId()))` 判断僵尸超时；`fail()` 签名新增 `FailReasonCode code` 参数，两处调用点各自传入对应枚举并 `record.setFailReasonCode(code.value())` |
| `mybatisplus/domain/PtDownloadRecordPlus.java` | 改动 | 新增字段 `failReasonCode`（`@TableField("fail_reason_code")`） |
| `mybatisplus/domain/PtSubscriptionPlus.java` | 改动 | 新增字段 `downloadOverride`（`@TableField("download_override")`） |
| `pt/task/dto/DownloadRecordView.java` | 改动 | 新增字段 `failReasonCode`，`DownloadRecordAdminService.toView()` 同步补上 `view.setFailReasonCode(r.getFailReasonCode())` |
| `ruoyi-common/src/main/resources/sql/20260738-pt-download-record-fail-reason-code.sql` | 新建 | 见 §3.1 |
| `ruoyi-common/src/main/resources/sql/20260739-pt-subscription-download-override.sql` | 新建 | 见 §3.2 |
| `ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java` | 改动 | `getSqlFiles()` 追加以上两个脚本路径 |

不改动：`RssPollService`、`AutoSearchService`、`SearchSupplementService`、`DownloadRecordAdminService.retry()`（重试产生的是全新记录，不涉及历史 `failReasonCode`）、`TorrentFilterEngine`/`FilterCriteria`/`FilterCriteriaFactory`（过滤择优逻辑完全不变）、前端订阅页/过滤覆盖弹窗（本次不做 `download_override` 的编辑 UI）。

## 5. API

无新增/变更的对外 HTTP 接口。

`GET /api/openliststrm/pt-download-records` 的响应体新增一个字段 `failReasonCode`（来自 `DownloadRecordView`），是纯粹的字段追加，不影响现有字段与调用方；前端不升级也能正常工作（只是看不到新标签）。

## 6. 前端改动

只涉及问题 2 的展示，其余三项后端不可见。

`openlist-web/src/views/openlist/ptDownloadRecord/index.vue`（PC 端）与 `views-mobile/ptDownloadRecord/index.vue`（移动端）：现有失败提示块

```html
<div class="record-fail" v-if="item.state === 'FAILED'">
  <el-icon><WarningFilled /></el-icon>
  <span>{{ item.failReason || '未知原因' }}</span>
</div>
```

前面加一个小分类标签（`item.failReasonCode` 为空时不渲染，兼容历史数据）：

```html
<el-tag v-if="item.failReasonCode" size="small" :type="failReasonTagType(item.failReasonCode)">
  {{ failReasonCodeLabel(item.failReasonCode) }}
</el-tag>
```

两个映射函数直接写在各自 `<script setup>` 里（沿用现有 `stateLabel`/`stateTagType` 就地映射的写法，不抽公共 util——两个页面加起来就 6 行映射代码，抽取的收益小于引入一个新文件的认知成本）：

```ts
const failReasonCodeLabel = (code: string) => {
  switch (code) {
    case 'TORRENT_NOT_FOUND': return '种子丢失'
    case 'ZOMBIE_TIMEOUT': return '下载超时'
    default: return '其他原因'
  }
}
const failReasonTagType = (code: string): 'warning' | 'danger' => {
  return code === 'ZOMBIE_TIMEOUT' ? 'warning' : 'danger'
}
```

`DownloadRecordQuery`（`api/openlist/ptDownloadRecord.ts`）不新增筛选参数——问题描述只要求"展示分类标签"，没有要求按分类筛选，暂不加查询条件（YAGNI，真需要时后端加个 `failReasonCode` 查询参数即可，是很小的追加）。

## 7. 测试计划

对齐现有 `pt/subscription`、`pt/task` 包的 Mockito 单测风格。

### 7.1 `SubscriptionEngineTest`

- 现有测试默认桩 `recordService.list(any(Wrapper.class))` 返回空列表，会被 `loadDownloaderLoadCounts` 的查询复用（同一个通配 `any(Wrapper.class)` matcher）——需要确认现有全部用例在改动后仍然绿（不需要改动断言，因为负载查询只影响 `resolveDownloader` 内部的选择顺序，单下载器场景下选择结果不变）。
- 新增：两个启用下载器，都未被订阅指定，`recordService.list` 按 `downloader_id` 返回不同数量的在途记录 → 断言 `addTorrent` 被调用时用的是记录数更少的那个下载器（用 `ArgumentCaptor<PtDownloaderPlus>` 或校验传给 `downloaderClientFactory.get(...)` 的参数）。
- 新增：订阅显式指定了 `downloaderId`，即使该下载器当前负载更高 → 仍然选它（负载均衡不覆盖显式指定）。
- 新增：指定的 `downloaderId` 不在启用列表里 → 回退到负载最低的启用下载器（而不是"第一个"，与旧行为的断言需要同步更新）。
- 新增：同一批次里两个分组都命中同一个负载最低的下载器 → 第二个分组推送时应该已经感知到第一个分组的推送（负载 +1 后可能变成第二低），用两个负载几乎相等的下载器构造这个场景来验证 `downloaderLoadCache` 的批内自增生效。
- 复用现有 mock 基础设施时注意：新增下载器负载查询与"guid 去重"查询用的是同一个 `recordService.list(any(Wrapper.class))` 方法签名，新用例要用 `argThat` 按 `QueryWrapper` 的目标字段区分两类查询的桩数据，不能简单再叠加一个 `any(Wrapper.class)` 桩（后者会覆盖前一个桩，Mockito 对同一 matcher 的多次 `when` 只有最后一个生效）。

### 7.2 `DownloadTrackServiceTest`

- 构造函数签名变化：所有 `new DownloadTrackService(recordService, episodeService, completionSyncTrigger, 3)` 调用点需要改成 `new DownloadTrackService(recordService, episodeService, completionSyncTrigger, subscriptionService, 3, 24)`（新增 `subscriptionService` mock 与 `zombieTimeoutHoursDefault` 参数）；新增 `@Mock private IPtSubscriptionPlusService subscriptionService`，默认桩 `when(subscriptionService.listByIds(any())).thenReturn(List.of())`（对应"订阅查不到，回退全局默认值"分支，与现有全部用例的行为保持一致，不用逐个用例改断言）。
- 新增：`resolveZombieTimeoutMillis` 相关——
  - 订阅 `downloadOverride` 为 `{"zombieTimeoutHours": 1}`，构造一条 `age` 超过 1 小时但不超过 24 小时的记录 → 断言判定为 `ZOMBIE_TIMEOUT` 失败（验证覆盖值生效，若还用全局默认 24 小时则不会判失败，用这个差异断言覆盖确实起作用）。
  - 订阅 `downloadOverride` 为非法 JSON（如 `"{"`) → 回退全局默认值，不抛异常。
  - 订阅 `downloadOverride` 为 `{"zombieTimeoutHours": 0}` 或负数 → 视为无效覆盖，回退全局默认值（"0 小时超时"没有实际意义，等同于配置错误）。
  - `subscriptionService.listByIds` 返回空（订阅已删除）→ 回退全局默认值。
- 新增：`fail()` 分类断言——找不到种子的失败用例断言 `record.getFailReasonCode() == "TORRENT_NOT_FOUND"`；僵尸超时的失败用例断言 `== "ZOMBIE_TIMEOUT"`（在现有对应用例里各加一行断言即可，不需要新增用例）。

### 7.3 `FailReasonCode`

纯枚举，不单独写测试类（与 `DownloadRecordState`/`SubscriptionEpisodeState` 的既有处理方式一致，这两个枚举在代码库里也没有专门的测试类）。

### 7.4 手动验证

- `mvn clean package -DskipTests` 确认能编译（构造器签名变化影响面較大，先确认编译通过再跑单测）。
- 按 AGENTS.md 要求，涉及 bean 装配变化（`DownloadTrackService` 新增构造器依赖）需要做启动验证：`docker compose up -d --build --no-deps backend` 后确认容器 `restarts=0`，并确认 `MysqlDdl` 迁移执行后 `pt_download_record`/`pt_subscription` 两张表能查到新列（`SHOW COLUMNS`）。

## 8. 不做的事情（本次范围之外）

- 不做 `download_override`/`fail_reason_code` 的前端编辑界面（分类固定由后端枚举定义，覆盖值本次只能直接写库或后续单独设计表单）。
- 不做失败原因的历史数据回填/重新分类。
- 不做 `GRACE_MILLIS`（找不到种子的宽限期）的可配置化。
- 不做下载器容量上限、加权路由、基于外部监控指标（带宽/磁盘/延迟）的路由策略。
- 不做 `pushBest()` 跨调用的批内缓存（如 `SearchSupplementService` 一次补搜循环内多次调用 `pushBest` 仍各自查询）。
- 不做失败原因分类的前端筛选查询参数。
- 不改变 `PUSH_ERROR` 场景的现有"删记录回滚"策略，`FailReasonCode` 枚举里暂不加这个值（见 §2.2 的理由，加了也没有调用点会用到）。
