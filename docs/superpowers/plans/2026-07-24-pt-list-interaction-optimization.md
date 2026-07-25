# 订阅/下载记录列表交互优化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 给 `ptSubscription`/`ptDownloadRecord` 两个列表页补齐批量操作（批量暂停/恢复/删除订阅、批量重试失败下载记录）、订阅卡片操作按钮收纳（8 按钮 → 4 按钮 + "更多"下拉）、订阅列表按"上次命中时间"排序、建订阅弹窗 TMDb 选片表格海报缩略列这四项交互体验。

**架构：** 后端在 `SubscriptionService`/`DownloadRecordAdminService` 新增"循环复用单条方法 + try/catch 逐条隔离失败"的批量编排方法，`PtSubscriptionRestController`/`PtDownloadRecordRestController` 按项目既有 `POST /xxx/batchXxx?ids=1,2,3` 惯例新增端点；排序落在 `PtSubscriptionRestController#buildQueryWrapper()` 里加分支，不碰 `IPtSubscriptionPlusService`。前端选中态是"卡片 checkbox overlay + 手动维护 `selectedIds` 数组"，不复用 `<el-table>` 的 `@selection-change`；订阅页复用 `useTaskList` 已有的 `selectedIds`/`batchDeleteApi` 机制，下载记录页（本来不用 `useTaskList`）新增一套同构最小选中态。TMDb 海报列纯前端渲染，后端零改动。

**技术栈：** Java 25 preview + Spring Boot + MyBatis-Plus（后端）；Vue 3 `<script setup>` + Element Plus + Pinia 无关（composable 分层）；Vitest + @vue/test-utils（前端组件级/组合式函数级测试）；JUnit 5 + Mockito（后端单测）。

---

## 前置说明（写计划前已确认的事实，直接影响下面的任务）

- **本计划是"前端轨道 C→B→A"里的 B**，依赖 C（`docs/superpowers/plans/2026-07-24-pt-style-polish.md`）已经合入。已完整重读 C、D 两个独立计划落地后的真实文件：
  - `openlist-web/src/views/openlist/ptSubscription/index.vue`（当前 667 行，C 已加骨架屏分支 `v-if="loading && taskList.length===0"`/`v-else v-loading` 与 CSS 令牌替换）
  - `openlist-web/src/views/openlist/ptDownloadRecord/index.vue`（当前 358 行，C 加了骨架屏分支 + `record-card--failed` 视觉强化，D 加了 `failReasonCode`/`failReasonCodeLabel`/`failReasonTagType` 失败原因分类标签）
  - `openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`（当前已有 4 个 describe 块：`prototype`/`failReasonCode 标签`/`PtDownloadRecord 失败卡片视觉强化`/`PtDownloadRecord 骨架屏`，共 8 个用例）
  - `openlist-web/src/views/openlist/ptSubscription/__tests__/index.spec.ts`（当前只有 1 个 describe 块：`PtSubscription 骨架屏`，2 个用例）、同目录 `style-tokens.spec.ts`（3 个用例，本计划不碰）
  - `openlist-web/src/composables/usePtSubscription.ts`、`usePtDownloadRecord.ts`、`useTaskList.ts`
  - 后端 `SubscriptionService.java`、`DownloadRecordAdminService.java`、`PtSubscriptionRestController.java`、`PtDownloadRecordRestController.java`、`PtSubscriptionPlus.java`、`TmdbSearchItem.java`、`SubscriptionServiceTest.java`、`DownloadRecordAdminServiceTest.java`
  - 下面每个任务给出的行号均以上述**当前真实内容**为准；同一文件内的多个任务按顺序执行，后一个任务的行号是前一个任务改完之后的行号（已在任务描述里注明）。
- **`SubscriptionEngine.java` 确认不需要改动**：本计划范围明确排除"不改 SubscriptionEngine 推送决策"（设计文档第 1 节范围限定），已读过该文件确认其订阅匹配/推送逻辑与本次批量操作、按钮收纳、排序、海报列四项改动无交集，不在改动清单内。
- **`useTaskList.ts` 已经支持 `batchDeleteApi` 配置项**（`handleDelete()` 内部：`if (batchDeleteApi) { await batchDeleteApi(ids) } else { await Promise.all(ids.map(...)) }`，见该文件第 141-145 行），这是此前某个独立改动已经落地的基础设施，**本计划不需要再改 `useTaskList.ts` 本身**，订阅页批量删除只需在 `usePtSubscription.ts` 调用 `useTaskList(...)` 时补一个 `batchDeleteApi` 配置字段即可复用现成的确认框 + 批量调用 + 清空选中 + 刷新逻辑。
- **两个必须先修正的落点纠偏**（设计文档内部有一处自相矛盾，写计划时已核对并按更详细的措辞定稿，避免后续任务互相打架）：
  1. 设计文档第 4 节正文明确说"`index.vue` 里新增一个 `handleMoreCommand(cmd, row)`...不改 `usePtSubscription.ts` 的任何导出"，但第 7 节改动清单表格误把 `handleMoreCommand` 也列进了 `usePtSubscription.ts` 的改动里。**以第 4 节正文为准**：`handleMoreCommand` 写在 `ptSubscription/index.vue` 的 `<script setup>` 里，`usePtSubscription.ts` 不新增这个导出。
  2. 设计文档第 3.2 节给的 `handleBatchRetry` 示例代码里 `ElMessageBox.confirm(...)` 前后没有包 `try/catch`——若用户点"取消"，`confirm()` 会 reject 一个字符串 `'cancel'`，这里会变成未捕获的 Promise rejection。本仓库所有其余"confirm 后再操作"的现成函数（`handleRemove`/`useTaskList.handleDelete`/`useTaskList.handleExecute`）都用 `try { ... } catch (e) { if (e !== 'cancel') console.error(e) }` 包一层。为保持风格一致、避免用户点取消时控制台报未处理异常，本计划实现 `handleBatchRetry`/`handleBatchPause`/`handleBatchResume` 时都补上这层 `try/catch`，其余逻辑（确认文案、成功提示文案、清空选中、刷新列表）与设计文档完全一致。
- **`Convert` 工具类的真实包路径是 `com.ruoyi.common.core.text.Convert`**（不是设计文档里没写全的 `com.ruoyi.common.utils.Convert`）——已通过 `OpenlistCopyTaskRestController.java` 的真实 import 验证，下面的 Controller 代码按这个真实路径写。
- **`SupplementResult`**（`pt/subscription/dto/SupplementResult.java`）是 `@Data @AllArgsConstructor`，字段 `pushed`(boolean)/`candidateCount`(int)，boolean 字段 `pushed` 的 Lombok getter 是 `isPushed()`（不是 `getPushed()`），下面 `retryBatch` 的实现与测试都用 `isPushed()`。
- 所有前端命令默认在 `openlist-web/` 目录下执行；后端命令默认在仓库根目录执行。

---

### 任务 1：新建 `BatchOperationResult` DTO（批量暂停/恢复的返回结构）

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/dto/BatchOperationResult.java`

- [ ] **步骤 1：编写文件**

```java
package com.ruoyi.openliststrm.pt.subscription.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 批量暂停/恢复订阅的执行结果。
 * <p>
 * 逐条复用单条 pause/resume，一条订阅已被并发删除等原因导致失败时不影响同批次其余条目，
 * 失败的 id 收进 {@link #failedIds} 供前端提示"M 项已跳过（可能已被删除）"。
 * </p>
 *
 * @author Jack
 */
@Data
@AllArgsConstructor
public class BatchOperationResult {

    /** 成功处理的条数 */
    private int successCount;

    /** 因订阅不存在等原因被跳过的 id 列表 */
    private List<Integer> failedIds;
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn compile -pl ruoyi-openliststrm -am -q`

预期：无输出、退出码 0（新文件只是一个 DTO，编译通过即说明语法/包路径正确）。

- [ ] **步骤 3：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/dto/BatchOperationResult.java
git commit -m "feat: 新增批量暂停/恢复订阅的返回结构 BatchOperationResult"
```

---

### 任务 2：`SubscriptionService` 新增 `pauseBatch`/`resumeBatch`（TDD）

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionService.java:196-202`（在现有 `resume()` 方法后插入两个新方法）
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionServiceTest.java:456-460`（在 `resume_把订阅置回订阅中()` 用例后、`// ---------- 辅助 ----------` 注释前插入新用例）

- [ ] **步骤 1：编写失败的测试**

在 `SubscriptionServiceTest.java` 顶部导入区追加（现有导入见文件第 1-37 行）：

```java
import com.ruoyi.openliststrm.pt.subscription.dto.BatchOperationResult;
```

```java
import static org.mockito.Mockito.times;
```

在第 458 行 `resume_把订阅置回订阅中()` 方法结束的 `}` 之后、第 460 行 `// ---------- 辅助 ----------` 之前插入：

```java

    // ---------- 批量暂停/恢复 ----------

    @Test
    void pauseBatch_全部存在_成功数等于总数且逐条更新() {
        when(subscriptionService.getById(1)).thenReturn(activeTv(1, 1));
        when(subscriptionService.getById(2)).thenReturn(activeTv(2, 1));
        when(subscriptionService.getById(3)).thenReturn(activeTv(3, 1));

        BatchOperationResult result = service.pauseBatch(List.of(1, 2, 3));

        assertEquals(3, result.getSuccessCount());
        assertTrue(result.getFailedIds().isEmpty());
        verify(subscriptionService, times(3)).updateById(any());
    }

    @Test
    void pauseBatch_其中一个不存在_不中断其余条目() {
        when(subscriptionService.getById(1)).thenReturn(activeTv(1, 1));
        when(subscriptionService.getById(2)).thenReturn(null);
        when(subscriptionService.getById(3)).thenReturn(activeTv(3, 1));

        BatchOperationResult result = service.pauseBatch(List.of(1, 2, 3));

        assertEquals(2, result.getSuccessCount());
        assertEquals(List.of(2), result.getFailedIds());
        verify(subscriptionService, times(2)).updateById(any());
    }

    @Test
    void resumeBatch_其中一个不存在_不中断其余条目() {
        when(subscriptionService.getById(4)).thenReturn(activeTv(4, 1));
        when(subscriptionService.getById(5)).thenReturn(null);

        BatchOperationResult result = service.resumeBatch(List.of(4, 5));

        assertEquals(1, result.getSuccessCount());
        assertEquals(List.of(5), result.getFailedIds());
        verify(subscriptionService, times(1)).updateById(any());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=SubscriptionServiceTest`

预期：编译失败（`cannot find symbol: method pauseBatch`/`method resumeBatch`），因为 `SubscriptionService` 还没有这两个方法。

- [ ] **步骤 3：编写最少实现代码**

在 `SubscriptionService.java` 顶部导入区（现有导入见文件第 1-22 行）追加：

```java
import com.ruoyi.openliststrm.pt.subscription.dto.BatchOperationResult;
```

在第 201 行 `resume()` 方法的 `}` 之后、第 203 行 `// ---------- 内部 ----------` 之前插入：

```java

    /** 批量暂停：逐条复用单条 pause，一条失败（如已被并发删除）不影响其余条目 */
    public BatchOperationResult pauseBatch(List<Integer> ids) {
        int success = 0;
        List<Integer> failed = new ArrayList<>();
        for (Integer id : ids) {
            try {
                pause(id);
                success++;
            } catch (IllegalArgumentException e) {
                failed.add(id);
            }
        }
        return new BatchOperationResult(success, failed);
    }

    /** 批量恢复：逐条复用单条 resume，一条失败不影响其余条目 */
    public BatchOperationResult resumeBatch(List<Integer> ids) {
        int success = 0;
        List<Integer> failed = new ArrayList<>();
        for (Integer id : ids) {
            try {
                resume(id);
                success++;
            } catch (IllegalArgumentException e) {
                failed.add(id);
            }
        }
        return new BatchOperationResult(success, failed);
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=SubscriptionServiceTest`

预期：`Tests run: 24, Failures: 0, Errors: 0`（原有 21 个用例 + 本任务新增 3 个）。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionService.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionServiceTest.java
git commit -m "feat: SubscriptionService新增批量暂停/恢复，单条失败不中断整批"
```

---

### 任务 3：`PtSubscriptionPlus` 新增不落库的 `sortBy` 排序意向字段

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/domain/PtSubscriptionPlus.java:91-94`

- [ ] **步骤 1：编写最少实现代码**

当前文件第 91-94 行（`lastSearchTime` 字段与类结尾 `}`）：

```java
    /** 上次发起搜索补集的时间，用于自动补搜到期判断与前端展示 */
    @TableField("last_search_time")
    private Date lastSearchTime;
}
```

替换为：

```java
    /** 上次发起搜索补集的时间，用于自动补搜到期判断与前端展示 */
    @TableField("last_search_time")
    private Date lastSearchTime;

    /** 排序方式：lastMatchTime=按上次命中时间倒序；其余/空=默认按 id 倒序。仅供列表查询用，不落库 */
    @TableField(exist = false)
    private String sortBy;
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn compile -pl ruoyi-openliststrm -am -q`

预期：无输出、退出码 0。

- [ ] **步骤 3：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/domain/PtSubscriptionPlus.java
git commit -m "feat: PtSubscriptionPlus新增不落库的sortBy排序意向字段"
```

---

### 任务 4：`PtSubscriptionRestController` 新增排序分支与三个批量端点

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtSubscriptionRestController.java:1-27`（导入区）、`58-72`（`buildQueryWrapper`）、`209-216`（文件末尾，新增三个批量端点）

本控制器目前没有单测覆盖（`pt/` 测试目录只有 service/task 层，`buildQueryWrapper` 是 `protected` 方法且逻辑很薄），沿用项目"瘦 Controller 不单测"的基线，本任务改完后用编译通过 + 手动验证代替单测（手动验证步骤见任务 14）。

- [ ] **步骤 1：修改导入区**

当前文件第 1-27 行：

```java
package com.ruoyi.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.core.domain.Result;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.subscription.SearchSupplementService;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionService;
import com.ruoyi.openliststrm.pt.subscription.TmdbSearchService;
import com.ruoyi.openliststrm.pt.subscription.dto.SearchRequest;
import com.ruoyi.openliststrm.pt.subscription.dto.SubscribeRequest;
import com.ruoyi.openliststrm.pt.subscription.dto.SubscriptionProgress;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import com.ruoyi.openliststrm.pt.subscription.dto.TmdbSearchItem;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
```

替换为（新增 `Convert`、`BatchOperationResult`、`Arrays` 三行导入）：

```java
package com.ruoyi.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.core.domain.Result;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.subscription.SearchSupplementService;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionSearchOnCreateTrigger;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionService;
import com.ruoyi.openliststrm.pt.subscription.TmdbSearchService;
import com.ruoyi.openliststrm.pt.subscription.dto.BatchOperationResult;
import com.ruoyi.openliststrm.pt.subscription.dto.SearchRequest;
import com.ruoyi.openliststrm.pt.subscription.dto.SubscribeRequest;
import com.ruoyi.openliststrm.pt.subscription.dto.SubscriptionProgress;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import com.ruoyi.openliststrm.pt.subscription.dto.TmdbSearchItem;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
```

- [ ] **步骤 2：修改 `buildQueryWrapper` 加排序分支**

当前文件第 58-72 行：

```java
    @Override
    protected Wrapper<PtSubscriptionPlus> buildQueryWrapper(PtSubscriptionPlus entity) {
        LambdaQueryWrapper<PtSubscriptionPlus> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(entity.getTitle())) {
            wrapper.like(PtSubscriptionPlus::getTitle, entity.getTitle());
        }
        if (StringUtils.isNotBlank(entity.getMediaType())) {
            wrapper.eq(PtSubscriptionPlus::getMediaType, entity.getMediaType());
        }
        if (StringUtils.isNotBlank(entity.getStatus())) {
            wrapper.eq(PtSubscriptionPlus::getStatus, entity.getStatus());
        }
        wrapper.orderByDesc(PtSubscriptionPlus::getId);
        return wrapper;
    }
```

替换为：

```java
    @Override
    protected Wrapper<PtSubscriptionPlus> buildQueryWrapper(PtSubscriptionPlus entity) {
        LambdaQueryWrapper<PtSubscriptionPlus> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(entity.getTitle())) {
            wrapper.like(PtSubscriptionPlus::getTitle, entity.getTitle());
        }
        if (StringUtils.isNotBlank(entity.getMediaType())) {
            wrapper.eq(PtSubscriptionPlus::getMediaType, entity.getMediaType());
        }
        if (StringUtils.isNotBlank(entity.getStatus())) {
            wrapper.eq(PtSubscriptionPlus::getStatus, entity.getStatus());
        }
        if ("lastMatchTime".equals(entity.getSortBy())) {
            wrapper.orderByDesc(PtSubscriptionPlus::getLastMatchTime).orderByDesc(PtSubscriptionPlus::getId);
        } else {
            wrapper.orderByDesc(PtSubscriptionPlus::getId);
        }
        return wrapper;
    }
```

- [ ] **步骤 3：文件末尾新增三个批量端点**

当前文件第 203-216 行（`resume()` 方法结束到类结尾）：

```java
    /**
     * 恢复订阅。
     */
    @PostMapping("/{id}/resume")
    public Result<Void> resume(@PathVariable("id") Integer id) {
        try {
            subscriptionBiz.resume(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 删除订阅，连带删除其每集状态行。
     * <p>
     * 覆写基类实现：基类只删主表，会在 pt_subscription_episode 留下孤儿数据。
     * </p>
     */
    @Override
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Integer id) {
        episodeService.remove(new QueryWrapper<PtSubscriptionEpisodePlus>().eq("sub_id", id));
        boolean removed = service.removeById(id);
        return removed ? Result.success() : Result.error("删除失败");
    }
}
```

替换为（在 `resume()` 之后插入三个批量端点，`delete()` 与类结尾不变）：

```java
    /**
     * 恢复订阅。
     */
    @PostMapping("/{id}/resume")
    public Result<Void> resume(@PathVariable("id") Integer id) {
        try {
            subscriptionBiz.resume(id);
            return Result.success();
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量暂停订阅，单条失败（如已被并发删除）不影响其余条目。
     */
    @PostMapping("/batchPause")
    public Result<BatchOperationResult> batchPause(@RequestParam("ids") String ids) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要暂停的订阅");
        }
        List<Integer> idList = Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
        return Result.success(subscriptionBiz.pauseBatch(idList));
    }

    /**
     * 批量恢复订阅，单条失败不影响其余条目。
     */
    @PostMapping("/batchResume")
    public Result<BatchOperationResult> batchResume(@RequestParam("ids") String ids) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要恢复的订阅");
        }
        List<Integer> idList = Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
        return Result.success(subscriptionBiz.resumeBatch(idList));
    }

    /**
     * 批量删除订阅，连带删除每集状态行。
     * <p>
     * 与单条 {@link #delete(Integer)} 同样的"纯 CRUD 组合"落点，用 IN 一次性执行不逐条循环。
     * </p>
     */
    @PostMapping("/batchDelete")
    public Result<Void> batchDelete(@RequestParam("ids") String ids) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要删除的订阅");
        }
        List<Integer> idList = Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
        episodeService.remove(new QueryWrapper<PtSubscriptionEpisodePlus>().in("sub_id", idList));
        boolean removed = service.removeByIds(idList);
        return removed ? Result.success() : Result.error("删除失败");
    }

    /**
     * 删除订阅，连带删除其每集状态行。
     * <p>
     * 覆写基类实现：基类只删主表，会在 pt_subscription_episode 留下孤儿数据。
     * </p>
     */
    @Override
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Integer id) {
        episodeService.remove(new QueryWrapper<PtSubscriptionEpisodePlus>().eq("sub_id", id));
        boolean removed = service.removeById(id);
        return removed ? Result.success() : Result.error("删除失败");
    }
}
```

- [ ] **步骤 4：编译验证**

运行：`mvn compile -pl ruoyi-openliststrm -am -q`

预期：无输出、退出码 0。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtSubscriptionRestController.java
git commit -m "feat: PtSubscriptionRestController新增按上次命中时间排序与批量暂停/恢复/删除端点"
```

---

### 任务 5：新建 `BatchRetryResult` DTO（批量重试下载记录的返回结构）

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/dto/BatchRetryResult.java`

- [ ] **步骤 1：编写文件**

```java
package com.ruoyi.openliststrm.pt.task.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 批量重试失败下载记录的执行结果。
 * <p>
 * 逐条复用单条 retry，记录已被并发处理成非 FAILED、关联订阅已暂停等情况计入 {@link #skippedCount}，
 * 不因单条不满足条件让整批失败。
 * </p>
 *
 * @author Jack
 */
@Data
@AllArgsConstructor
public class BatchRetryResult {

    /** 本次批量重试涉及的记录总数 */
    private int total;

    /** 重新找到候选并成功推送下载的条数 */
    private int pushedCount;

    /** 未搜到候选、或因状态不满足重试条件而被跳过的条数 */
    private int skippedCount;
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn compile -pl ruoyi-openliststrm -am -q`

预期：无输出、退出码 0。

- [ ] **步骤 3：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/dto/BatchRetryResult.java
git commit -m "feat: 新增批量重试下载记录的返回结构 BatchRetryResult"
```

---

### 任务 6：`DownloadRecordAdminService` 新增 `retryBatch`（TDD）

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadRecordAdminService.java:1-26`（导入区）、`149-150`（`retry()` 方法后插入）
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadRecordAdminServiceTest.java:1-34`（导入区）、`276-277`（文件末尾插入新用例）

- [ ] **步骤 1：编写失败的测试**

在 `DownloadRecordAdminServiceTest.java` 顶部导入区，当前第 1-34 行：

```java
package com.ruoyi.openliststrm.pt.task;

import com.ruoyi.common.core.domain.PageResult;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.subscription.SearchSupplementService;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

替换为（新增 `BatchRetryResult` 导入、`anyString`、`times`）：

```java
package com.ruoyi.openliststrm.pt.task;

import com.ruoyi.common.core.domain.PageResult;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.subscription.SearchSupplementService;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import com.ruoyi.openliststrm.pt.task.dto.BatchRetryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

在文件第 276 行 `retry_没有BLOCKED集_不触发重置更新()` 方法结束的 `}` 之后、第 277 行类结尾 `}` 之前插入：

```java

    // ---------- retryBatch ----------

    @Test
    void retryBatch_全部命中_pushedCount等于总数() {
        PtDownloadRecordPlus r1 = record(1, 10, 5, "FAILED", 20, 30);
        PtDownloadRecordPlus r2 = record(2, 10, 6, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r1);
        when(recordService.getById(2)).thenReturn(r2);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of());
        when(searchSupplementService.supplement(eq(10), eq(5), eq("某剧 S01E05")))
                .thenReturn(new SupplementResult(true, 1));
        when(searchSupplementService.supplement(eq(10), eq(6), eq("某剧 S01E06")))
                .thenReturn(new SupplementResult(true, 1));

        BatchRetryResult result = service().retryBatch(List.of(1, 2));

        assertEquals(2, result.getTotal());
        assertEquals(2, result.getPushedCount());
        assertEquals(0, result.getSkippedCount());
    }

    @Test
    void retryBatch_一条不是FAILED状态_计入skipped不影响其余() {
        PtDownloadRecordPlus r1 = record(1, 10, 5, "FAILED", 20, 30);
        PtDownloadRecordPlus r2 = record(2, 10, 6, "DOWNLOADING", 20, 30);
        when(recordService.getById(1)).thenReturn(r1);
        when(recordService.getById(2)).thenReturn(r2);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of());
        when(searchSupplementService.supplement(eq(10), eq(5), eq("某剧 S01E05")))
                .thenReturn(new SupplementResult(true, 1));

        BatchRetryResult result = service().retryBatch(List.of(1, 2));

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getPushedCount());
        assertEquals(1, result.getSkippedCount());
        verify(searchSupplementService, times(1)).supplement(anyInt(), anyInt(), anyString());
    }

    @Test
    void retryBatch_搜到0候选_计入skipped而非异常路径() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        when(recordService.getById(1)).thenReturn(r);
        when(subscriptionService.getById(10)).thenReturn(tvSub(10, "某剧", 1, "ACTIVE"));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of());
        when(searchSupplementService.supplement(eq(10), eq(5), eq("某剧 S01E05")))
                .thenReturn(new SupplementResult(false, 0));

        BatchRetryResult result = service().retryBatch(List.of(1));

        assertEquals(1, result.getTotal());
        assertEquals(0, result.getPushedCount());
        assertEquals(1, result.getSkippedCount());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=DownloadRecordAdminServiceTest`

预期：编译失败（`cannot find symbol: method retryBatch`），因为 `DownloadRecordAdminService` 还没有这个方法。

- [ ] **步骤 3：编写最少实现代码**

在 `DownloadRecordAdminService.java` 顶部导入区，当前第 1-26 行：

```java
package com.ruoyi.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.subscription.SearchSupplementService;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionMatcher;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionService;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import com.ruoyi.openliststrm.pt.task.dto.DownloadRecordView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
```

替换为（新增 `BatchRetryResult` 导入）：

```java
package com.ruoyi.openliststrm.pt.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloaderPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.subscription.SearchSupplementService;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionEpisodeState;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionMatcher;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionService;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import com.ruoyi.openliststrm.pt.task.dto.BatchRetryResult;
import com.ruoyi.openliststrm.pt.task.dto.DownloadRecordView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
```

在文件第 149 行 `retry()` 方法结束的 `}` 之后、第 151 行 `resetBlockedEpisodes` 方法注释之前插入：

```java

    /**
     * 批量重试失败下载记录：逐条复用单条 retry，用 try/catch 隔离预期内的"跳过"（记录已被并发处理成
     * 非 FAILED、关联订阅已暂停等），不让一条不满足条件的记录中断整批。
     */
    public BatchRetryResult retryBatch(List<Integer> ids) {
        int pushed = 0;
        int skipped = 0;
        for (Integer id : ids) {
            try {
                SupplementResult r = retry(id);
                if (r.isPushed()) {
                    pushed++;
                } else {
                    skipped++;
                }
            } catch (IllegalArgumentException e) {
                skipped++;
            }
        }
        return new BatchRetryResult(ids.size(), pushed, skipped);
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=DownloadRecordAdminServiceTest`

预期：`Tests run: 20, Failures: 0, Errors: 0`（原有 17 个用例 + 本任务新增 3 个）。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadRecordAdminService.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadRecordAdminServiceTest.java
git commit -m "feat: DownloadRecordAdminService新增批量重试失败下载记录，单条失败不中断整批"
```

---

### 任务 7：`PtDownloadRecordRestController` 新增 `batchRetry` 端点

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtDownloadRecordRestController.java:1-19`（导入区）、`58-66`（文件末尾）

- [ ] **步骤 1：修改导入区**

当前文件第 1-19 行：

```java
package com.ruoyi.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.Result;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import com.ruoyi.openliststrm.pt.task.DownloadRecordAdminService;
import com.ruoyi.openliststrm.pt.task.dto.DownloadRecordView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
```

替换为（新增 `Convert`、`BatchRetryResult`、`Arrays`、`List` 导入）：

```java
package com.ruoyi.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.PageResult;
import com.ruoyi.common.core.domain.Result;
import com.ruoyi.common.core.text.Convert;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.pt.subscription.dto.SupplementResult;
import com.ruoyi.openliststrm.pt.task.DownloadRecordAdminService;
import com.ruoyi.openliststrm.pt.task.dto.BatchRetryResult;
import com.ruoyi.openliststrm.pt.task.dto.DownloadRecordView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
```

- [ ] **步骤 2：文件末尾新增批量重试端点**

当前文件第 55-66 行：

```java
    /**
     * 立即重试一条失败的下载记录：按订阅标题+季/集号重新发起一次搜索补集。
     */
    @PostMapping("/{id}/retry")
    public Result<SupplementResult> retry(@PathVariable("id") Integer id) {
        try {
            return Result.success(adminService.retry(id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
```

替换为：

```java
    /**
     * 立即重试一条失败的下载记录：按订阅标题+季/集号重新发起一次搜索补集。
     */
    @PostMapping("/{id}/retry")
    public Result<SupplementResult> retry(@PathVariable("id") Integer id) {
        try {
            return Result.success(adminService.retry(id));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 批量重试选中的失败下载记录，单条失败（如已被并发处理成非 FAILED）不影响其余条目。
     */
    @PostMapping("/batchRetry")
    public Result<BatchRetryResult> batchRetry(@RequestParam("ids") String ids) {
        if (StringUtils.isBlank(ids)) {
            return Result.error("请选择要重试的下载记录");
        }
        List<Integer> idList = Arrays.stream(Convert.toStrArray(ids)).map(Integer::valueOf).toList();
        return Result.success(adminService.retryBatch(idList));
    }
}
```

- [ ] **步骤 3：编译验证**

运行：`mvn compile -pl ruoyi-openliststrm -am -q`

预期：无输出、退出码 0。

- [ ] **步骤 4：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtDownloadRecordRestController.java
git commit -m "feat: PtDownloadRecordRestController新增批量重试端点"
```

---

### 任务 8：前端 API 层——`ptSubscription.ts` 新增三个批量接口

**文件：**
- 修改：`openlist-web/src/api/openlist/ptSubscription.ts:72-76`（文件末尾）

- [ ] **步骤 1：编写最少实现代码**

当前文件第 72-76 行（文件末尾）：

```ts
/** 查订阅最近的匹配/过滤日志，排查"这一轮为什么没抓到" */
export function getSubscriptionSearchLogsApi(id: number) {
  return request.get<any, any[]>(`/openliststrm/pt-subscriptions/${id}/search-logs`)
}
```

替换为（新增三个批量 API 函数）：

```ts
/** 查订阅最近的匹配/过滤日志，排查"这一轮为什么没抓到" */
export function getSubscriptionSearchLogsApi(id: number) {
  return request.get<any, any[]>(`/openliststrm/pt-subscriptions/${id}/search-logs`)
}

/** 批量暂停订阅 */
export function batchPauseSubscriptionApi(ids: number[]) {
  return request.post<any, { successCount: number; failedIds: number[] }>(
    '/openliststrm/pt-subscriptions/batchPause', null, { params: { ids: ids.join(',') } }
  )
}

/** 批量恢复订阅 */
export function batchResumeSubscriptionApi(ids: number[]) {
  return request.post<any, { successCount: number; failedIds: number[] }>(
    '/openliststrm/pt-subscriptions/batchResume', null, { params: { ids: ids.join(',') } }
  )
}

/** 批量删除订阅 */
export function batchDeletePtSubscriptionApi(ids: number[]) {
  return request.post('/openliststrm/pt-subscriptions/batchDelete', null, { params: { ids: ids.join(',') } })
}
```

- [ ] **步骤 2：类型检查验证**

运行：`cd openlist-web && npx vue-tsc --noEmit`

预期：退出码 0，无新增类型错误。

- [ ] **步骤 3：Commit**

```bash
git add openlist-web/src/api/openlist/ptSubscription.ts
git commit -m "feat: 前端新增订阅批量暂停/恢复/删除API"
```

---

### 任务 9：前端 API 层——`ptDownloadRecord.ts` 新增批量重试接口

**文件：**
- 修改：`openlist-web/src/api/openlist/ptDownloadRecord.ts:14-19`（文件末尾）

- [ ] **步骤 1：编写最少实现代码**

当前文件第 14-19 行（文件末尾）：

```ts
/** 立即重试一条失败的下载记录：按订阅标题+季/集号重新发起搜索补集 */
export function retryPtDownloadRecordApi(id: number) {
  return request.post<any, { pushed: boolean; candidateCount: number }>(
    `/openliststrm/pt-download-records/${id}/retry`
  )
}
```

替换为：

```ts
/** 立即重试一条失败的下载记录：按订阅标题+季/集号重新发起搜索补集 */
export function retryPtDownloadRecordApi(id: number) {
  return request.post<any, { pushed: boolean; candidateCount: number }>(
    `/openliststrm/pt-download-records/${id}/retry`
  )
}

/** 批量重试选中的失败下载记录 */
export function batchRetryPtDownloadRecordApi(ids: number[]) {
  return request.post<any, { total: number; pushedCount: number; skippedCount: number }>(
    '/openliststrm/pt-download-records/batchRetry', null, { params: { ids: ids.join(',') } }
  )
}
```

- [ ] **步骤 2：类型检查验证**

运行：`cd openlist-web && npx vue-tsc --noEmit`

预期：退出码 0，无新增类型错误。

- [ ] **步骤 3：Commit**

```bash
git add openlist-web/src/api/openlist/ptDownloadRecord.ts
git commit -m "feat: 前端新增下载记录批量重试API"
```

---

### 任务 10：`usePtDownloadRecord.ts` 新增批量重试选中态与逻辑（TDD）

**文件：**
- 修改：`openlist-web/src/composables/usePtDownloadRecord.ts`（全文件，见下方具体行号）
- 创建：`openlist-web/src/composables/__tests__/usePtDownloadRecord.spec.ts`

- [ ] **步骤 1：编写失败的测试**

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'

// usePtDownloadRecord 内部调用 useRoute()（读 subId query 参数），
// 测试环境没有安装 vue-router 插件，mock 掉整个模块。
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} })
}))

// getList 在 setup 阶段同步调用，mock 掉整个 API 模块避免真实网络请求。
vi.mock('@/api/openlist/ptDownloadRecord', () => ({
  getPtDownloadRecordListApi: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  retryPtDownloadRecordApi: vi.fn(),
  batchRetryPtDownloadRecordApi: vi.fn()
}))

import { usePtDownloadRecord } from '../usePtDownloadRecord'
import { batchRetryPtDownloadRecordApi, getPtDownloadRecordListApi } from '@/api/openlist/ptDownloadRecord'

describe('usePtDownloadRecord 的批量重试', () => {
  let confirmSpy: any
  let successSpy: any

  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
    confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
    successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as any)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('没有选中项时不发起确认框也不调用接口', async () => {
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = []

    await composable.handleBatchRetry()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(batchRetryPtDownloadRecordApi).not.toHaveBeenCalled()
  })

  it('用户取消确认框时不调用批量重试接口', async () => {
    confirmSpy.mockRejectedValue('cancel')
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = [1]

    await composable.handleBatchRetry()

    expect(batchRetryPtDownloadRecordApi).not.toHaveBeenCalled()
  })

  it('有选中项时确认后调用批量重试接口并提示结果，随后清空选中并刷新列表', async () => {
    (batchRetryPtDownloadRecordApi as any).mockResolvedValue({ total: 2, pushedCount: 1, skippedCount: 1 })
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = [1, 2]

    await composable.handleBatchRetry()

    expect(confirmSpy).toHaveBeenCalled()
    expect(batchRetryPtDownloadRecordApi).toHaveBeenCalledWith([1, 2])
    expect(successSpy).toHaveBeenCalledWith('已重新推送 1 条，1 条未搜到或已跳过')
    expect(composable.selectedIds.value).toEqual([])
  })

  it('toggleRecordSelect 在未选中时加入选中，已选中时移除', () => {
    const composable = usePtDownloadRecord()
    composable.toggleRecordSelect({ id: 5 })
    expect(composable.selectedIds.value).toEqual([5])
    composable.toggleRecordSelect({ id: 5 })
    expect(composable.selectedIds.value).toEqual([])
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtDownloadRecord.spec.ts`

预期：FAIL，报错 `composable.selectedIds is undefined`（或 TS 报 `Property 'selectedIds' does not exist`），因为 `usePtDownloadRecord()` 还没有导出 `selectedIds`/`toggleRecordSelect`/`handleBatchRetry`。

- [ ] **步骤 3：编写最少实现代码**

当前 `usePtDownloadRecord.ts` 第 1-11 行：

```ts
import { ref, reactive, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getPtDownloadRecordListApi, retryPtDownloadRecordApi } from '@/api/openlist/ptDownloadRecord'
import type { PtDownloadRecordQuery } from '@/api/openlist/ptDownloadRecord'

/**
 * PT 下载记录 composable：只读列表 + 失败重试，没有增删改，
 * 因此不复用 useTaskList（那个是围绕 CRUD 设计的，硬凑只会留一堆空实现）。
 */
export function usePtDownloadRecord() {
```

替换为：

```ts
import { ref, reactive, computed } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getPtDownloadRecordListApi, retryPtDownloadRecordApi, batchRetryPtDownloadRecordApi } from '@/api/openlist/ptDownloadRecord'
import type { PtDownloadRecordQuery } from '@/api/openlist/ptDownloadRecord'

/**
 * PT 下载记录 composable：只读列表 + 失败重试，没有增删改，
 * 因此不复用 useTaskList（那个是围绕 CRUD 设计的，硬凑只会留一堆空实现）。
 */
export function usePtDownloadRecord() {
```

当前文件第 52-68 行（`retryingIds`/`handleRetry` 之后）：

```ts
  // ---------- 重试 ----------
  const retryingIds = reactive(new Set<number>())

  const handleRetry = async (row: any) => {
    retryingIds.add(row.id)
    try {
      const result = await retryPtDownloadRecordApi(row.id)
      ElMessage[result.pushed ? 'success' : 'info'](
        result.pushed ? '已重新找到并推送下载' : '重试未搜索到匹配资源'
      )
      getList()
    } catch (e) {
      console.error(e)
    } finally {
      retryingIds.delete(row.id)
    }
  }
```

替换为（在 `handleRetry` 之后插入批量选中态 + 批量重试）：

```ts
  // ---------- 重试 ----------
  const retryingIds = reactive(new Set<number>())

  const handleRetry = async (row: any) => {
    retryingIds.add(row.id)
    try {
      const result = await retryPtDownloadRecordApi(row.id)
      ElMessage[result.pushed ? 'success' : 'info'](
        result.pushed ? '已重新找到并推送下载' : '重试未搜索到匹配资源'
      )
      getList()
    } catch (e) {
      console.error(e)
    } finally {
      retryingIds.delete(row.id)
    }
  }

  // ---------- 批量重试 ----------
  const selectionMode = ref(false)
  const selectedIds = ref<number[]>([])

  const toggleRecordSelect = (row: any) => {
    const idx = selectedIds.value.indexOf(row.id)
    if (idx === -1) {
      selectedIds.value.push(row.id)
    } else {
      selectedIds.value.splice(idx, 1)
    }
  }

  const handleBatchRetry = async () => {
    if (!selectedIds.value.length) return
    try {
      await ElMessageBox.confirm(`确认批量重试选中的 ${selectedIds.value.length} 条失败记录？`, '提示', { type: 'warning' })
      const result = await batchRetryPtDownloadRecordApi(selectedIds.value)
      ElMessage.success(`已重新推送 ${result.pushedCount} 条，${result.skippedCount} 条未搜到或已跳过`)
      selectedIds.value = []
      getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }
```

最后，当前文件第 96-101 行（`return` 语句）：

```ts
  return {
    taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
    retryingIds, handleRetry,
    totalPages, prevPage, nextPage, handleSizeChange, searchCollapsed
  }
}
```

替换为：

```ts
  return {
    taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
    retryingIds, handleRetry,
    selectionMode, selectedIds, toggleRecordSelect, handleBatchRetry,
    totalPages, prevPage, nextPage, handleSizeChange, searchCollapsed
  }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtDownloadRecord.spec.ts`

预期：`Test Files 1 passed (1)`、`Tests 4 passed (4)`。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/composables/usePtDownloadRecord.ts openlist-web/src/composables/__tests__/usePtDownloadRecord.spec.ts
git commit -m "feat: usePtDownloadRecord新增批量重试选中态与批量重试逻辑"
```

---

### 任务 11：`usePtSubscription.ts` 新增批量暂停/恢复选中态与排序参数（TDD）

**文件：**
- 修改：`openlist-web/src/composables/usePtSubscription.ts`（全文件，见下方具体行号）
- 创建：`openlist-web/src/composables/__tests__/usePtSubscription.spec.ts`

- [ ] **步骤 1：编写失败的测试**

```ts
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'

// base.getList() 在 setup 阶段同步调用，mock 掉整个 API 模块避免真实网络请求。
vi.mock('@/api/openlist/ptSubscription', () => ({
  getPtSubscriptionListApi: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  addPtSubscriptionApi: vi.fn(),
  updatePtSubscriptionApi: vi.fn(),
  deletePtSubscriptionApi: vi.fn(),
  tmdbSearchApi: vi.fn(),
  subscribeApi: vi.fn(),
  getSubscriptionProgressApi: vi.fn(),
  refreshSubscriptionApi: vi.fn(),
  pauseSubscriptionApi: vi.fn(),
  resumeSubscriptionApi: vi.fn(),
  searchSupplementApi: vi.fn(),
  getSubscriptionSearchLogsApi: vi.fn(),
  batchPauseSubscriptionApi: vi.fn(),
  batchResumeSubscriptionApi: vi.fn(),
  batchDeletePtSubscriptionApi: vi.fn()
}))

import { usePtSubscription } from '../usePtSubscription'
import {
  getPtSubscriptionListApi,
  batchPauseSubscriptionApi,
  batchResumeSubscriptionApi
} from '@/api/openlist/ptSubscription'

describe('usePtSubscription 的批量暂停/恢复', () => {
  let confirmSpy: any
  let successSpy: any

  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
    confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
    successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as any)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('没有选中项时批量暂停不发起确认框', async () => {
    const composable = usePtSubscription()

    await composable.handleBatchPause()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(batchPauseSubscriptionApi).not.toHaveBeenCalled()
  })

  it('有选中项时批量暂停确认后调用接口、提示结果、清空选中并刷新', async () => {
    (batchPauseSubscriptionApi as any).mockResolvedValue({ successCount: 2, failedIds: [3] })
    const composable = usePtSubscription()
    composable.selectedIds.value = [1, 2, 3]

    await composable.handleBatchPause()

    expect(batchPauseSubscriptionApi).toHaveBeenCalledWith([1, 2, 3])
    expect(successSpy).toHaveBeenCalledWith('成功 2 项，1 项已跳过（可能已被删除）')
    expect(composable.selectedIds.value).toEqual([])
  })

  it('全部成功时提示语不带跳过后缀', async () => {
    (batchPauseSubscriptionApi as any).mockResolvedValue({ successCount: 2, failedIds: [] })
    const composable = usePtSubscription()
    composable.selectedIds.value = [1, 2]

    await composable.handleBatchPause()

    expect(successSpy).toHaveBeenCalledWith('成功 2 项')
  })

  it('批量恢复同构：确认后调用 batchResumeSubscriptionApi', async () => {
    (batchResumeSubscriptionApi as any).mockResolvedValue({ successCount: 1, failedIds: [] })
    const composable = usePtSubscription()
    composable.selectedIds.value = [9]

    await composable.handleBatchResume()

    expect(batchResumeSubscriptionApi).toHaveBeenCalledWith([9])
  })

  it('toggleSubSelect 未选中时加入、已选中时移除，isSubSelected 与之同步', () => {
    const composable = usePtSubscription()
    expect(composable.isSubSelected(7)).toBe(false)
    composable.toggleSubSelect({ id: 7 })
    expect(composable.isSubSelected(7)).toBe(true)
    composable.toggleSubSelect({ id: 7 })
    expect(composable.isSubSelected(7)).toBe(false)
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtSubscription.spec.ts`

预期：FAIL（`composable.handleBatchPause is not a function`），因为 `usePtSubscription()` 还没有导出这些批量操作函数。

- [ ] **步骤 3：编写最少实现代码**

当前 `usePtSubscription.ts` 第 1-24 行：

```ts
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTaskList } from './useTaskList'
import {
  getPtSubscriptionListApi,
  addPtSubscriptionApi,
  updatePtSubscriptionApi,
  deletePtSubscriptionApi,
  tmdbSearchApi,
  subscribeApi,
  getSubscriptionProgressApi,
  refreshSubscriptionApi,
  pauseSubscriptionApi,
  resumeSubscriptionApi,
  searchSupplementApi,
  getSubscriptionSearchLogsApi
} from '@/api/openlist/ptSubscription'
import type { SearchParams } from '@/types'

interface PtSubscriptionQuery extends SearchParams {
  title?: string
  mediaType?: string
  status?: string
}

/**
 * PT 订阅 composable
 */
export function usePtSubscription() {
  const base = useTaskList<PtSubscriptionQuery>({
    listApi: getPtSubscriptionListApi,
    addApi: addPtSubscriptionApi,
    updateApi: updatePtSubscriptionApi,
    deleteApi: deletePtSubscriptionApi,
    idField: 'id',
    initForm: () => ({ id: undefined }),
    rules: {},
    defaultQuery: { title: undefined, mediaType: undefined, status: undefined }
  })
```

替换为：

```ts
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useTaskList } from './useTaskList'
import {
  getPtSubscriptionListApi,
  addPtSubscriptionApi,
  updatePtSubscriptionApi,
  deletePtSubscriptionApi,
  tmdbSearchApi,
  subscribeApi,
  getSubscriptionProgressApi,
  refreshSubscriptionApi,
  pauseSubscriptionApi,
  resumeSubscriptionApi,
  searchSupplementApi,
  getSubscriptionSearchLogsApi,
  batchPauseSubscriptionApi,
  batchResumeSubscriptionApi,
  batchDeletePtSubscriptionApi
} from '@/api/openlist/ptSubscription'
import type { SearchParams } from '@/types'

interface PtSubscriptionQuery extends SearchParams {
  title?: string
  mediaType?: string
  status?: string
  sortBy?: string
}

/**
 * PT 订阅 composable
 */
export function usePtSubscription() {
  const base = useTaskList<PtSubscriptionQuery>({
    listApi: getPtSubscriptionListApi,
    addApi: addPtSubscriptionApi,
    updateApi: updatePtSubscriptionApi,
    deleteApi: deletePtSubscriptionApi,
    batchDeleteApi: batchDeletePtSubscriptionApi,
    idField: 'id',
    initForm: () => ({ id: undefined }),
    rules: {},
    defaultQuery: { title: undefined, mediaType: undefined, status: undefined, sortBy: undefined }
  })
```

当前文件第 285-330 行（"行操作"小节，`handleRefresh`~`handleRemove`）之后紧跟"移动端 - 分页辅助"小节。在 `handleRemove` 方法结束之后插入批量暂停/恢复与选中态。当前第 317-332 行：

```ts
  const handleRemove = async (row: any) => {
    try {
      await ElMessageBox.confirm(
        `确认删除订阅「${row.title}」？其集数追踪记录会一并删除。`,
        '警告',
        { type: 'warning' }
      )
      await deletePtSubscriptionApi(row.id)
      ElMessage.success('删除成功')
      base.getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  // ---------- 移动端 - 分页辅助 ----------
```

替换为（插入批量选中态与批量暂停/恢复）：

```ts
  const handleRemove = async (row: any) => {
    try {
      await ElMessageBox.confirm(
        `确认删除订阅「${row.title}」？其集数追踪记录会一并删除。`,
        '警告',
        { type: 'warning' }
      )
      await deletePtSubscriptionApi(row.id)
      ElMessage.success('删除成功')
      base.getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  // ---------- 批量操作 ----------

  const selectionMode = ref(false)

  const toggleSubSelect = (row: any) => {
    const idx = base.selectedIds.value.indexOf(row.id)
    if (idx === -1) {
      base.selectedIds.value.push(row.id)
    } else {
      base.selectedIds.value.splice(idx, 1)
    }
  }

  const isSubSelected = (id: number) => base.selectedIds.value.includes(id)

  /** 批量暂停/恢复共用的结果提示文案："成功 N 项" +（有跳过时）"，M 项已跳过（可能已被删除）" */
  const formatBatchResultMessage = (result: { successCount: number; failedIds: number[] }) => {
    const skipTip = result.failedIds.length ? `，${result.failedIds.length} 项已跳过（可能已被删除）` : ''
    return `成功 ${result.successCount} 项${skipTip}`
  }

  const handleBatchPause = async () => {
    if (!base.selectedIds.value.length) return
    try {
      await ElMessageBox.confirm(`确认批量暂停选中的 ${base.selectedIds.value.length} 个订阅？`, '提示', { type: 'warning' })
      const result = await batchPauseSubscriptionApi(base.selectedIds.value)
      ElMessage.success(formatBatchResultMessage(result))
      base.selectedIds.value = []
      base.getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  const handleBatchResume = async () => {
    if (!base.selectedIds.value.length) return
    try {
      await ElMessageBox.confirm(`确认批量恢复选中的 ${base.selectedIds.value.length} 个订阅？`, '提示', { type: 'warning' })
      const result = await batchResumeSubscriptionApi(base.selectedIds.value)
      ElMessage.success(formatBatchResultMessage(result))
      base.selectedIds.value = []
      base.getList()
    } catch (e) {
      if (e !== 'cancel') console.error(e)
    }
  }

  // ---------- 移动端 - 分页辅助 ----------
```

最后，当前文件第 359-378 行（`return` 语句）：

```ts
  return {
    ...base,
    // 建订阅向导
    subscribeOpen, searchLoading, subscribeLoading, searchResults, searchForm,
    picked, pickedSeason, openSubscribeDialog, doSearch, pick, confirmSubscribe,
    // 进度
    progressOpen, progressLoading, progress, currentSubscription, showProgress,
    // 匹配日志
    searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
    // 过滤规则覆盖
    filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
    openFilterOverride, saveFilterOverride,
    // 搜索补集
    searchDialogOpen, searchDialogLoading, searchDialogKeyword,
    openSeasonSearch, openEpisodeSearch, confirmSearch, toggleAutoSearch,
    // 行操作
    handleRefresh, handlePause, handleResume, handleRemove,
    // 移动端分页 & 搜索面板
    totalPages, prevPage, nextPage, handleSizeChange, searchCollapsed
  }
}
```

替换为：

```ts
  return {
    ...base,
    // 建订阅向导
    subscribeOpen, searchLoading, subscribeLoading, searchResults, searchForm,
    picked, pickedSeason, openSubscribeDialog, doSearch, pick, confirmSubscribe,
    // 进度
    progressOpen, progressLoading, progress, currentSubscription, showProgress,
    // 匹配日志
    searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
    // 过滤规则覆盖
    filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
    openFilterOverride, saveFilterOverride,
    // 搜索补集
    searchDialogOpen, searchDialogLoading, searchDialogKeyword,
    openSeasonSearch, openEpisodeSearch, confirmSearch, toggleAutoSearch,
    // 行操作
    handleRefresh, handlePause, handleResume, handleRemove,
    // 批量操作
    selectionMode, toggleSubSelect, isSubSelected, handleBatchPause, handleBatchResume,
    // 移动端分页 & 搜索面板
    totalPages, prevPage, nextPage, handleSizeChange, searchCollapsed
  }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtSubscription.spec.ts`

预期：`Test Files 1 passed (1)`、`Tests 5 passed (5)`。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/composables/usePtSubscription.ts openlist-web/src/composables/__tests__/usePtSubscription.spec.ts
git commit -m "feat: usePtSubscription新增批量暂停/恢复选中态与排序查询参数"
```

---

### 任务 12：`ptDownloadRecord/index.vue` 模板——批量重试 UI（TDD）

**文件：**
- 修改：`openlist-web/src/views/openlist/ptDownloadRecord/index.vue`（全文件，见下方具体行号；行号为任务 10 完成后的最终文件行号，与composable 改动无关，行号不变）
- 测试：`openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`（追加新 describe 块，不新建文件）

- [ ] **步骤 1：编写失败的测试**

在 `openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts` 中，当前第 14-29 行的 `baseComposable` 函数：

```ts
function baseComposable(overrides: Record<string, any> = {}) {
  return {
    taskList: ref([]),
    loading: ref(false),
    total: ref(0),
    queryParams: reactive({ pageNum: 1, pageSize: 10, title: undefined, state: undefined, subId: undefined }),
    getList: vi.fn(),
    handleQuery: vi.fn(),
    resetQuery: vi.fn(),
    queryRef: ref(),
    retryingIds: reactive(new Set<number>()),
    handleRetry: vi.fn(),
    ...overrides
  }
}
```

替换为（追加批量重试相关的默认字段）：

```ts
function baseComposable(overrides: Record<string, any> = {}) {
  return {
    taskList: ref([]),
    loading: ref(false),
    total: ref(0),
    queryParams: reactive({ pageNum: 1, pageSize: 10, title: undefined, state: undefined, subId: undefined }),
    getList: vi.fn(),
    handleQuery: vi.fn(),
    resetQuery: vi.fn(),
    queryRef: ref(),
    retryingIds: reactive(new Set<number>()),
    handleRetry: vi.fn(),
    selectionMode: ref(false),
    selectedIds: ref<number[]>([]),
    toggleRecordSelect: vi.fn(),
    handleBatchRetry: vi.fn(),
    ...overrides
  }
}
```

在文件末尾（当前第 98 行 `describe('PtDownloadRecord 骨架屏', ...)` 块结束的 `})` 之后）追加新的 describe 块：

```ts

describe('PtDownloadRecord 批量重试', () => {
  it('selectionMode 为 false 时不渲染批量工具条和 checkbox', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
    expect(wrapper.find('.record-card-checkbox').exists()).toBe(false)
  })

  it('selectionMode 为 true 时仅 FAILED 卡片显示 checkbox', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([
        { id: 1, title: 'A', state: 'FAILED', failReason: 'boom' },
        { id: 2, title: 'B', state: 'COMPLETED' }
      ])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.findAll('.record-card-checkbox').length).toBe(1)
  })

  it('批量工具条展示已选数量', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1, 2])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.batch-toolbar').text()).toContain('已选 2 项')
  })

  it('点击批量重试按钮调用 handleBatchRetry', async () => {
    const handleBatchRetry = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      handleBatchRetry
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.batch-retry-btn').trigger('click')
    expect(handleBatchRetry).toHaveBeenCalled()
  })

  it('点击取消按钮退出批量操作模式，隐藏工具条', async () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(true)
    await wrapper.find('.batch-cancel-btn').trigger('click')
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
  })

  it('勾选下载记录调用 toggleRecordSelect', async () => {
    const toggleRecordSelect = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }]),
      toggleRecordSelect
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.record-card-checkbox').trigger('change')
    expect(toggleRecordSelect).toHaveBeenCalled()
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`

预期：FAIL（新增 6 个用例全部失败，报错类似 `expect(wrapper.find('.batch-toolbar').exists()).toBe(false)` 实际是 `true`/找不到该元素——因为模板还没有 `.batch-toolbar`/`.record-card-checkbox`/`.batch-retry-btn`/`.batch-cancel-btn`）。

- [ ] **步骤 3：编写最少实现代码**

当前 `index.vue` 第 30-36 行（action-bar）：

```html
      <div class="action-bar">
        <div class="action-left" />
        <el-button text @click="showSearch = !showSearch">
          <el-icon><Filter /></el-icon>
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </el-button>
      </div>
```

替换为：

```html
      <div class="action-bar">
        <div class="action-left">
          <el-button text @click="selectionMode = !selectionMode">
            {{ selectionMode ? '退出批量操作' : '批量操作' }}
          </el-button>
        </div>
        <el-button text @click="showSearch = !showSearch">
          <el-icon><Filter /></el-icon>
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </el-button>
      </div>

      <div class="batch-toolbar" v-if="selectionMode">
        已选 {{ selectedIds.length }} 项
        <el-button link type="primary" class="batch-retry-btn" :disabled="!selectedIds.length" @click="handleBatchRetry">批量重试</el-button>
        <el-button link class="batch-cancel-btn" @click="selectionMode = false">取消</el-button>
      </div>
```

当前文件第 51-60 行（card-grid 真实渲染分支开头 + record-card 开标签）：

```html
      <div class="card-grid" v-else v-loading="loading">
        <div
          v-for="item in taskList"
          :key="item.id"
          class="record-card"
          :class="{ 'record-card--failed': item.state === 'FAILED' }"
        >
          <div class="record-header">
            <span class="record-title" :title="item.title">{{ item.title }}</span>
            <el-tag :type="stateTagType(item.state)" size="small">{{ stateLabel(item.state) }}</el-tag>
          </div>
```

替换为（在 `record-card` 内、`record-header` 之前插入 checkbox）：

```html
      <div class="card-grid" v-else v-loading="loading">
        <div
          v-for="item in taskList"
          :key="item.id"
          class="record-card"
          :class="{ 'record-card--failed': item.state === 'FAILED' }"
        >
          <el-checkbox
            v-if="selectionMode && item.state === 'FAILED'"
            class="record-card-checkbox"
            :model-value="selectedIds.includes(item.id)"
            @change="toggleRecordSelect(item)"
          />
          <div class="record-header">
            <span class="record-title" :title="item.title">{{ item.title }}</span>
            <el-tag :type="stateTagType(item.state)" size="small">{{ stateLabel(item.state) }}</el-tag>
          </div>
```

当前文件第 134-137 行（`<script setup>` 内的 composable 解构）：

```ts
const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  retryingIds, handleRetry
} = usePtDownloadRecord()
```

替换为：

```ts
const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  retryingIds, handleRetry,
  selectionMode, selectedIds, toggleRecordSelect, handleBatchRetry
} = usePtDownloadRecord()
```

样式部分，当前文件第 207-212 行（`.action-bar`）：

```scss
.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
```

替换为（新增 `.batch-toolbar` 样式，并给 `.record-card` 加 `position: relative` 承载 checkbox overlay）：

```scss
.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.batch-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: var(--osr-radius-sm);
  background: var(--osr-bg-page);
  font-size: 13px;
  color: var(--osr-text-secondary);
}
```

当前文件第 228-241 行（`.record-card`）：

```scss
.record-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  transition: box-shadow var(--osr-transition-fast), border-color var(--osr-transition-fast);

  &:hover {
    box-shadow: var(--osr-shadow-md);
    border-color: var(--osr-border-base);
  }
}
```

替换为（新增 `position: relative` 与 `.record-card-checkbox` overlay 定位）：

```scss
.record-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  transition: box-shadow var(--osr-transition-fast), border-color var(--osr-transition-fast);

  &:hover {
    box-shadow: var(--osr-shadow-md);
    border-color: var(--osr-border-base);
  }
}

.record-card-checkbox {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 1;
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`

预期：`Test Files 1 passed (1)`、`Tests 14 passed (14)`（原有 8 个用例 + 本任务新增 6 个）。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views/openlist/ptDownloadRecord/index.vue openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts
git commit -m "feat: ptDownloadRecord列表页新增批量重试UI，仅FAILED卡片可勾选"
```

---

### 任务 13：`ptSubscription/index.vue` 模板——批量操作 + 按钮收纳 + 排序 + 海报列（TDD）

**文件：**
- 修改：`openlist-web/src/views/openlist/ptSubscription/index.vue`（全文件，见下方具体行号）
- 测试：`openlist-web/src/views/openlist/ptSubscription/__tests__/index.spec.ts`（追加新 describe 块，不新建文件）

本任务把设计文档的"批量操作""按钮收纳""排序""海报列"四项前端 UI 一次性做完（都在同一个文件里，模板结构互相衔接，拆开会导致中间态无法独立跑通模板渲染测试）。

- [ ] **步骤 1：编写失败的测试**

在 `openlist-web/src/views/openlist/ptSubscription/__tests__/index.spec.ts` 中，当前第 29-87 行的 `baseComposable` 函数末尾（`handleRemove: vi.fn(),` 之后、`...overrides` 之前，当前第 84-86 行）：

```ts
    handleRefresh: vi.fn(),
    handlePause: vi.fn(),
    handleResume: vi.fn(),
    handleRemove: vi.fn(),
    ...overrides
  }
}
```

替换为（追加批量操作相关的默认字段，同时补上 `handleDelete`——批量删除按钮复用 `useTaskList` 现成的这个函数）：

```ts
    handleRefresh: vi.fn(),
    handlePause: vi.fn(),
    handleResume: vi.fn(),
    handleRemove: vi.fn(),
    handleDelete: vi.fn(),
    selectedIds: ref<number[]>([]),
    selectionMode: ref(false),
    toggleSubSelect: vi.fn(),
    isSubSelected: vi.fn(() => false),
    handleBatchPause: vi.fn(),
    handleBatchResume: vi.fn(),
    ...overrides
  }
}
```

在文件末尾（当前第 93-113 行 `describe('PtSubscription 骨架屏', ...)` 块结束的 `})` 之后）追加两个新 describe 块：

```ts

describe('PtSubscription 批量操作', () => {
  it('selectionMode 为 false 时不渲染批量工具条和 checkbox', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
    expect(wrapper.find('.sub-card-checkbox').exists()).toBe(false)
  })

  it('selectionMode 为 true 时每张卡片都显示 checkbox', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.sub-card-checkbox').exists()).toBe(true)
  })

  it('批量工具条展示已选数量', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1, 2, 3])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.batch-toolbar').text()).toContain('已选 3 项')
  })

  it('点击批量暂停调用 handleBatchPause', async () => {
    const handleBatchPause = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      handleBatchPause
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    await wrapper.find('.batch-pause-btn').trigger('click')
    expect(handleBatchPause).toHaveBeenCalled()
  })

  it('点击批量恢复调用 handleBatchResume', async () => {
    const handleBatchResume = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      handleBatchResume
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    await wrapper.find('.batch-resume-btn').trigger('click')
    expect(handleBatchResume).toHaveBeenCalled()
  })

  it('点击批量删除调用 handleDelete（复用useTaskList现成批量删除逻辑）', async () => {
    const handleDelete = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      handleDelete
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    await wrapper.find('.batch-delete-btn').trigger('click')
    expect(handleDelete).toHaveBeenCalled()
  })

  it('点击取消退出批量操作模式，隐藏工具条', async () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(true)
    await wrapper.find('.batch-cancel-btn').trigger('click')
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
  })

  it('勾选订阅卡片调用 toggleSubSelect', async () => {
    const toggleSubSelect = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }]),
      toggleSubSelect
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    await wrapper.find('.sub-card-checkbox').trigger('change')
    expect(toggleSubSelect).toHaveBeenCalled()
  })
})

describe('PtSubscription 按钮收纳', () => {
  it('sub-actions 只保留4个直接按钮：进度/下载记录/暂停或恢复/删除', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    const directButtons = wrapper.findAll('.sub-actions > el-button')
    const texts = directButtons.map(b => b.text())
    expect(texts).toEqual(['进度', '下载记录', '暂停', '删除'])
  })

  it('对账/匹配日志/过滤规则/搜索补齐收进更多下拉，不再是直接按钮', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    const directButtonTexts = wrapper.findAll('.sub-actions > el-button').map(b => b.text())
    expect(directButtonTexts).not.toContain('对账')
    expect(directButtonTexts).not.toContain('匹配日志')
    expect(directButtonTexts).not.toContain('过滤规则')
    expect(directButtonTexts).not.toContain('搜索补齐')
    const dropdownItemTexts = wrapper.findAll('el-dropdown-item').map(i => i.text())
    expect(dropdownItemTexts).toEqual(['对账', '匹配日志', '过滤规则', '搜索补齐'])
  })

  it('已暂停的订阅显示恢复按钮而不是暂停按钮', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'PAUSED', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    const texts = wrapper.findAll('.sub-actions > el-button').map(b => b.text())
    expect(texts).toContain('恢复')
    expect(texts).not.toContain('暂停')
  })
})

describe('PtSubscription 排序下拉', () => {
  it('切换排序下拉触发 handleQuery', async () => {
    const handleQuery = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({ handleQuery }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    await wrapper.find('.sort-select').trigger('change')
    expect(handleQuery).toHaveBeenCalled()
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptSubscription/__tests__/index.spec.ts`

预期：FAIL（新增 12 个用例全部失败或报错，例如 `.batch-toolbar`/`.sub-card-checkbox`/`.batch-pause-btn`/`.sort-select` 均找不到，`.sub-actions > el-button` 数量是 8 而不是 4）。

- [ ] **步骤 3：编写最少实现代码**

**3a. action-bar：新增批量操作入口 + 排序下拉**

当前文件第 35-45 行：

```html
      <div class="action-bar">
        <div class="action-left">
          <el-button type="primary" @click="openSubscribeDialog">
            <el-icon><Plus /></el-icon> 新增订阅
          </el-button>
        </div>
        <el-button text @click="showSearch = !showSearch">
          <el-icon><Filter /></el-icon>
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </el-button>
      </div>
```

替换为：

```html
      <div class="action-bar">
        <div class="action-left">
          <el-button type="primary" @click="openSubscribeDialog">
            <el-icon><Plus /></el-icon> 新增订阅
          </el-button>
          <el-button text @click="selectionMode = !selectionMode">
            {{ selectionMode ? '退出批量操作' : '批量操作' }}
          </el-button>
        </div>
        <div class="action-right">
          <el-select
            v-model="queryParams.sortBy"
            class="sort-select"
            placeholder="排序"
            :style="{ width: '150px' }"
            @change="handleQuery"
          >
            <el-option label="默认（最新创建）" value="" />
            <el-option label="上次命中时间" value="lastMatchTime" />
          </el-select>
          <el-button text @click="showSearch = !showSearch">
            <el-icon><Filter /></el-icon>
            {{ showSearch ? '隐藏搜索' : '显示搜索' }}
          </el-button>
        </div>
      </div>

      <div class="batch-toolbar" v-if="selectionMode">
        已选 {{ selectedIds.length }} 项
        <el-button link type="warning" class="batch-pause-btn" :disabled="!selectedIds.length" @click="handleBatchPause">批量暂停</el-button>
        <el-button link type="success" class="batch-resume-btn" :disabled="!selectedIds.length" @click="handleBatchResume">批量恢复</el-button>
        <el-button link type="danger" class="batch-delete-btn" :disabled="!selectedIds.length" @click="handleDelete()">批量删除</el-button>
        <el-button link class="batch-cancel-btn" @click="selectionMode = false">取消</el-button>
      </div>
```

**3b. sub-card：新增 checkbox overlay + sub-actions 收纳为 4 按钮 + 下拉**

当前文件第 63-118 行（`sub-card` 开标签到 `sub-actions` 结束）：

```html
        <div v-for="item in taskList" :key="item.id" class="sub-card">
          <div class="sub-poster">
```

（此处省略中间不变的 `sub-poster`/`sub-info`/`sub-header`/`sub-meta`/`sub-row` 部分，只替换 `sub-card` 开标签这一行与 `sub-actions` 整块）

第 63 行替换为：

```html
        <div v-for="item in taskList" :key="item.id" class="sub-card">
          <el-checkbox
            v-if="selectionMode"
            class="sub-card-checkbox"
            :model-value="isSubSelected(item.id)"
            @change="toggleSubSelect(item)"
          />
          <div class="sub-poster">
```

当前文件第 108-118 行（`sub-actions` 整块）：

```html
            <div class="sub-actions">
              <el-button link type="primary" @click="showProgress(item)">进度</el-button>
              <el-button link type="primary" @click="openSeasonSearch(item)">搜索补齐</el-button>
              <el-button link type="primary" @click="handleRefresh(item)">对账</el-button>
              <el-button link type="primary" @click="goDownloadRecords(item)">下载记录</el-button>
              <el-button link type="primary" @click="showSearchLogs(item)">匹配日志</el-button>
              <el-button link type="primary" @click="openFilterOverride(item)">过滤规则</el-button>
              <el-button v-if="item.status !== 'PAUSED'" link type="warning" @click="handlePause(item)">暂停</el-button>
              <el-button v-else link type="success" @click="handleResume(item)">恢复</el-button>
              <el-button link type="danger" @click="handleRemove(item)">删除</el-button>
            </div>
```

替换为：

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

**3c. TMDb 选片表格：加海报列**

当前文件第 154-173 行：

```html
      <el-table
        v-loading="searchLoading"
        :data="searchResults"
        height="300"
        highlight-current-row
        @current-change="pick"
      >
        <el-table-column label="标题" min-width="200" show-overflow-tooltip>
          <template #default="scope">
            {{ scope.row.title }}
            <span v-if="scope.row.originalTitle && scope.row.originalTitle !== scope.row.title" class="sub-year">
              / {{ scope.row.originalTitle }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="年份" prop="year" width="80" align="center">
          <template #default="scope">{{ scope.row.year || '-' }}</template>
        </el-table-column>
        <el-table-column label="TMDb ID" prop="tmdbId" width="100" align="center" />
      </el-table>
```

替换为（新增"海报"列，放在"标题"列之前）：

```html
      <el-table
        v-loading="searchLoading"
        :data="searchResults"
        height="300"
        highlight-current-row
        @current-change="pick"
      >
        <el-table-column label="海报" width="64" align="center">
          <template #default="scope">
            <img
              v-if="scope.row.posterPath"
              :src="posterUrl(scope.row.posterPath)"
              class="search-poster"
              loading="lazy"
              @error="(e: Event) => ((e.target as HTMLImageElement).style.visibility = 'hidden')"
            />
            <el-icon v-else class="search-poster-placeholder"><Picture /></el-icon>
          </template>
        </el-table-column>
        <el-table-column label="标题" min-width="200" show-overflow-tooltip>
          <template #default="scope">
            {{ scope.row.title }}
            <span v-if="scope.row.originalTitle && scope.row.originalTitle !== scope.row.title" class="sub-year">
              / {{ scope.row.originalTitle }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="年份" prop="year" width="80" align="center">
          <template #default="scope">{{ scope.row.year || '-' }}</template>
        </el-table-column>
        <el-table-column label="TMDb ID" prop="tmdbId" width="100" align="center" />
      </el-table>
```

**3d. `<script setup>`：导入 `ArrowDown`、解构新增导出、新增 `handleMoreCommand`**

当前文件第 365-395 行：

```ts
<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { Picture } from '@element-plus/icons-vue'
import { usePtSubscription } from '@/composables/usePtSubscription'

const router = useRouter()
const showSearch = ref(window.innerWidth >= 768)
/** 海报加载失败的订阅 id 集合，命中则展示占位图标而非裂图 */
const posterErrorIds = reactive(new Set<number>())

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  subscribeOpen, searchLoading, subscribeLoading, searchResults, searchForm,
  picked, pickedSeason, openSubscribeDialog, doSearch, pick, confirmSubscribe,
  progressOpen, progressLoading, progress, currentSubscription, showProgress,
  searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
  filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
  openFilterOverride, saveFilterOverride,
  searchDialogOpen, searchDialogLoading, searchDialogKeyword,
  openSeasonSearch, openEpisodeSearch, confirmSearch, toggleAutoSearch,
  handleRefresh, handlePause, handleResume, handleRemove
} = usePtSubscription()

/** TMDb 海报路径拼完整图片地址，w200 宽度足够列表缩略图使用 */
const posterUrl = (path: string) => `https://image.tmdb.org/t/p/w200${path}`

const goDownloadRecords = (row: any) => {
  router.push({ path: '/openlist/ptDownloadRecord', query: { subId: row.id } })
}
</script>
```

替换为：

```ts
<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { Picture, ArrowDown } from '@element-plus/icons-vue'
import { usePtSubscription } from '@/composables/usePtSubscription'

const router = useRouter()
const showSearch = ref(window.innerWidth >= 768)
/** 海报加载失败的订阅 id 集合，命中则展示占位图标而非裂图 */
const posterErrorIds = reactive(new Set<number>())

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  subscribeOpen, searchLoading, subscribeLoading, searchResults, searchForm,
  picked, pickedSeason, openSubscribeDialog, doSearch, pick, confirmSubscribe,
  progressOpen, progressLoading, progress, currentSubscription, showProgress,
  searchLogOpen, searchLogLoading, searchLogs, showSearchLogs,
  filterOverrideOpen, filterOverrideSaving, filterOverrideForm,
  openFilterOverride, saveFilterOverride,
  searchDialogOpen, searchDialogLoading, searchDialogKeyword,
  openSeasonSearch, openEpisodeSearch, confirmSearch, toggleAutoSearch,
  handleRefresh, handlePause, handleResume, handleRemove, handleDelete,
  selectedIds, selectionMode, toggleSubSelect, isSubSelected,
  handleBatchPause, handleBatchResume
} = usePtSubscription()

/** TMDb 海报路径拼完整图片地址，w200 宽度足够列表缩略图使用 */
const posterUrl = (path: string) => `https://image.tmdb.org/t/p/w200${path}`

const goDownloadRecords = (row: any) => {
  router.push({ path: '/openlist/ptDownloadRecord', query: { subId: row.id } })
}

/** "更多"下拉菜单 command → 现有函数的分发，纯路由不新增业务逻辑 */
const handleMoreCommand = (cmd: string, row: any) => {
  switch (cmd) {
    case 'refresh': handleRefresh(row); break
    case 'logs': showSearchLogs(row); break
    case 'filter': openFilterOverride(row); break
    case 'search': openSeasonSearch(row); break
  }
}
</script>
```

**3e. 样式：新增 `.batch-toolbar`/`.action-right`/`.sub-card-checkbox`/`.search-poster`，`.sub-card` 加 `position: relative`**

当前文件第 426-437 行（`.action-bar`）：

```scss
.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;

  .action-left {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }
}
```

替换为（新增 `.action-right` 与 `.batch-toolbar`）：

```scss
.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;

  .action-left {
    display: flex;
    gap: 6px;
    flex-wrap: wrap;
  }

  .action-right {
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  }
}

.batch-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: var(--osr-radius-sm);
  background: var(--osr-bg-page);
  font-size: 13px;
  color: var(--osr-text-secondary);
}
```

当前文件第 456-468 行（`.sub-card`）：

```scss
.sub-card {
  display: flex;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  transition: box-shadow var(--osr-transition-fast), border-color var(--osr-transition-fast);

  &:hover {
    box-shadow: var(--osr-shadow-md);
    border-color: var(--osr-border-base);
  }
}
```

替换为（新增 `position: relative` 与 `.sub-card-checkbox` overlay 定位）：

```scss
.sub-card {
  position: relative;
  display: flex;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  transition: box-shadow var(--osr-transition-fast), border-color var(--osr-transition-fast);

  &:hover {
    box-shadow: var(--osr-shadow-md);
    border-color: var(--osr-border-base);
  }
}

.sub-card-checkbox {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 1;
}
```

在样式文件末尾（当前第 663-666 行，`.override-checkbox` 之后、`</style>` 之前）新增海报缩略样式：

```scss
.override-checkbox {
  margin-right: 10px;
}

.search-poster {
  width: 40px;
  height: 60px;
  object-fit: cover;
  border-radius: var(--osr-radius-sm);
  display: block;
  margin: 0 auto;
}

.search-poster-placeholder {
  width: 40px;
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto;
  color: var(--osr-text-disabled);
  font-size: 18px;
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptSubscription/__tests__/index.spec.ts`

预期：`Test Files 1 passed (1)`、`Tests 14 passed (14)`（原有 2 个用例 + 本任务新增 12 个）。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views/openlist/ptSubscription/index.vue openlist-web/src/views/openlist/ptSubscription/__tests__/index.spec.ts
git commit -m "feat: ptSubscription列表页新增批量操作、按钮收纳、按上次命中排序与TMDb海报列"
```

---

### 任务 14：整体回归验证与手动走查（无新代码变更）

**文件：** 无新增/修改文件，本任务只做验证，不改动实现代码。

**背景**：任务 1-13 各自的单测只覆盖各自新增的结构性/业务逻辑断言，不能替代"后端接口真的能启动并正确响应""排序在真实 MySQL 上表现符合预期""下拉菜单/checkbox 在真实浏览器里可点击"的确认。这一步对齐设计文档第 9 节"测试计划"，也是 `AGENTS.md` 要求的"新增 bean/端点后必须做启动验证"。

- [ ] **步骤 1：跑后端全量单元测试**

运行：`mvn test -pl ruoyi-openliststrm -am`

预期：`BUILD SUCCESS`，其中 `SubscriptionServiceTest`（24 用例）、`DownloadRecordAdminServiceTest`（20 用例）全部通过。

- [ ] **步骤 2：后端完整打包**

运行：`mvn clean package -DskipTests`

预期：`BUILD SUCCESS`，生成 `ruoyi-admin/target/ruoyi-admin.jar`。

- [ ] **步骤 3：跑前端全量单元测试**

运行：`cd openlist-web && npm run test:unit`

预期：全部 Test Files/Tests 通过，其中应包含本计划新增/修改的 4 个测试文件：`usePtDownloadRecord.spec.ts`（4 用例）、`usePtSubscription.spec.ts`（5 用例）、`ptDownloadRecord/__tests__/index.spec.ts`（14 用例）、`ptSubscription/__tests__/index.spec.ts`（14 用例）。

- [ ] **步骤 4：跑前端类型检查与完整构建**

运行：`cd openlist-web && npm run build`

预期：退出码 0，输出以 `✓ built in` 结尾（`vue-tsc` 无类型错误）。若模板里用到的 `ArrowDown` 图标触发 `unplugin-vue-components` 自动生成 `components.d.ts` 新条目，把该文件变更一并 `git add` 提交。

- [ ] **步骤 5：跑前端 ESLint**

运行：`cd openlist-web && npm run lint`

预期：退出码 0，无残留错误。

- [ ] **步骤 6：容器化启动验证（新增 Controller 端点必须做）**

运行：`docker compose up -d --build --no-deps backend`

等待约 30 秒后运行：`docker ps --filter "name=osr-backend" --format "{{.Names}}\t{{.Status}}"`

预期：状态里 `restarts` 计数为 0（若反复重启，按 `AGENTS.md` 说明 `docker cp osr-backend:/data/logs ./tmp` 后看 `sys-error.log` 排查，常见原因是 bean 装配失败）。

- [ ] **步骤 7：手动接口验证——批量端点与排序**

后端确认启动成功后，用浏览器或 `curl` 登录后手动验证（需要携带登录 token，此处给出接口路径供人工用 Postman/前端页面调用核对，不写具体 curl 脚本，因为需要真实登录态）：

- 建 2-3 个测试订阅，选中其中 2 个点"批量暂停"，确认列表刷新后这 2 个订阅状态变为"已暂停"，提示"成功 2 项"。
- 对暂停的订阅点"批量恢复"，确认状态变回"订阅中"。
- 选中 1 个订阅点"批量删除"，确认订阅与其 `pt_subscription_episode` 关联行都被删除（可查库确认 `pt_subscription_episode` 里没有残留该 `sub_id` 的行）。
- 给其中一个订阅手动触发一次"搜索补齐"使其产生 `lastMatchTime`，切换排序下拉到"上次命中时间"，确认有 `lastMatchTime` 的订阅排在前面、从未命中过的排在最后。
- 造 2 条 `state=FAILED` 的下载记录，进入下载记录页开启批量操作，确认只有这 2 条出现 checkbox，其余状态的记录不出现；勾选后点"批量重试"，确认收到"已重新推送 N 条，M 条未搜到或已跳过"的提示。

- [ ] **步骤 8：手动视觉走查清单**

启动本地开发环境（`cd openlist-web && npm run dev`，配合已运行的后端 `localhost:6895`），登录后依次确认：

- **订阅卡片按钮收纳**：`sub-actions` 只剩"进度/下载记录/暂停或恢复/删除"4 个常驻按钮 + 一个"更多"下拉；点开"更多"能看到"对账/匹配日志/过滤规则/搜索补齐"4 项，点击每一项行为与收纳前一致（对账刷新列表、匹配日志弹窗打开、过滤规则弹窗打开、搜索补齐弹窗打开）。
- **批量操作 checkbox overlay**：点击"批量操作"后，订阅页每张卡片左上角、下载记录页仅 FAILED 卡片右上角出现 checkbox，不遮挡卡片原有内容；工具条正确显示"已选 N 项"，N=0 时三个批量按钮禁用置灰。
- **排序下拉**：切换到"上次命中时间"后列表顺序符合预期，切回"默认（最新创建）"后恢复按 id 倒序。
- **建订阅弹窗海报列**：TMDb 搜索结果表格新增的"海报"列能正常显示缩略图；`posterPath` 为空或图片 404 时降级为占位图标，不影响选片操作（点击整行仍能选中）。

---

## 自检记录（写计划时已完成，问题已直接改正，无需再走一遍审查）

- **规格覆盖度**：设计文档第 3 节（批量暂停/恢复/删除订阅、批量重试下载记录）→ 任务 1-2、4（后端）+ 8、10、12（前端）；第 4 节（按钮收纳）→ 任务 13；第 5 节（按上次命中时间排序）→ 任务 3-4（后端）+ 11、13（前端）；第 6 节（TMDb 海报列）→ 任务 13；第 9 节测试计划里列出的后端用例（`pauseBatch`/`resumeBatch`/`retryBatch` 的全部子场景）→ 任务 2、6 逐条覆盖；第 9 节"前端手动验证"清单 → 任务 14 步骤 7-8 逐条覆盖。第 10 节"不做的事情"未新增任何相关任务，符合范围限定。
- **占位符扫描**：全文档没有"待定/TODO/后续实现/补充细节/类似任务N"等模式，每个代码步骤都给了完整可运行代码块。
- **类型/方法名一致性**：`BatchOperationResult{successCount, failedIds}`、`BatchRetryResult{total, pushedCount, skippedCount}`、`pauseBatch`/`resumeBatch`/`retryBatch`、`batchPauseSubscriptionApi`/`batchResumeSubscriptionApi`/`batchDeletePtSubscriptionApi`/`batchRetryPtDownloadRecordApi`、`selectionMode`/`selectedIds`/`toggleSubSelect`/`isSubSelected`/`toggleRecordSelect`/`handleBatchPause`/`handleBatchResume`/`handleBatchRetry`/`handleMoreCommand` 在定义任务与后续使用任务里前后一致，已核对无漂移。
- **两处纠偏已在"前置说明"里写明并按纠偏后的版本落实到具体任务**：`handleMoreCommand` 只出现在 `ptSubscription/index.vue` 的 `<script setup>`（任务 13），未列入 `usePtSubscription.ts` 的改动（任务 11）；`handleBatchPause`/`handleBatchResume`/`handleBatchRetry` 均补了 `try/catch` 包裹 `ElMessageBox.confirm`，避免用户点取消时出现未捕获的 Promise rejection。
