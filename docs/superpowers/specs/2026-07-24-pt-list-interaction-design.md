# 订阅/下载记录列表交互优化设计

**日期**：2026-07-24
**作者**：Jack（与 Claude 协作）
**前置阅读**：
- `docs/superpowers/specs/2026-07-21-pt-subscription-download-design.md`（订阅/下载记录数据模型与整体链路）
- `docs/superpowers/specs/2026-07-23-subscription-create-search-backfill-design.md`（建订阅补搜设计，本设计复用其中的"搜索补集"能力）
- `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtSubscriptionRestController.java`、`PtDownloadRecordRestController.java`（现有单条操作接口）
- `openlist-web/src/views/openlist/ptSubscription/index.vue` + `composables/usePtSubscription.ts`
- `openlist-web/src/views/openlist/ptDownloadRecord/index.vue` + `composables/usePtDownloadRecord.ts`
- `openlist-web/src/composables/useTaskList.ts`（通用 CRUD composable，订阅页复用了它）

## 1. 背景与目标

订阅列表（`ptSubscription`）和下载记录列表（`ptDownloadRecord`）自建成以来一直是"卡片网格 + 每行按钮"的形态，随着功能增多暴露出四个具体问题：

1. 暂停/恢复/删除/重试都只能逐条点，订阅或失败记录一多，批量场景（比如季末集中清理已完结订阅、批量重试因索引器抖动导致的一批失败记录）操作成本很高。
2. 订阅卡片 `sub-actions` 里堆了 8 个 `link` 按钮（进度/搜索补齐/对账/下载记录/匹配日志/过滤规则/暂停或恢复/删除），移动端宽度下容易换行到 3-4 行，视觉噪音大，高频操作和低频排查操作混在一起。
3. 列表默认按 `id` 倒序（新建的排最前），无法按"最近一次真正抓到资源的时间"排序，用户想找"最近没动静的订阅"逐条肉眼找耗时。
4. 建订阅弹窗的 TMDb 选片表格只有文字（标题/年份/TMDb ID），同名不同版本或简繁标题相近的作品全靠文字辨认，容易选错。

**目标**：在不引入新的列表框架、不破坏现有 composable 分层约定的前提下，把这四项交互体验补齐。

**范围限定**：只做这四项前端交互 + 支撑它们的最小后端接口/查询改动；不改 `SubscriptionEngine` 推送决策、不改 `DownloadTrackService` 失败判定、不改移动端（`views-mobile/` 下确认没有 PT 相关页面，无需同步）、不改 Telegram 通知内容。

## 2. 现状盘点（决定设计取舍的关键事实）

写代码前读现有实现发现的、直接影响本次设计的几个事实：

- **两个列表页的选中态基础设施不对等**。`usePtSubscription` 内部用 `useTaskList` 打底，天然带有 `selectedIds`/`single`/`multiple`/`handleSelectionChange`/`handleDelete`（批量分支）/`handleExecute` 这套机制——但这套机制是为 `<el-table>` 的 `@selection-change` 事件设计的（`handleSelectionChange(selection: any[])` 直接拿 el-table 吐出来的整行对象数组）。订阅页现在用的是 `card-grid` 的 `v-for` 卡片，不是 `el-table`，所以这套选中态目前完全没有接线到界面上。`usePtDownloadRecord` 干脆没有复用 `useTaskList`（注释写明"只读列表+失败重试，硬凑 CRUD composable 只会留一堆空实现"），选中态要从零建。
  → 结论：订阅页可以复用 `selectedIds` 这个 ref 本身（继续用它存 id 数组），但不能指望 `handleSelectionChange`；改为卡片上放 checkbox，手动对 `selectedIds.value` 做增删。下载记录页新增一套同构的最小选中态，不硬套 `useTaskList`。
- **批量接口在本项目里已有稳定约定**，`copyTask`/`copyRecord`/`strmTask`/`strmRecord`/`renameDetail`/`renameTask`/`renameOrphan` 全部是 `POST /xxx/batchXxx`，参数用 `@RequestParam("ids") String ids`（逗号拼接的字符串，前端 `ids.join(',')`），后端用 `Convert.toStrArray(ids)` 转数组。本次批量接口照此惯例实现，不用 `@RequestBody List<Integer>`（只有个别文件这么写，非主流）。
- **`PtSubscriptionRestController` 的列表查询完全绕开了 `IPtSubscriptionPlusService` 的自定义方法**。`BaseCrudRestController.list()` 是 `selectPage(service.getBaseMapper(), buildQueryWrapper(entity))`，直接操作 `Wrapper`，`IPtSubscriptionPlusService` 现在只有一个 `listActive()`（给 RSS 轮询用，跟列表查询无关）。也就是说"排序能力"这个需求，真正该改的落点是 `PtSubscriptionRestController#buildQueryWrapper()`，不是 service 接口——第 5 节详细说明为什么设计上选择偏离最初"IPtSubscriptionPlusService 查询加 orderBy 参数"的字面表述。
- **`BaseController.selectPage()` 里已经有一套通用排序机制，但有 bug，不能直接借用**：它读 `PageDomain.getOrderBy()`（拼成 `"column_name asc"` 或 `"column_name desc"` 的完整字符串），却固定调用 `mpPage.addOrder(OrderItem.desc(orderByColumn))`——不管 `isAsc` 是什么值都用 `.desc(...)`，而且把整个"列名+方向"字符串当列名传给 `.desc()`，非默认方向时会拼出 `ORDER BY last_match_time asc DESC` 这种非法 SQL。这个共享基础设施的 bug 不在本次范围内修（会影响站内所有走 `BaseCrudRestController` 的列表页，风险面太大），本设计的排序需求**不经过**这条路径，自己在 `buildQueryWrapper()` 里加分支，规避这个坑。
- **`TmdbSearchItem` 已经有 `posterPath` 字段**（`pt/subscription/dto/TmdbSearchItem.java:32`），后端不需要改动，海报缩略列纯前端工作。
- **`PtSubscriptionPlus` 已经有 `lastMatchTime`（`last_match_time`）字段**且列表卡片已经在展示"上次命中"，排序需求不需要新增数据库字段。MySQL 对 `ORDER BY ... DESC` 里的 `NULL` 排在最后（从未命中过的订阅自然排到最下面），符合直觉，不需要额外的 `COALESCE`。
- **下载记录的失败重试语义**（读 `DownloadRecordAdminService.retry()`）：只有 `state=FAILED` 且关联订阅 `status=ACTIVE` 才能重试，重试前会把该记录关联的 `BLOCKED` 集重置回 `MISSING`。批量重试必须复用这个方法而不是绕开它，否则会漏掉"重置 BLOCKED 集"这一步。

## 3. 设计一：批量操作

### 3.1 订阅列表：批量暂停 / 恢复 / 删除

**交互**：`action-bar` 新增一个"批量操作"文本按钮（与现有"显示搜索"按钮并列），点击后进入选择模式——每张卡片左上角出现 checkbox，卡片区上方浮出一条选中态工具条：`已选 N 项 | 批量暂停 | 批量恢复 | 批量删除 | 取消`。三个批量按钮在 `N=0` 时禁用。退出选择模式或翻页不清空已选（翻页场景较少，清空反而容易让用户以为丢了选择；如果后续发现困惑可以再加"跨页保留"提示，本次不做）。

**为什么不用 el-table 自带多选列**：卡片网格本来就不是表格，硬套 `<el-table>` 的 `type="selection"` 需要把整个列表从卡片布局改成表格布局，改动面远超"加个批量操作"的量级，也会牺牲海报图的展示空间。checkbox overlay 是这类卡片网格常见的最小改法。

**后端接口**（沿用第 2 节确认的项目级批量惯例）：

```
POST /api/openliststrm/pt-subscriptions/batchPause?ids=1,2,3
POST /api/openliststrm/pt-subscriptions/batchResume?ids=1,2,3
POST /api/openliststrm/pt-subscriptions/batchDelete?ids=1,2,3
```

`batchPause`/`batchResume` 内部逐条调用 `SubscriptionService` 已有的单条 `pause(subId)`/`resume(subId)`，但不是简单循环——用 try/catch 包住每一条，一条订阅已被并发删除（`requireSubscription` 抛 `IllegalArgumentException`）不应该让同批次其余订阅的暂停/恢复也失败。新增聚合结果 DTO：

```java
// pt/subscription/dto/BatchOperationResult.java
public class BatchOperationResult {
    private int successCount;
    private List<Integer> failedIds; // 找不到订阅等原因跳过的 id
}
```

`SubscriptionService` 新增两个编排方法（复用已有单条逻辑，风格与 `SearchSupplementService.supplementOnCreate()` 的"循环 + 每项 try/catch"一致）：

```java
public BatchOperationResult pauseBatch(List<Integer> ids) {
    int success = 0;
    List<Integer> failed = new ArrayList<>();
    for (Integer id : ids) {
        try { pause(id); success++; }
        catch (IllegalArgumentException e) { failed.add(id); }
    }
    return new BatchOperationResult(success, failed);
}
// resumeBatch 同构
```

`batchDelete` **不**走 `SubscriptionService`——现有单条 `delete()` 的"先删 episode 再删主表"两步逻辑就直接写在 `PtSubscriptionRestController#delete()` 里（覆写基类那个方法的注释说得很清楚：这是纯 CRUD 组合，不是订阅域业务规则）。批量版本保持同样的落点，但用 `IN` 一次性执行，不逐条循环（删除量可能到几十条，没必要几十次 SQL）：

```java
@PostMapping("/batchDelete")
public Result<Void> batchDelete(@RequestParam("ids") String ids) {
    if (StringUtils.isBlank(ids)) return Result.error("请选择要删除的订阅");
    List<Integer> idList = Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
    episodeService.remove(new QueryWrapper<PtSubscriptionEpisodePlus>().in("sub_id", idList));
    boolean removed = service.removeByIds(idList);
    return removed ? Result.success() : Result.error("删除失败");
}
```

**前端**（`usePtSubscription.ts`）：

- `selectionMode = ref(false)`：控制 checkbox 和批量工具条的显隐。
- 复用 `useTaskList` 已带出来的 `selectedIds`（不复用 `handleSelectionChange`），新增 `toggleSubSelect(row)`（在 `selectedIds.value` 里 push/splice row.id）与 `isSubSelected(id)`。
- 新增 `handleBatchPause()`/`handleBatchResume()`：`ElMessageBox.confirm` 二次确认 → 调 `batchPauseSubscriptionApi(selectedIds.value)`/`batchResumeSubscriptionApi(...)` → 按返回的 `successCount`/`failedIds.length` 提示"成功 N 项，M 项已跳过（可能已被删除）" → 清空选中 → `getList()`。不复用 `useTaskList` 的 `handleExecute`，因为那个函数只接一个 `executeApi`，这里要接两个不同动作，各自要不同的确认文案。
- `handleDelete`（`useTaskList` 已有）在没有单条 `row` 时走批量分支：给 `useTaskList` 配置项传入 `batchDeleteApi: batchDeletePtSubscriptionApi`，`selectedIds.value` 非空时点"批量删除"直接调用它，零改动复用现成逻辑。
- `api/openlist/ptSubscription.ts` 新增：

```ts
export function batchPauseSubscriptionApi(ids: number[]) {
  return request.post<any, { successCount: number; failedIds: number[] }>(
    '/openliststrm/pt-subscriptions/batchPause', null, { params: { ids: ids.join(',') } })
}
export function batchResumeSubscriptionApi(ids: number[]) { /* 同构，路径 batchResume */ }
export function batchDeletePtSubscriptionApi(ids: number[]) {
  return request.post('/openliststrm/pt-subscriptions/batchDelete', null, { params: { ids: ids.join(',') } })
}
```

### 3.2 下载记录列表：批量重试失败记录

**交互**：与订阅页同款"批量操作"入口，但 checkbox **只出现在 `state === 'FAILED'` 的卡片上**（非失败记录本来就没有"重试"这个动作，选了也没意义，不如从物理上不给选，比选完再静默跳过更直白）。工具条：`已选 N 项 | 批量重试`。

**后端接口**：

```
POST /api/openliststrm/pt-download-records/batchRetry?ids=1,2,3
```

`DownloadRecordAdminService` 新增 `retryBatch`，循环调用已有 `retry(recordId)`，用 try/catch 吞掉单条的 `IllegalArgumentException`（记录已被并发处理成非 FAILED、订阅已暂停等——这些是预期内的"跳过"而不是系统异常）：

```java
public BatchRetryResult retryBatch(List<Integer> ids) {
    int pushed = 0, skipped = 0;
    for (Integer id : ids) {
        try {
            SupplementResult r = retry(id);
            if (r.isPushed()) pushed++; else skipped++;
        } catch (IllegalArgumentException e) {
            skipped++;
        }
    }
    return new BatchRetryResult(ids.size(), pushed, skipped);
}
```

`BatchRetryResult`（`pt/task/dto/`）：`{ total, pushedCount, skippedCount }`，前端据此提示"已重新推送 N 条，M 条未搜到/已跳过"。

**前端**（`usePtDownloadRecord.ts`，本来就没复用 `useTaskList`，同款新增最小选中态）：

```ts
const selectionMode = ref(false)
const selectedIds = ref<number[]>([])
const toggleRecordSelect = (row: any) => { /* push/splice */ }
const handleBatchRetry = async () => {
  if (!selectedIds.value.length) return
  await ElMessageBox.confirm(`确认批量重试选中的 ${selectedIds.value.length} 条失败记录？`, '提示', { type: 'warning' })
  const result = await batchRetryPtDownloadRecordApi(selectedIds.value)
  ElMessage.success(`已重新推送 ${result.pushedCount} 条，${result.skippedCount} 条未搜到或已跳过`)
  selectedIds.value = []
  getList()
}
```

`api/openlist/ptDownloadRecord.ts` 新增 `batchRetryPtDownloadRecordApi`，用法与上面 `batchPauseSubscriptionApi` 同构。

## 4. 设计二：订阅卡片操作按钮收纳

现有 `sub-actions` 里 8 个按钮：进度、搜索补齐、对账、下载记录、匹配日志、过滤规则、暂停/恢复、删除。

**保留在外层**（高频 / 后果重大，需要一眼看到）：

| 按钮 | 理由 |
|---|---|
| 进度 | 查看这个订阅"缺了什么"是最常见的动作 |
| 下载记录 | 跳转到下载记录页，高频导航入口 |
| 暂停 / 恢复 | 直接影响订阅是否继续被 RSS/自动补搜处理，状态切换类操作应该显眼 |
| 删除 | 破坏性操作，故意保持显眼而不是藏进菜单，避免用户为了删除还要先展开菜单，反而增加"手滑批量删除"路径上的确认成本变化——外层删除还是原来的单条 + 二次确认，风险不因收纳而降低 |

**收进 `el-dropdown` "更多"**（低频 / 排查向，不需要常驻）：

| 按钮 | 理由 |
|---|---|
| 对账 | 手动与媒体库对账是兜底动作，正常情况下 `LibrarySyncTask` 会定时做 |
| 匹配日志 | 排查"为什么没抓到"时才会用 |
| 过滤规则 | 配置类操作，改的频率远低于查看频率 |
| 搜索补齐 | 建订阅时已有自动补搜（见 2026-07-23 设计），手动搜索补齐现在更多是补搜失败后的人工兜底 |

**实现**：纯模板层重排，不改 `usePtSubscription.ts` 的任何导出（4 个"更多"里的函数 `handleRefresh`/`showSearchLogs`/`openFilterOverride`/`openSeasonSearch` 已经是 composable 现成导出的函数，直接换个触发方式）：

```html
<div class="sub-actions">
  <el-button link type="primary" @click="showProgress(item)">进度</el-button>
  <el-button link type="primary" @click="goDownloadRecords(item)">下载记录</el-button>
  <el-button v-if="item.status !== 'PAUSED'" link type="warning" @click="handlePause(item)">暂停</el-button>
  <el-button v-else link type="success" @click="handleResume(item)">恢复</el-button>
  <el-button link type="danger" @click="handleRemove(item)">删除</el-button>
  <el-dropdown trigger="click" @command="(cmd: string) => handleMoreCommand(cmd, item)">
    <el-button link type="info">更多<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="refresh">对账</el-dropdown-item>
        <el-dropdown-item command="logs">匹配日志</el-dropdown-item>
        <el-dropdown-item command="filter">过滤规则</el-dropdown-item>
        <el-dropdown-item command="search">搜索补齐</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</div>
```

`index.vue` 里新增一个 `handleMoreCommand(cmd, row)` 做 command → 现有函数的 switch 分发，不新增业务逻辑。

## 5. 设计三：按"上次命中时间"排序

**前端**：搜索区加一个排序下拉（`el-select`，与"状态"筛选并列），选项：`默认（最新创建）` / `上次命中时间`。选中后写入 `queryParams.sortBy`（`SearchParams` 本来就是 `[key: string]: any` 的宽松类型，加字段不用改 `PtSubscriptionQuery` 接口外的任何类型定义），`@change` 触发 `handleQuery()`。

**后端为什么落在 `buildQueryWrapper()` 而不是 `IPtSubscriptionPlusService`**：如第 2 节所述，`BaseCrudRestController.list()` 从不调用 `IPtSubscriptionPlusService` 的自定义方法，只用 `service.getBaseMapper()` + `buildQueryWrapper(entity)` 拼的 `Wrapper`。`title`/`mediaType`/`status` 这三个既有筛选条件也是在这个方法里读 `entity` 的字段拼条件，排序作为第四个查询维度，落在同一个方法里是最小改动、风格也最一致。往 `IPtSubscriptionPlusService` 加一个不会被调用到的方法只是死代码。

**具体改动**：

1. `PtSubscriptionPlus` 新增一个不落库的排序意向字段（沿用 `BaseEntity.params` 同款 `@TableField(exist=false)` 手法，但用扁平字段而不是塞进 `params` Map——`request.ts` 没有配置任何自定义 `paramsSerializer`，axios 默认序列化对不对 `params['sortBy']` 这种嵌套 Map 编成 `sortBy` bracket 语法没有把握；`title`/`mediaType`/`status` 已经验证过扁平字段可以直接被 GET 查询字符串绑定，加一个同款字段风险最低）：

```java
/** 排序方式：lastMatchTime=按上次命中时间倒序；其余/空=默认按 id 倒序。仅供列表查询用，不落库 */
@TableField(exist = false)
private String sortBy;
```

2. `PtSubscriptionRestController#buildQueryWrapper()` 追加分支：

```java
if ("lastMatchTime".equals(entity.getSortBy())) {
    wrapper.orderByDesc(PtSubscriptionPlus::getLastMatchTime).orderByDesc(PtSubscriptionPlus::getId);
} else {
    wrapper.orderByDesc(PtSubscriptionPlus::getId);
}
```

（`last_match_time` 为 `NULL` 的订阅在 `DESC` 排序下 MySQL 天然排到最后，不需要 `COALESCE`；加 `id` 倒序兜底避免相同时间戳/同为 `NULL` 时顺序不稳定。）

## 6. 设计四：TMDb 选片海报缩略列

`TmdbSearchItem`（`pt/subscription/dto/TmdbSearchItem.java`）已经有 `posterPath` 字段，**后端零改动**。

前端只改建订阅弹窗里的 `el-table`，加一列海报缩略图，复用 `index.vue` 里已经存在的 `posterUrl()` 拼接函数（目前只给卡片网格用，是个普通的 script setup 内函数，模板任何位置都能调）：

```html
<el-table-column label="海报" width="64" align="center">
  <template #default="scope">
    <img v-if="scope.row.posterPath"
         :src="posterUrl(scope.row.posterPath)"
         class="search-poster"
         loading="lazy"
         @error="(e: Event) => ((e.target as HTMLImageElement).style.visibility = 'hidden')" />
    <el-icon v-else class="search-poster-placeholder"><Picture /></el-icon>
  </template>
</el-table-column>
```

放在"标题"列前面。加载失败时用简单的内联 `@error` 隐藏图片即可（不需要像卡片列表那样维护一个 `posterErrorIds` Set——那个 Set 是为了翻页/重渲染后仍记得哪些图裂过，而 TMDb 搜索结果表格每次搜索都会整体替换，不存在"记住上次裂图状态"的需要）。`.search-poster`/`.search-poster-placeholder` 样式仿照现有 `.sub-poster` 缩小一版即可。

## 7. 组件改动清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `pt/subscription/SubscriptionService.java` | 改动 | 新增 `pauseBatch(List<Integer>)` / `resumeBatch(List<Integer>)`，循环复用现有单条 `pause`/`resume`，try/catch 逐条隔离失败 |
| `pt/subscription/dto/BatchOperationResult.java` | 新建 | `{ successCount, failedIds }`，批量暂停/恢复的返回结构 |
| `pt/task/DownloadRecordAdminService.java` | 改动 | 新增 `retryBatch(List<Integer>)`，循环复用现有 `retry(Integer)`，try/catch 隔离 + 统计 pushed/skipped |
| `pt/task/dto/BatchRetryResult.java` | 新建 | `{ total, pushedCount, skippedCount }` |
| `mybatisplus/domain/PtSubscriptionPlus.java` | 改动 | 新增 `@TableField(exist=false) private String sortBy` 排序意向字段，不落库 |
| `controller/api/PtSubscriptionRestController.java` | 改动 | `buildQueryWrapper()` 加 `sortBy` 分支；新增 `batchPause`/`batchResume`/`batchDelete` 三个端点 |
| `controller/api/PtDownloadRecordRestController.java` | 改动 | 新增 `batchRetry` 端点 |
| `openlist-web/src/api/openlist/ptSubscription.ts` | 改动 | 新增 `batchPauseSubscriptionApi`/`batchResumeSubscriptionApi`/`batchDeletePtSubscriptionApi` |
| `openlist-web/src/api/openlist/ptDownloadRecord.ts` | 改动 | 新增 `batchRetryPtDownloadRecordApi` |
| `openlist-web/src/composables/usePtSubscription.ts` | 改动 | 新增 `selectionMode`/`toggleSubSelect`/`isSubSelected`/`handleBatchPause`/`handleBatchResume`/`handleMoreCommand`；`useTaskList` 配置补 `batchDeleteApi`；`queryParams` 增加 `sortBy` |
| `openlist-web/src/composables/usePtDownloadRecord.ts` | 改动 | 新增 `selectionMode`/`selectedIds`/`toggleRecordSelect`/`handleBatchRetry` |
| `openlist-web/src/views/openlist/ptSubscription/index.vue` | 改动 | 批量工具条 + 卡片 checkbox；`sub-actions` 收纳为 4 按钮 + `el-dropdown`；排序下拉；TMDb 表格加海报列 |
| `openlist-web/src/views/openlist/ptDownloadRecord/index.vue` | 改动 | 批量工具条 + `FAILED` 卡片 checkbox |

不改动：`SubscriptionEngine`、`DownloadTrackService`、`TorrentFilterEngine`/`FilterCriteria`/`FilterCriteriaFactory`、`TgHelper`、`SearchSupplementService`、任何数据库脚本、`IPtSubscriptionPlusService` 接口（原因见第 2、5 节）。

## 8. API 汇总

| 方法 | 路径 | 参数 | 返回 |
|---|---|---|---|
| POST | `/api/openliststrm/pt-subscriptions/batchPause` | `ids`（逗号分隔） | `BatchOperationResult` |
| POST | `/api/openliststrm/pt-subscriptions/batchResume` | `ids` | `BatchOperationResult` |
| POST | `/api/openliststrm/pt-subscriptions/batchDelete` | `ids` | `Void` |
| GET | `/api/openliststrm/pt-subscriptions`（既有列表接口） | 新增可选 `sortBy=lastMatchTime` | `PageResult<PtSubscriptionPlus>`（结构不变，仅排序变化） |
| POST | `/api/openliststrm/pt-download-records/batchRetry` | `ids` | `BatchRetryResult` |

`GET /pt-subscriptions/tmdb-search` 返回结构不变（`TmdbSearchItem.posterPath` 已存在，前端本来就收得到，只是之前没渲染）。

## 9. 测试计划

对齐 `pt/subscription`、`pt/task` 包现有 mock 测试风格（`SubscriptionServiceTest`、`DownloadRecordAdminServiceTest` 均是 `@ExtendWith(MockitoExtension.class)` + 构造器/字段注入 mock；注意 AGENTS.md 提到的 `*Plus` 实体浅层 `equals` 陷阱，同一用例里对同一个 mock 方法用两个不同的 `PtSubscriptionPlus`/`PtSubscriptionEpisodePlus` 实例做参数匹配要用 `same()`/`eq()` 显式区分）：

- `SubscriptionServiceTest` 新增：
  - `pauseBatch`：3 个 id 全部存在 → `successCount=3`，`failedIds` 为空，`updateById` 被调用 3 次
  - `pauseBatch`：其中 1 个 id 对应 `getById` 返回 `null` → `successCount=2`，`failedIds=[那个id]`，其余 2 条仍正常完成（验证单条失败不中断循环）
  - `resumeBatch` 同构最少覆盖一个"部分失败"用例
- `DownloadRecordAdminServiceTest` 新增：
  - `retryBatch`：全部记录都是 `FAILED` 且都命中 → `pushedCount=N`，`skippedCount=0`
  - `retryBatch`：其中一条记录状态不是 `FAILED`（`retry()` 内部抛 `IllegalArgumentException`）→ 该条计入 `skippedCount`，其余记录仍正常调用 `retry()`（验证 try/catch 隔离生效，不会因为一条不满足条件就整批失败）
  - `retryBatch`：`retry()` 返回 `pushed=false`（搜到 0 候选）的记录计入 `skippedCount` 而不是异常路径
- `PtSubscriptionRestController#buildQueryWrapper` 的 `sortBy` 分支：本模块 `controller/` 目前没有任何单测覆盖（`pt/` 测试目录只有 service/task 层），`buildQueryWrapper` 是 `protected` 方法且逻辑很薄（一个 if/else 拼 `orderByDesc`），沿用现有"瘦 Controller 不单测"的项目基线，改动后人工验证：建 2 个订阅、给其中一个手动触发一次搜索补集使其产生 `lastMatchTime`，切换排序下拉确认有 `lastMatchTime` 的排在前面、`null` 的排在后面。
- 前端手动验证（本项目当前无组件级单测基础设施，`test:e2e` 是 Playwright 端到端，不为这类小交互单独加用例，成本不成比例）：
  - 批量操作：全选/部分选/切换页码后选中态表现、批量删除/暂停/恢复/重试后列表刷新且选中态清空
  - 下拉菜单：4 个"更多"项功能与收纳前行为一致
  - 排序下拉切换后列表顺序符合预期
  - 建订阅弹窗海报列：能正常显示、`posterPath` 为空或图片 404 时降级为占位图标，不影响选片操作

## 10. 不做的事情（本次范围之外）

- 不做"批量修改过滤规则覆盖"（过滤规则覆盖是个 JSON 表单，批量语义不清晰——不同订阅的现有覆盖值不同，批量套用容易误覆盖，需要单独设计，不在这次"交互优化"范围内）
- 不做批量操作的"全选当前页/全选所有页"区分，只支持手动逐张勾选（后续如果批量场景扩大到"全部暂停"这种量级，再加全选控件）
- 不修 `BaseController.selectPage()` 里 `isAsc` 被忽略、排序字符串拼接不安全的既有 bug（影响面是全站所有走 `BaseCrudRestController` 的列表页，需要单独排查每个调用方是否依赖了现有的"总是 desc"行为，风险与本次任务不对等）
- 不给下载记录列表加排序下拉（本次排序需求明确针对订阅列表的"上次命中时间"，下载记录列表本身已经按 `id` 倒序且有状态筛选，暂无同等强烈的排序诉求）
- 不改移动端（确认 `views-mobile/` 下没有 PT 订阅/下载记录页面）
- 不新增批量操作的操作审计日志（现有单条 pause/resume/delete/retry 都没有专门的操作日志表，批量版本保持同等粒度，不单独拔高）
