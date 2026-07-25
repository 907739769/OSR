# 下载并发/优先级控制（E4）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 给 `PtDownloaderPlus` 增加可配置的最大并发数，`SubscriptionEngine.handleGroup()` 推送前检查目标下载器当前在途任务数，达到上限就跳过本轮（不落库、不占位、不推送）。

**架构：** 在 `resolveDownloader()` 解析出下载器之后、`claim()` 占位之前插入一次 `isOverCapacity()` 检查；容量判断直接复用 D 组任务（下载器负载均衡）已经建好的 `downloaderLoadCache`（`Map<Integer, Long>`，由 `loadDownloaderLoadCounts()` 在批次开始时查一次、推送成功后就地 `+1`），不新增任何独立的 COUNT 查询。`pt_downloader` 新增 `max_concurrent` 列（默认 0=不限），`pt_download_record` 新增 `(downloader_id, state)` 复合索引供 `loadDownloaderLoadCounts()` 使用。

**技术栈：** Spring Boot 4 + MyBatis-Plus（BaseMapper/IService）、JUnit 5 + Mockito（`MockitoSettings(strictness = LENIENT)`）、Vue 3 + Element Plus + Vitest。

---

## 前置说明：与设计文档的一处偏差（已核实，按此计划为准）

`docs/superpowers/specs/2026-07-24-pt-download-concurrency-design.md` 第 2.2 节写的 `isOverCapacity()` 示例代码会对 `recordService` 发起一条独立的 `count(...)` COUNT 查询。但该设计文档第 2.4 节第 1 点已经明确要求：**如果 D 组（下载器负载均衡）先落地，本设计改为调用 D 已经建好的查询逻辑，不要维护两份几乎相同的 `recordService.count(...)` 查询**。

实际读取当前 `SubscriptionEngine.java`（本计划编写时的真实内容，D 已合入）确认：D 组已经实现了 `loadDownloaderLoadCounts(enabledDownloaders)` 方法，在 `process()`/`pushBest()` 批次开始时查一次全部启用下载器的在途记录数，缓存进 `Map<Integer, Long> downloaderLoadCache`（key=下载器id，value=当前 PUSHED/DOWNLOADING 记录数），并在每次推送成功后 `downloaderLoadCache.merge(downloader.getId(), 1L, Long::sum)` 就地自增。

因此本计划的 `isOverCapacity()` **不发起新查询**，直接从 `downloaderLoadCache` 里读取该下载器的已有计数，与 `maxConcurrent` 比较。第 3.2 节的 `idx_downloader_state` 索引仍然保留——它优化的是 `loadDownloaderLoadCounts()` 里 `downloader_id IN (...) AND state IN (...)` 这条查询，不是因为 `isOverCapacity()` 需要单独的索引。

设计文档第 4 节组件改动清单里"`controller/PtDownloaderController.java` 新增字段的表单校验"一项：实际读取 `PtDownloaderRestController.java`/`BaseCrudRestController.java` 确认，当前 `add`/`edit` 端点完全没有任何字段级后端校验（`port`/`useHttps` 等已有字段都没有），只有前端 `el-input-number`/`rules` 做校验。为保持代码库一致性（不引入这次改动独有的新校验模式），本计划**不新增后端校验代码**，仅在前端 composable 的 `rules` 里加 `min: 0` 校验（做法与现有 `port` 字段完全一致，见任务 4）。

---

## 任务清单

1. SQL 迁移脚本：`pt_downloader.max_concurrent` 列 + `pt_download_record` 索引
2. `PtDownloaderPlus` 实体新增 `maxConcurrent` 字段
3. `SubscriptionEngine` 容量检查（复用 `downloaderLoadCache`）
4. 前端 composable `usePtDownloader.ts`：表单默认值 + 校验规则
5. 前端页面 `ptDownloader/index.vue`：表单项 + 卡片展示
6. 全量验证与启动校验

---

### 任务 1：SQL 迁移脚本 — `pt_downloader.max_concurrent` 列 + `pt_download_record` 索引

**文件：**
- 创建：`ruoyi-common/src/main/resources/sql/20260742-pt-downloader-max-concurrency.sql`
- 修改：`ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java:66`

**背景**：`MysqlDdl.getSqlFiles()` 当前最后一项是 `"sql/20260741-pt-stats-menu.sql"`（第 66 行），所以本次新脚本编号为 `20260742`（设计文档草稿里写的 `20260738` 编号已被同批次另一个计划占用，实际以仓库里已存在的文件为准）。迁移脚本沿用 `20260735-pt-downloader-strm-task-link.sql` 的幂等写法：`INFORMATION_SCHEMA` 探测 + 动态 SQL + `PREPARE`/`EXECUTE`，不做 `DROP TABLE`/重建。这是纯 DDL 任务，没有可独立单测的逻辑，用编译验证代替（与 `2026-07-24-pt-backend-robustness.md` 任务 2/3 的既有做法一致）。

- [ ] **步骤 1：创建迁移脚本**

```sql
-- ----------------------------
-- 20260742: pt_downloader 增加 max_concurrent 列，用于单个下载器的并发上限保护（幂等脚本）
-- 同时给 pt_download_record 补一个 (downloader_id, state) 复合索引，
-- 避免 SubscriptionEngine.loadDownloaderLoadCounts() 的按下载器+状态过滤查询退化成全表扫描。
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_downloader' AND COLUMN_NAME = 'max_concurrent');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_downloader` ADD COLUMN `max_concurrent` int NOT NULL DEFAULT 0 COMMENT ''同时处于PUSHED/DOWNLOADING状态的最大记录数，0表示不限'' AFTER `tag`', 'SELECT ''Column max_concurrent already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND INDEX_NAME = 'idx_downloader_state');
SET @idx_sql := IF(@idx_exist = 0, 'ALTER TABLE `pt_download_record` ADD INDEX `idx_downloader_state` (`downloader_id`, `state`)', 'SELECT ''Index idx_downloader_state already exists''');
PREPARE stmt2 FROM @idx_sql;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;
```

- [ ] **步骤 2：`MysqlDdl.getSqlFiles()` 追加脚本路径**

在列表最后一项（原文件第 66 行）`"sql/20260741-pt-stats-menu.sql"` 之后追加：

```java
                "sql/20260740-notify-webhook-config.sql",
                "sql/20260741-pt-stats-menu.sql",
                "sql/20260742-pt-downloader-max-concurrency.sql"
        );
```

- [ ] **步骤 3：编译验证（纯 DDL 脚本 + 配置列表变更，无独立可测试逻辑；由任务 3 的测试间接覆盖 `maxConcurrent` 的读写）**

运行：`mvn compile -pl ruoyi-common,ruoyi-openliststrm -am -q`
预期：无输出、退出码 0

- [ ] **步骤 4：Commit**

```bash
git add ruoyi-common/src/main/resources/sql/20260742-pt-downloader-max-concurrency.sql \
        ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java
git commit -m "feat: pt_downloader 新增 max_concurrent 列，pt_download_record 新增下载器状态复合索引"
```

---

### 任务 2：`PtDownloaderPlus` 实体新增 `maxConcurrent` 字段

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/domain/PtDownloaderPlus.java:66-72`
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/mybatisplus/domain/PtDownloaderPlusTest.java`

**背景**：`PtDownloaderPlus` 是纯 `@Getter @Setter` 的 MyBatis-Plus 实体（`@TableName("pt_downloader")`），新增字段不需要额外逻辑。字段放在 `tag` 和 `enabled` 之间，与任务 1 里 SQL 的 `AFTER tag` 位置保持一致。

- [ ] **步骤 1：编写失败的测试**

在 `PtDownloaderPlusTest.java` 顶部导入区（原文件第 1-5 行）新增一个静态导入：

```java
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
```

在文件末尾、类的最后一个测试方法 `baseUrl_不改写字段本身的host值()`（原文件第 66-72 行）之后、类的闭合大括号之前新增：

```java
    @Test
    void maxConcurrent_未设置时为null_设置后能读回() {
        PtDownloaderPlus d = new PtDownloaderPlus();

        assertNull(d.getMaxConcurrent());

        d.setMaxConcurrent(5);

        assertEquals(5, d.getMaxConcurrent());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=PtDownloaderPlusTest`
预期：编译失败（COMPILATION ERROR），报错类似 `cannot find symbol: method getMaxConcurrent()`（字段还不存在）

- [ ] **步骤 3：编写最少实现代码**

在 `PtDownloaderPlus.java` 里 `tag` 字段（原文件第 66-68 行）之后、`enabled` 字段（原文件第 70-72 行）之前插入：

```java
    /** 推送时打的标签 */
    @TableField("tag")
    private String tag;

    /** 同时处于 PUSHED/DOWNLOADING 状态的最大记录数，0 表示不限 */
    @TableField("max_concurrent")
    private Integer maxConcurrent;

    /** 是否启用 0-否 1-是 */
    @TableField("enabled")
    private String enabled;
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=PtDownloaderPlusTest`
预期：`Tests run: 8, Failures: 0, Errors: 0`（原有 7 个 `baseUrl` 相关用例 + 本任务新增 1 个）

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/domain/PtDownloaderPlus.java \
        ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/mybatisplus/domain/PtDownloaderPlusTest.java
git commit -m "feat: PtDownloaderPlus 新增 maxConcurrent 字段"
```

---

### 任务 3：`SubscriptionEngine` — 容量检查（复用 `downloaderLoadCache`）

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java:191-244,389-415`
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngineTest.java`

**背景**：当前 `handleGroup()`（第 155-244 行）在 `resolveDownloader()` 解析出下载器（第 191 行）、判空（第 192-196 行）之后，直接进入"原子占位"循环（第 198-199 行注释与代码）。本任务在判空分支和占位循环之间插入一次 `isOverCapacity(downloader, downloaderLoadCache)` 检查，命中就 `recordSummary` 记录原因并 `return false`（与"没有可用的下载器"分支完全对称，都是"占位前拦截，不需要回滚"）。`isOverCapacity()` 新增为私有方法，放在 `resolveDownloader()`（第 389-414 行）之后；它读取的 `loadCache` 就是调用方已经传入的 `downloaderLoadCache`，不新建任何查询。

- [ ] **步骤 1：编写失败的测试**

在 `SubscriptionEngineTest.java` 的 `// ---------- 下载器 ----------` 分组内，`同一批次连续命中_第二次感知前一次推送的负载增量()` 测试方法（原文件第 398-422 行）之后、`// ---------- 多订阅 ----------` 分组注释（原文件第 424 行）之前新增以下 4 个测试：

```java
    // ---------- 并发上限 ----------

    @Test
    void 下载器maxConcurrent为0_不做限制_正常推送() throws Exception {
        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setType("QBITTORRENT");
        downloader.setSavePath("/data/downloads");
        downloader.setTag("osr-pt");
        downloader.setEnabled("1");
        downloader.setMaxConcurrent(0);
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(downloader));
        List<PtDownloadRecordPlus> heavyLoad = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            PtDownloadRecordPlus r = new PtDownloadRecordPlus();
            r.setDownloaderId(1);
            heavyLoad.add(r);
        }
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(heavyLoad);
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertEquals(1, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString());
    }

    @Test
    void 下载器maxConcurrent为null_不做限制_正常推送() throws Exception {
        // setUp() 里的默认下载器未设置 maxConcurrent，Integer 包装类型默认为 null
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertEquals(1, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString());
    }

    @Test
    void 下载器已达最大并发_跳过本轮_不占位不落库不推送() throws Exception {
        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setType("QBITTORRENT");
        downloader.setSavePath("/data/downloads");
        downloader.setTag("osr-pt");
        downloader.setEnabled("1");
        downloader.setMaxConcurrent(2);
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(downloader));
        PtDownloadRecordPlus active1 = new PtDownloadRecordPlus();
        active1.setDownloaderId(1);
        PtDownloadRecordPlus active2 = new PtDownloadRecordPlus();
        active2.setDownloaderId(1);
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(List.of(active1, active2));
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertEquals(0, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));

        verify(episodeService, never()).update(any(), any(Wrapper.class));
        verify(recordService, never()).save(any());
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString());
        verify(searchLogService).recordSummary(eq(10), eq(1), eq(SearchLogService.SOURCE_RSS), contains("并发"));
    }

    @Test
    void 下载器未达最大并发_正常推送() throws Exception {
        PtDownloaderPlus downloader = new PtDownloaderPlus();
        downloader.setId(1);
        downloader.setType("QBITTORRENT");
        downloader.setSavePath("/data/downloads");
        downloader.setTag("osr-pt");
        downloader.setEnabled("1");
        downloader.setMaxConcurrent(2);
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(downloader));
        PtDownloadRecordPlus active1 = new PtDownloadRecordPlus();
        active1.setDownloaderId(1);
        when(recordService.list(argThat((Wrapper<PtDownloadRecordPlus> w) -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(List.of(active1));
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        assertEquals(1, engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p"))));
        verify(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString());
    }
```

在导入区（原文件第 38 行 `import static org.mockito.ArgumentMatchers.argThat;` 之后）新增一个静态导入：

```java
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=SubscriptionEngineTest`
预期：新增的 `下载器已达最大并发_跳过本轮_不占位不落库不推送` 用例 FAIL（`expected: <0> but was: <1>`，因为生产代码目前完全没有容量检查，仍会正常推送）；另外 3 个新用例（`maxConcurrent为0`/`maxConcurrent为null`/`未达最大并发`）本来就会 PASS——它们描述的是"不应限制"的场景，当前生产代码本来就不限制，这是预期的巧合通过，步骤 3 落地后仍应保持 PASS

- [ ] **步骤 3：编写最少实现代码**

在 `resolveDownloader()` 调用与判空分支（原文件第 191-196 行）之后插入容量检查：

```java
        PtDownloaderPlus downloader = resolveDownloader(sub, enabledDownloaders, downloaderLoadCache);
        if (downloader == null) {
            log.warn("没有可用的下载器，订阅[{}] 本轮跳过", sub.getId());
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, "没有可用的下载器");
            return false;
        }

        if (isOverCapacity(downloader, downloaderLoadCache)) {
            log.debug("下载器[{}] 已达最大并发 {}，订阅[{}] 集{} 本轮跳过",
                    downloader.getId(), downloader.getMaxConcurrent(), sub.getId(), match.getEpisode());
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, "下载器并发已达上限");
            return false;
        }

        // 原子占位：条件更新按影响行数判断，防止并发轮询给同一集推两个种子
```

在 `resolveDownloader()` 方法（原文件第 389-414 行）末尾、类的闭合大括号之前新增私有方法：

```java
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

    /**
     * 目标下载器是否已达最大并发。{@code maxConcurrent} 为 null 或 &lt;=0 视为不限
     * （与 pt_filter_config 里 min_size/max_size 用 0 表示"不限"的既有约定一致）。
     * <p>
     * 直接复用调用方（{@link #process}/{@link #pushBest}）已经查好的 {@code loadCache}
     * （key=下载器id，value=当前 PUSHED/DOWNLOADING 在途记录数，见 {@link #loadDownloaderLoadCounts}），
     * 不再对 {@code recordService} 发起第二条 COUNT 查询——这条查询已经覆盖了所有启用下载器，
     * 且 {@link #handleGroup} 推送成功后会对该 Map 就地 {@code +1}，同一批次内的后续分组
     * 天然能感知到这次推送占用的名额。
     * </p>
     */
    private boolean isOverCapacity(PtDownloaderPlus downloader, Map<Integer, Long> loadCache) {
        Integer max = downloader.getMaxConcurrent();
        if (max == null || max <= 0) {
            return false;
        }
        long active = loadCache.getOrDefault(downloader.getId(), 0L);
        return active >= max;
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=SubscriptionEngineTest`
预期：`Tests run: 34, Failures: 0, Errors: 0`（原有 30 个用例 + 本任务新增 4 个）

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java \
        ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngineTest.java
git commit -m "feat: 下载器达到最大并发时跳过本轮推送，复用批内负载缓存"
```

---

### 任务 4：前端 composable `usePtDownloader.ts` — 表单默认值 + 校验规则

**文件：**
- 修改：`openlist-web/src/composables/usePtDownloader.ts:30-53`
- 测试：`openlist-web/src/composables/__tests__/usePtDownloader.spec.ts`

**背景**：`initForm()` 里加 `maxConcurrent: 0`（放在 `tag` 和 `enabled` 之间，与后端实体字段顺序一致）；`rules` 里加一条非必填但 `type: 'number', min: 0` 的校验，写法沿用 `port` 字段。

- [ ] **步骤 1：编写失败的测试**

在 `usePtDownloader.spec.ts` 文件末尾、最后一个 `it` 块（原文件第 78-102 行 `编辑既有记录时会自动触发一次校验，即使从未 blur 过`）之后、`describe` 闭合括号之前新增：

```typescript
  it('初始化表单时 maxConcurrent 默认值为 0（表示不限）', async () => {
    const composable = usePtDownloader()
    await nextTick()

    expect(composable.form.value.maxConcurrent).toBe(0)
  })
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtDownloader.spec.ts`
预期：新用例 FAIL（`expected undefined to be 0`，因为 `initForm()` 还没有 `maxConcurrent` 字段）

- [ ] **步骤 3：编写最少实现代码**

`usePtDownloader.ts` 里 `initForm`/`rules`（原文件第 30-53 行）整体替换为：

```typescript
    initForm: () => ({
      id: undefined,
      name: undefined,
      type: 'QBITTORRENT',
      host: undefined,
      port: 8080,
      useHttps: '0',
      username: undefined,
      password: undefined,
      savePath: undefined,
      tag: 'osr-pt',
      maxConcurrent: 0,
      enabled: '1',
      strmTaskId: undefined
    }),
    rules: {
      name: [{ required: true, message: '名称不能为空', trigger: 'blur' }],
      host: [{ required: true, message: '主机不能为空', trigger: 'blur' }],
      port: [
        { required: true, message: '端口不能为空', trigger: 'blur' },
        { type: 'number', min: 1, max: 65535, message: '端口须在 1-65535 之间', trigger: 'blur' }
      ],
      savePath: [{ required: true, message: '保存路径不能为空', trigger: 'blur' }],
      tag: [{ required: true, message: '标签不能为空', trigger: 'blur' }],
      maxConcurrent: [
        { type: 'number', min: 0, message: '最大并发数不能为负数', trigger: 'blur' }
      ]
    },
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtDownloader.spec.ts`
预期：`Test Files 1 passed, Tests 5 passed`（原有 4 个 + 本任务新增 1 个）

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/composables/usePtDownloader.ts \
        openlist-web/src/composables/__tests__/usePtDownloader.spec.ts
git commit -m "feat: usePtDownloader 新增 maxConcurrent 默认值与校验规则"
```

---

### 任务 5：前端页面 `ptDownloader/index.vue` — 表单项 + 卡片展示

**文件：**
- 修改：`openlist-web/src/views/openlist/ptDownloader/index.vue:73-77,136-148,181-187`

**背景**：仓库里 `views/openlist/ptDownloader/` 目录目前没有任何 `__tests__` 目录/`.spec.ts` 文件（已用 Glob 确认），这是既有约定的延伸——`2026-07-24-pt-backend-robustness.md` 任务 9 对移动端页面也采用同样理由（无现成测试基础设施时不新增），本任务对这个纯模板/展示层改动同样遵循，只用 `vue-tsc` 类型检查 + 复用任务 6 的启动验证兜底，不新建组件测试文件。

- [ ] **步骤 1：新增/编辑对话框表单项**

在 `<el-form-item label="标签" prop="tag">`（原文件第 136-138 行）之后、`<el-form-item label="关联STRM任务" prop="strmTaskId">`（原文件第 139 行起）之前插入：

```html
        <el-form-item label="标签" prop="tag">
          <el-input v-model="form.tag" placeholder="推送种子时打的标签" />
        </el-form-item>
        <el-form-item label="最大并发数" prop="maxConcurrent">
          <el-input-number v-model="form.maxConcurrent" :min="0" :style="{ width: '200px' }" />
          <div class="field-hint">0 表示不限，达到上限时新任务会等到下一轮自动重试</div>
        </el-form-item>
        <el-form-item label="关联STRM任务" prop="strmTaskId">
```

- [ ] **步骤 2：卡片列表展示行**

在 `card-body` 里"标签"那一行（原文件第 73-76 行）之后插入：

```html
            <div class="card-row">
              <span class="label">标签</span>
              <span class="value">{{ item.tag }}</span>
            </div>
            <div class="card-row">
              <span class="label">最大并发</span>
              <span class="value">{{ item.maxConcurrent ? item.maxConcurrent : '不限' }}</span>
            </div>
```

- [ ] **步骤 3：新增 `.field-hint` 样式**

在 `<style scoped lang="scss">` 块里 `.save-path-warning`（原文件第 181-186 行）之后、`.page-container`（原文件第 188 行）之前插入：

```scss
.save-path-warning {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-warning);
}

.field-hint {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--osr-text-secondary);
}

.page-container {
```

- [ ] **步骤 4：类型检查验证**

运行：`cd openlist-web && npx vue-tsc --noEmit`
预期：无类型错误输出（`item`/`form` 均为 `any`，模板绑定不受类型检查约束，本步骤主要确认没有引入 `<script setup>` 语法错误）

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views/openlist/ptDownloader/index.vue
git commit -m "feat: PT下载器页面新增最大并发数配置项与展示"
```

---

### 任务 6：全量验证与启动校验

**文件：** 无新增/修改，纯验证任务。

**背景**：本次改动涉及 `pt_downloader` 新增列、`pt_download_record` 新增索引，两者都由 `MysqlDdl` 在应用启动时自动执行迁移。按 `AGENTS.md` 的要求，涉及数据库迁移的改动需要做启动验证，确认迁移在真实容器里成功执行、后端未崩溃重启。

- [ ] **步骤 1：后端全量单元测试**

运行：`mvn test -pl ruoyi-openliststrm -am`
预期：`BUILD SUCCESS`，无 `Tests in error`/`Tests failed`

- [ ] **步骤 2：后端完整打包（含 `--enable-preview` 编译）**

运行：`mvn clean package -DskipTests`
预期：`BUILD SUCCESS`，`ruoyi-admin/target/` 下生成 `ruoyi-admin.jar`

- [ ] **步骤 3：前端单元测试 + 类型检查构建**

运行：`cd openlist-web && npm run test:unit`
预期：全部测试用例 PASS

运行：`cd openlist-web && npm run build`
预期：`vue-tsc` 无类型错误，`vite build` 成功生成 `dist/`

- [ ] **步骤 4：前端 lint**

运行：`cd openlist-web && npm run lint`
预期：无残留 lint 错误（该命令带 `--fix`，会自动修复可修复项；若有不可自动修复的错误需手动处理后重跑至通过）

- [ ] **步骤 5：Docker 启动验证（数据库迁移 + 后端未崩溃）**

运行：`docker compose up -d --build --no-deps backend`

等待约 30 秒后运行：`docker ps --filter "name=osr-backend" --format "{{.Names}}\t{{.Status}}"`
预期：状态里 `restarts=0`（若容器不断重启说明启动失败，需按 `AGENTS.md` 排查步骤 `docker cp osr-backend:/data/logs ./tmp` 后看 `sys-error.log`）

- [ ] **步骤 6：确认数据库迁移生效**

运行（替换为实际的数据库连接方式，如 `docker exec -it <mysql容器名> mysql -uroot -p osr`）：

```sql
SHOW COLUMNS FROM pt_downloader LIKE 'max_concurrent';
SHOW INDEX FROM pt_download_record WHERE Key_name = 'idx_downloader_state';
```

预期：第一条返回一行（列已创建，`Default` 为 `0`）；第二条返回两行（`downloader_id`、`state` 各一行，说明复合索引已创建）

- [ ] **步骤 7：手动验证前端表单**

在浏览器里打开 PT 下载器管理页，新增或编辑一个下载器，确认"最大并发数"输入框可见、可输入非负整数，保存后卡片列表能看到对应行（未配置显示"不限"）。
