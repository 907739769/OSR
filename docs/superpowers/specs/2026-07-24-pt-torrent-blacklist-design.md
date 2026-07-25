# 种子/发布组黑名单设计

**日期**：2026-07-24
**作者**：Jack（与 Claude 协作）
**前置阅读**：
- [`pt/filter/TorrentFilterEngine.java`](../../../ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/filter/TorrentFilterEngine.java) + `FilterCriteria.java` + `FilterCriteriaFactory.java`（过滤择优链路，本设计的插入点）
- [`pt/subscription/SubscriptionEngine.java`](../../../ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java)（唯一调用 `filterEngine.evaluate()` 的地方）
- [`rename/extractor/impl/SourceAndGroupExtractor.java`](../../../ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/rename/extractor/impl/SourceAndGroupExtractor.java)（发布组解析，本设计直接复用其产物）

## 1. 背景与目标

### 1.1 问题

`TorrentFilterEngine` 目前只支持规则化过滤：做种数/体积/免费/分辨率白名单，以及标题级别的关键词包含/排除。没有针对**具体某一个种子**或**某个发布组**的手动屏蔽能力：

- 用户发现某个发布组反复推来转码质量差、带片头广告/水印的资源，唯一的规避手段是去全局过滤配置的 `exclude_keywords` 里手填发布组名字符串——容易和正常标题词冲突（发布组名有时是常见英文单词缩写），而且只能在"过滤规则"页全局修改，不能从"这条我不想要"的具体场景一键操作。
- 用户发现某个种子是假种（做种为 0 但一直挂着、内容与标题不符），只要这个种子**还没被推送过**（不在 `pt_download_record` 里），`excludeAlreadyRecorded` 的去重逻辑就不会拦它——它下一轮 RSS/补搜还会被重新评为候选，除非做种/体积等硬性条件恰好把它刷掉（假种数据经常伪造得刚好达标）。

### 1.2 目标

1. 支持按 **GUID 精确拉黑单个种子**：拉黑后，即便该种子从未被推送过、体积/做种数据看起来完全合规，也会被过滤引擎淘汰。
2. 支持按 **发布组拉黑**：拉黑后，该发布组的全部种子（不论标题其余部分）直接淘汰，不需要逐条维护关键词，也不会误伤同名普通词汇。
3. 提供两个操作入口：下载记录页"拉黑该种子 / 拉黑该发布组"一键按钮（覆盖最高频场景）+ 独立黑名单管理页（查看全部规则、手动新增发布组规则、解除拉黑）。
4. 发布组解析复用 `rename` 模块已有的 `SourceAndGroupExtractor`，不新增解析逻辑。

### 1.3 成功标准

1. 对某条下载记录点击"拉黑该种子"后，该种子的 guid 落入黑名单表；下一轮 RSS/补搜再抓到同一 guid 的候选时，无论做种/体积/分辨率是否达标都被淘汰，`pt_search_log` 里能看到"该种子已被手动拉黑"这一具体原因。
2. 对某条下载记录点击"拉黑该发布组"后，该记录标题解析出的发布组进入黑名单；下一轮任何候选种子只要标题能解析出同一发布组（大小写不敏感）都被淘汰。
3. 未命中任何黑名单规则的候选，过滤结果与改动前完全一致——现有 `TorrentFilterEngineFilterTest`（24 个用例）与 `TorrentFilterEnginePickBestTest` 不改一行断言、不改调用方式，原样通过。
4. 黑名单管理页可独立于下载记录新增/删除发布组规则，不依赖某条下载记录必须存在。

## 2. 架构

### 2.1 与现有链路的关系

不新建过滤主链路，在 `TorrentFilterEngine.rejectReason()` 现有判定链里插入两个新判定点；新增一个与 `FilterCriteria` 同一角色的不可变值对象 `TorrentBlacklist`——引擎本身仍然"纯逻辑，不读数据库"，生效的黑名单由调用方一次性查好传入。

新增内容：

1. **mybatisplus 三件套**：`PtTorrentBlacklistPlus`(domain) + `PtTorrentBlacklistPlusMapper` + `IPtTorrentBlacklistPlusService`/`PtTorrentBlacklistPlusServiceImpl`，风格照抄 `PtIndexerPlus`。Impl 额外提供两个业务方法 `blockRecordGuid(recordId, reason)` / `blockRecordReleaseGroup(recordId, reason)`，注入 `IPtDownloadRecordPlusService` 按 id 取记录，发布组场景复用 `MediaParser.parseLocal(title)`（沿用 `SubscriptionEngine` 里"`MediaParser` 非 Spring bean，手动 `new` 一个本地解析用途的实例"的既有写法）解析发布组。
2. **`pt.filter.TorrentBlacklist`**：不可变值对象，装 `Set<String> guidHashes` + `Set<String> releaseGroupsUpper`，静态工厂 `from(List<PtTorrentBlacklistPlus>)` 做分组归一化。放在 `pt.filter` 包而非 `mybatisplus`，与 `FilterCriteria` 同级，语义上都是"过滤引擎的输入"。
3. **`TorrentFilterEngine`** 新增两个重载 `evaluate(candidates, criteria, blacklist)` / `filter(candidates, criteria, blacklist)`；原有两参数签名保留，内部转调三参数版本并传 `TorrentBlacklist.EMPTY`——现有 24 个测试零改动。
4. **`SubscriptionEngine`** 新增构造器参数 `IPtTorrentBlacklistPlusService blacklistService`；`process()`/`pushBest()` 顶部各查一次全量黑名单（与 `globalConfig` 同一位置、同一生命周期：一次批量处理内只查一次，不随分组重复查库），构建 `TorrentBlacklist` 后经 `handleGroup` 传给 `filterEngine.evaluate`。
5. **`TorrentInfo`** 新增 `parsedReleaseGroup` 字段；`SubscriptionEngine.fillParsed()` 补一行 `torrent.setParsedReleaseGroup(info.getReleaseGroup())`——发布组解析早已存在于 `MediaParser` 的抽取链（`SourceAndGroupExtractor` 已把 `MediaInfo.releaseGroup` 填好），此前只是没人把这个字段从 `MediaInfo` 搬到 `TorrentInfo`，`SearchSupplementService.fillParsedAll()` 内部调的也是同一个 `fillParsed()`，两条链路（RSS、搜索补集）自动一起覆盖。
6. **前端**：下载记录卡片新增两个按钮；新增独立管理页 `ptTorrentBlacklist`（仿 `ptIndexer` 的 `useTaskList` CRUD 模式）。

### 2.2 数据流

```
①「过滤链路」— rejectReason() 判定顺序（新增项前后加 ▶）：

  ▶ 0. GUID 命中黑名单           —— 最先判定，见 2.3 取舍
     1. 做种数低于下限
     2. 体积超出上下限
     3. 非免费种（要求仅免费时）
     4. 分辨率不在白名单
     5. 标题为空
  ▶ 5.5 发布组命中黑名单         —— 依赖标题解析结果，必须在"标题为空"之后
     6. 命中排除关键词
     7. 未命中任何包含关键词
  → 通过


②「入口一：下载记录页按钮」

PtDownloadRecordRestController
  POST /{id}/blacklist-guid           POST /{id}/blacklist-release-group
        │                                       │
        ▼                                       ▼
  blacklistService.blockRecordGuid(id, reason)   blacklistService.blockRecordReleaseGroup(id, reason)
        │ 取 PtDownloadRecordPlus.guidHash            │ mediaParser.parseLocal(record.getTitle())
        │ 已存在(type=GUID,value=guidHash) → 幂等返回  │   .getReleaseGroup() 为空 → 报错"无法解析出发布组"
        ▼                                       │ 已存在(type=RELEASE_GROUP,value=大写发布组) → 幂等返回
  插入 pt_torrent_blacklist                      ▼
                                          插入 pt_torrent_blacklist


③「入口二：黑名单管理页」

PtTorrentBlacklistRestController(BaseCrudRestController)
  GET /list   DELETE /{id}   POST(新增，仅 RELEASE_GROUP，见 2.3)
        │
        ▼
  PtTorrentBlacklistPlusServiceImpl.save()/updateById()
  （已重载：拒绝 type=GUID 的新增/编辑请求，归一化 RELEASE_GROUP 的 value 为大写去空白）


④「下一轮 RSS/补搜」

SubscriptionEngine.process()/pushBest()
        │
        ├─▶ blacklist = TorrentBlacklist.from(blacklistService.list())   // 与 globalConfig 同一次性查询
        │
        ▼
  handleGroup(... blacklist)
        │
        ▼
  filterEngine.evaluate(fresh, criteria, blacklist)   // 命中黑名单的候选被淘汰，原因落 pt_search_log
```

### 2.3 关键设计取舍

- **用重载而非直接改 `evaluate`/`filter` 签名**：仓库里现有 24 处测试直接 `new FilterCriteria(...)` 后调 `engine.filter(candidates, criteria)`，若改签名要逐一改造这些已经稳定覆盖过滤规则的用例。重载对旧调用零成本、新调用显式传黑名单，符合"过滤引擎是纯函数、调用方负责组装输入"的既有分工（`FilterCriteria` 也是调用方组装好再传入，而非引擎自己拼）。
- **GUID 判定放在判定链最前面**：不依赖标题解析、不依赖任何统计字段，是最便宜的判定；而且"拉黑一个具体种子"是用户的强确定性意图（已经认定这是假种/坏资源），语义上应该比"做种数不够"这类软性阈值更早生效——即使某个假种伪造出很高的做种数，也应该先被这条硬规则拦下，不必等到后面的判定才淘汰。
- **发布组判定放在"标题为空"检查之后、`excludeKeywords` 之前**：判定依赖 `parsedReleaseGroup`，而这个字段本质上是标题解析的产物，与 `excludeKeywords`/`includeKeywords` 同样要求标题非空，因此不能早于该检查；又因为它和 `excludeKeywords` 语义最接近（都是"标题相关的黑名单式淘汰"），紧邻放置便于以后维护者把这一段读作一个整体。
- **发布组匹配基于 `parsedReleaseGroup` 整词比较，而非对标题做子串 `contains`**：种子标题里发布组只是结尾的一个 token（如 `-CHDWEB`），若直接对标题 `contains("CHDWEB")` 判断，会误伤"CHDWEB2""XCHDWEB"这类近似但其实是别的组的种子。`SourceAndGroupExtractor` 已经把发布组从标题里结构化切出来（`GROUP_END`/`GROUP_BRACKET` 两个正则，逻辑已验证过），直接复用这个已解析字段做整词比较更准确，也避免重复实现一遍标题切分逻辑。
- **GUID 黑名单存 `guid_hash` 而非原始 `guid`**：与 `pt_download_record` 的去重键用同一套哈希算法（`GuidHasher.hash`），一是避免原始 guid 里可能带的 apikey 出现在黑名单表里被管理页展示，二是命中判断本身就是等值比较，没必要多存一份长字符串。原始标题另存 `display_value` 字段，只给管理页展示用，不参与匹配。
- **发布组归一化为大写**：索引器和用户手填的大小写混乱是常态（`chdweb`/`CHDWEB`/`ChdWeb`），与 `TorrentFilterEngine` 现有分辨率白名单的 `containsIgnoreCase` 保持同一套"大小写不敏感"处理哲学。
- **两个入口都做，而非先只做管理页**：下载记录页按钮解决的是最高频场景——用户是在看下载记录时才会意识到"这条不想要"；如果只有管理页，用户得先自己想办法弄清楚 guid/发布组再跑去另一个页面填表，摩擦大到大概率没人会用。两个入口共享同一张表、同一个 Service，不是重复实现——管理页的"新增"走 `BaseCrudRestController` 通用 `add`，按钮走两个语义化的 `block-*` 端点，殊途同归。
- **管理页手动新增只支持 `RELEASE_GROUP` 类型，不支持 `GUID` 类型**：`value` 列对 GUID 类型存的是哈希值，没有用户会手动填一段 SHA-256；而且手填一段无法验证真伪的哈希字符串对过滤没有意义。GUID 类型的黑名单只能通过"拉黑该种子"按钮产生（后端直接从已知的 `PtDownloadRecordPlus.guidHash` 取值，不经过用户手填）。管理页对 GUID 类型的规则仍然可查看（用 `display_value` 展示原标题）与删除，只是新增/编辑入口拒绝该类型，在 `PtTorrentBlacklistPlusServiceImpl.save()`/`updateById()` 里显式校验拒绝，而不是指望前端表单不出现这个选项就够了（后端必须是最终防线）。
- **不在 `FilterCriteria` 里加黑名单字段**：`FilterCriteria` 是"全局配置 + 订阅级覆盖"合并后的结果，语义上是每个订阅可能不同的过滤门槛；黑名单是与订阅无关的全局硬规则，混进 `FilterCriteria` 会让 `FilterCriteriaFactory.build(global, override)` 这个纯配置合并函数背上不相关的职责，也会导致同一批黑名单数据在每个订阅的 `FilterCriteria` 里被重复构建。作为 `evaluate()` 的独立参数，语义边界更清晰，且只在 `process()`/`pushBest()` 顶部构建一次。
- **命中判断前先判断黑名单是否为空再决定要不要算哈希/取字段**：`rejectReason()` 在每次 RSS 轮询里对每个候选都会被调用一次；默认没配置黑名单时（`guidHashes`/`releaseGroupsUpper` 均为空集合），两个新判定直接跳过，不对种子的 guid 做 SHA-256 计算、也不比较发布组，不给"功能没用上时也要为它付出计算成本"的情况留口子。

## 3. 数据模型改动

新增表 `pt_torrent_blacklist`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | int unsigned | 自增主键 |
| `type` | varchar(20) | 拉黑类型：`GUID` / `RELEASE_GROUP` |
| `value` | varchar(255) | 匹配键：`GUID` 类型存 `guid` 的 SHA-256 十六进制（与 `pt_download_record.guid_hash` 同算法）；`RELEASE_GROUP` 类型存归一化为大写的发布组名 |
| `display_value` | varchar(512) | 展示用原文：`GUID` 类型为种子标题，`RELEASE_GROUP` 类型为原始大小写形式的发布组名；仅供管理页展示，不参与匹配 |
| `reason` | varchar(255) | 拉黑原因；按钮触发时有默认文案，管理页手动新增时用户可填 |
| `create_time` / `update_time` | datetime | 沿用 `BaseEntity` |

唯一索引 `uk_type_value(type, value)`：防止同一种子/发布组被重复拉黑，也让"再次点击同一个按钮"天然幂等（服务层查一次是否已存在，不依赖抛唯一键异常）。

```sql
-- ruoyi-common/src/main/resources/sql/20260738-pt-torrent-blacklist.sql
CREATE TABLE IF NOT EXISTS `pt_torrent_blacklist` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '拉黑类型 GUID/RELEASE_GROUP',
    `value` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '匹配键：GUID类型存guid的SHA-256哈希，RELEASE_GROUP类型存归一化(大写)的发布组名',
    `display_value` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '展示用原文，仅供管理页展示，不参与匹配',
    `reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '拉黑原因',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_type_value`(`type`, `value`) USING BTREE
) COMMENT = 'PT 种子/发布组手动黑名单';

-- 菜单：PT下载管理(2070) 下新增第7项，照抄 20260731 的写法（页面与接口同批上线，visible='0' 即显示）
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2071, 'PT黑名单', 2070, 7, '/openlist/ptTorrentBlacklist', '', 'C', '0', '1', 'openliststrm:ptTorrentBlacklist:view', 'fa fa-ban', 'admin', '2026-07-24 00:00:00', '', NULL, 'PT 种子/发布组手动黑名单管理');
```

`fa fa-ban` 之前未出现在 `useMenuIcon.ts` 的 `iconMap` 里，需要同批补充映射（详见第 4 节），否则会重蹈 `20260428`/`20260737` 那次"图标类名没进 iconMap 导致侧边栏图标不显示"的坑。

`pt_subscription`、`pt_download_record`、`pt_filter_config` 均不改动。

## 4. 后端组件改动清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `mybatisplus/domain/PtTorrentBlacklistPlus.java` | 新建 | `@TableName("pt_torrent_blacklist")`，字段见第 3 节 |
| `mybatisplus/mapper/PtTorrentBlacklistPlusMapper.java` | 新建 | `extends BaseMapper<PtTorrentBlacklistPlus>`，空接口，同 `PtIndexerPlusMapper` |
| `mybatisplus/service/IPtTorrentBlacklistPlusService.java` | 新建 | `extends IService<PtTorrentBlacklistPlus>`；新增 `blockRecordGuid(Integer recordId, String reason)`、`blockRecordReleaseGroup(Integer recordId, String reason)`，返回值区分"新增成功"/"已存在(幂等)" |
| `mybatisplus/service/impl/PtTorrentBlacklistPlusServiceImpl.java` | 新建 | 注入 `IPtDownloadRecordPlusService`；持有 `private final MediaParser mediaParser = new MediaParser(null, null)`（非 bean，理由同 `SubscriptionEngine`）；重载 `save()`/`updateById()` 拒绝 `type=GUID`、并把 `RELEASE_GROUP` 的 `value` 归一化为 `trim().toUpperCase()` |
| `pt/filter/TorrentBlacklist.java` | 新建 | record，字段 `guidHashes`/`releaseGroupsUpper`；`EMPTY` 常量；`from(List<PtTorrentBlacklistPlus>)` 静态工厂 |
| `pt/filter/TorrentFilterEngine.java` | 改动 | `rejectReason` 插入 GUID/发布组两处判定；新增 3 参重载 `evaluate`/`filter`，旧签名转调新签名传 `TorrentBlacklist.EMPTY` |
| `pt/model/TorrentInfo.java` | 改动 | 新增字段 `parsedReleaseGroup` |
| `pt/subscription/SubscriptionEngine.java` | 改动 | 构造器新增 `IPtTorrentBlacklistPlusService blacklistService`；`process()`/`pushBest()` 顶部各查一次黑名单；`handleGroup` 新增形参并传给 `filterEngine.evaluate`；`fillParsed()` 补一行赋值 `parsedReleaseGroup` |
| `controller/api/PtDownloadRecordRestController.java` | 改动 | 新增 `POST /{id}/blacklist-guid`、`POST /{id}/blacklist-release-group`，转调 `blacklistService` 对应方法，`IllegalArgumentException` 转 `Result.error` |
| `controller/api/PtTorrentBlacklistRestController.java` | 新建 | `extends BaseCrudRestController<IPtTorrentBlacklistPlusService, PtTorrentBlacklistPlus>`，`buildQueryWrapper` 支持按 `type` 精确、`displayValue` 模糊查询 |
| `ruoyi-common/.../sql/20260738-pt-torrent-blacklist.sql` | 新建 | 建表 + 菜单，见第 3 节 |

不改动：`FilterCriteria`、`FilterCriteriaFactory`、`SearchSupplementService`（`fillParsedAll` 内部调用的 `SubscriptionEngine.fillParsed` 已经覆盖发布组解析，两条链路共用一处改动）、`DownloadTrackService`、`pt_download_record`/`pt_subscription` 表结构。

## 5. API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/openliststrm/pt-torrent-blacklists` | 分页列表，支持 `type`/`displayValue` 查询（`BaseCrudRestController` 通用） |
| POST | `/api/openliststrm/pt-torrent-blacklists` | 新增（服务层拒绝 `type=GUID`） |
| PUT | `/api/openliststrm/pt-torrent-blacklists` | 修改（同上限制） |
| DELETE | `/api/openliststrm/pt-torrent-blacklists/{id}` | 删除（解除拉黑），两种类型均可删 |
| POST | `/api/openliststrm/pt-download-records/{id}/blacklist-guid` | 按下载记录拉黑该种子（GUID 维度），请求体可选 `{ reason }` |
| POST | `/api/openliststrm/pt-download-records/{id}/blacklist-release-group` | 按下载记录拉黑该发布组，请求体可选 `{ reason }`；记录标题解析不出发布组时返回错误 |

## 6. 前端改动

- **`api/openlist/ptTorrentBlacklist.ts`**（新建）：`getPtTorrentBlacklistListApi`/`addPtTorrentBlacklistApi`/`deletePtTorrentBlacklistApi`，仿 `ptIndexer.ts`。
- **`api/openlist/ptDownloadRecord.ts`**（改动）：新增 `blacklistGuidApi(id, reason?)`、`blacklistReleaseGroupApi(id, reason?)`。
- **`composables/usePtTorrentBlacklist.ts`**（新建）：仿 `usePtIndexer.ts`，基于 `useTaskList` 封装列表+新增+删除；新增表单只暴露"发布组"一种类型（不提供 GUID 选项，呼应 2.3 的取舍），字段：`value`（发布组名，前端也 `trim`+大写预览但最终以后端归一化为准）、`reason`。
- **`views/openlist/ptTorrentBlacklist/index.vue`**（新建）：卡片/表格列表，列出 `type`、`displayValue`、`value`（GUID 类型显示为脱敏短哈希）、`reason`、`createTime`，操作列只有"删除"。
- **`composables/usePtDownloadRecord.ts`**（改动）：新增 `blacklistingIds`（防重复点击）+ `handleBlacklistGuid(row)`/`handleBlacklistReleaseGroup(row)`，调用上面两个新 API，成功后 `ElMessage.success`（幂等命中时提示"已在黑名单中"）。
- **`views/openlist/ptDownloadRecord/index.vue`**（改动）：`.record-actions` 区域新增两个 `link` 按钮"拉黑该种子"/"拉黑该发布组"，不再仅在 `state === 'FAILED'` 时才显示这个区域（黑名单操作对任意状态的记录都有意义，包括已完成但事后发现质量差的资源）。
- **`composables/useMenuIcon.ts`**（改动）：`iconMap` 新增 `'fa fa-ban': CircleClose`（`@element-plus/icons-vue` 已有该图标，仅需补充 import 与映射项）。

## 7. 测试计划

对齐现有 `pt/filter`、`pt/subscription` 包的测试风格：

- **`TorrentFilterEngineFilterTest`**：不改动任何现有用例；新增用例针对 3 参重载：
  - GUID 命中黑名单 → 淘汰，原因文案包含"拉黑"
  - GUID 未命中（黑名单非空但不含该 guid）→ 不受影响，走原有判定链
  - 发布组命中黑名单（大小写不一致也应命中，如黑名单存 `CHDWEB`，种子 `parsedReleaseGroup` 为 `chdweb`）→ 淘汰
  - 标题为空时即使 `parsedReleaseGroup` 恰好非空（构造异常输入）也应先被"标题为空"淘汰，不应该走到发布组判定（验证判定顺序）
  - 黑名单同时命中 GUID 和做种数不足 → 断言原因是 GUID 命中（验证判定顺序：GUID 检查最先执行）
  - `TorrentBlacklist.EMPTY` 或未传黑名单参数（两参旧签名）→ 行为与改动前完全一致
- **`TorrentBlacklistTest`**（新建）：`from()` 对大小写混合的发布组输入做归一化；`null` 列表输入返回 `EMPTY` 等价的空集合；重复 value 去重。
- **`PtTorrentBlacklistPlusServiceImplTest`**（新建，mock `IPtDownloadRecordPlusService`）：
  - `blockRecordGuid`：记录存在且未拉黑过 → 新增一行 `type=GUID, value=记录的guidHash`；重复调用同一记录 → 不重复插入，返回"已存在"；记录不存在 → 抛 `IllegalArgumentException`
  - `blockRecordReleaseGroup`：标题能解析出发布组 → 新增一行 `value` 为大写发布组；标题解析不出发布组（如纯中文无后缀标题）→ 抛 `IllegalArgumentException`
  - `save()`/`updateById()`：`type=GUID` 的入参一律拒绝；`type=RELEASE_GROUP` 的 `value` 落库前被归一化为去空白+大写
- **`SubscriptionEngineTest`**（改动，构造器新增参数）：
  - 现有构造点补上 mock 的 `IPtTorrentBlacklistPlusService`（返回空列表，等价于不影响现有全部用例的断言）
  - 新增用例：黑名单非空且候选命中 → `handleGroup` 不占位不推送，`searchLogService` 记录到的 `Verdict` 里能看到黑名单淘汰原因
- **前端**：`ptTorrentBlacklist` 页面与下载记录页按钮暂不补 Playwright E2E（现有 E2E 覆盖面本来就以核心链路为主），人工验证：拉黑后管理页能看到记录、删除后下一轮轮询恢复正常评选。

## 8. 不做的事情（本次范围之外）

- 不支持按标题关键词以外的维度拉黑（如按索引器整体拉黑）——现有 `pt_indexer.enabled` 已经能整站停用一个索引器，语义重复。
- 不做"拉黑后自动清理已在途/已完成的关联下载记录"——黑名单只影响后续评选，不回溯处理历史记录（用户如果想删除已下载的文件是刮削/文件管理的职责，不属于本设计范围）。
- 不做黑名单规则的批量导入/导出。
- 不做"临时拉黑（带过期时间）"，只有永久拉黑 + 手动删除解除；如果后续出现"限时屏蔽某发布组直到它修复烂种问题"的真实需求，再加 `expire_time` 字段（YAGNI）。
- 管理页新增不支持 `GUID` 类型，理由见 2.3；如果后续有"已知某个 guid 但没有对应下载记录"的场景（比如从别处看到某个种子的分享链接想提前拉黑），再考虑放开，需要额外设计"如何让用户安全地输入/校验一个 guid"。
