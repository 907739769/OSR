# PT 订阅下载后端健壮性优化 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 修复 `SubscriptionEngine`/`DownloadTrackService` 的四个后端内部健壮性问题——下载器列表重复查询、失败原因不可分类、僵尸超时写死、下载器负载不均——同时保持所有对外接口签名不变。

**架构：** `SubscriptionEngine.process()`/`pushBest()` 在循环外各自查一次启用下载器列表与下载器负载统计，通过新增参数传给 `handleGroup`；`resolveDownloader` 从"永远选第一个"改为"负载最少优先，显式指定不变"。`DownloadTrackService` 新增 `FailReasonCode` 枚举把失败原因结构化落库，并把僵尸超时从硬编码常量改为"全局配置 + 订阅级 JSON 覆盖"两级，覆盖解析逻辑与既有 `FilterCriteriaFactory` 同源但因字段少而不单独成类。两张表各自新增一列（`pt_download_record.fail_reason_code`、`pt_subscription.download_override`），历史数据不回填。前端下载记录页（PC + 移动端）新增一个只读展示标签。

**技术栈：** Java 25 (Spring Boot 4.0.6) + MyBatis-Plus（`BaseMapper`/`IService`）+ FastJSON2 + JUnit5/Mockito；Vue 3 + Element Plus + Vitest。

---

## 前置说明

- 本计划严格对照 `docs/superpowers/specs/2026-07-24-pt-backend-robustness-design.md` 编写，实现前已通读以下真实源码：`SubscriptionEngine.java`、`DownloadTrackService.java`、`PtDownloadRecordPlus.java`、`PtSubscriptionPlus.java`、`DownloadRecordView.java`、`DownloadRecordAdminService.java`、`MysqlDdl.java`、`FilterCriteriaFactory.java`、`DownloadRecordState.java`、`SubscriptionEpisodeState.java`、`SubscriptionEngineTest.java`、`DownloadTrackServiceTest.java`、`DownloadRecordAdminServiceTest.java`、两个 `ptDownloadRecord/index.vue`、`usePtDownloadRecord.ts`、PC 端的 `index.spec.ts`。
- 任务 4 与任务 5 都修改 `DownloadTrackService.java` 的同一批方法（`track()`/`fail()`），**必须按顺序执行**：任务 4 先落地僵尸超时配置化，任务 5 在此基础上加失败分类。任务 5 给出的"修改"行号是基于任务 4完成后的文件——动手前务必用 Read 工具重新确认当前行号，行号如有偏差以文件真实内容为准。
- 任务 7（`SubscriptionEngine`）与任务 1-6（`DownloadTrackService`/数据模型/前端）相互独立，可以调换顺序，但本计划按"数据模型 → DownloadTrackService → 展示层 → SubscriptionEngine → 前端 → 全量验证"的顺序排列，方便增量验证编译。
- 所有 `mvn` 命令均在仓库根目录 `D:\idea project\OpenList-strm-RuoYi\.claude\worktrees\pt-subscription-download-optimization-c356b8` 下执行。

---

### 任务 1：新增失败原因分类枚举 `FailReasonCode`

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/FailReasonCode.java`

**背景**：`pt/task` 包下已有 `DownloadRecordState.java`、`pt/subscription` 包下已有 `SubscriptionEpisodeState.java`，两者都是"纯值枚举 + `value()` 方法"，都没有专门的单元测试类（枚举本身没有可测试的行为分支）。`FailReasonCode` 遵循同样的风格与同样"不写专门测试类"的既有处理方式。

- [ ] **步骤 1：创建枚举文件**

```java
package com.ruoyi.openliststrm.pt.task;

/**
 * 下载失败原因的结构化分类，落库到 {@code pt_download_record.fail_reason_code}，
 * 供前端下载记录页展示分类标签、未来按维度筛选/统计使用。
 * 风格与 {@link DownloadRecordState}/{@link com.ruoyi.openliststrm.pt.subscription.SubscriptionEpisodeState} 一致。
 *
 * @author Jack
 */
public enum FailReasonCode {

    /** 下载器里已经找不到对应种子（可能被删除，或磁力元数据解析失败） */
    TORRENT_NOT_FOUND("TORRENT_NOT_FOUND"),
    /** 种子仍在下载器里但超过僵尸超时仍未完成 */
    ZOMBIE_TIMEOUT("ZOMBIE_TIMEOUT"),
    /** 兜底分类：当前代码里没有其他失败路径会产生 FAILED 记录，为将来的失败路径（如推送失败落记录）预留 */
    OTHER("OTHER");

    private final String value;

    FailReasonCode(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
```

- [ ] **步骤 2：编译验证（无专门单测，纯值枚举没有可测试的行为分支，与 `DownloadRecordState`/`SubscriptionEpisodeState` 的既有处理方式一致，由任务 5 的 `DownloadTrackServiceTest` 断言间接覆盖）**

运行：`mvn compile -pl ruoyi-openliststrm -am -q`
预期：无输出、退出码 0（编译通过）

- [ ] **步骤 3：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/FailReasonCode.java
git commit -m "feat: 新增下载失败原因分类枚举 FailReasonCode"
```

---

### 任务 2：`pt_download_record` 新增 `fail_reason_code` 列

**文件：**
- 创建：`ruoyi-common/src/main/resources/sql/20260738-pt-download-record-fail-reason-code.sql`
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/domain/PtDownloadRecordPlus.java:83-89`
- 修改：`ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java:58-63`

**背景**：迁移脚本沿用 `ruoyi-common/src/main/resources/sql/20260734-pt-episode-fail-count.sql` 的幂等写法（先查 `INFORMATION_SCHEMA.COLUMNS` 判断列是否存在，用动态 SQL + `PREPARE`/`EXECUTE` 执行）。`PtDownloadRecordPlus` 是纯 `@Getter @Setter` 的 MyBatis-Plus 实体，新增字段不需要额外逻辑；不回填历史数据（历史 `FAILED` 记录该列保持 `NULL`，前端按"未分类"处理）。

- [ ] **步骤 1：创建迁移脚本**

```sql
-- ----------------------------
-- 20260738: pt_download_record 增加 fail_reason_code 列，用于失败原因结构化分类展示（幂等脚本）
-- 只加列，不回填历史数据：历史 FAILED 记录该列为 NULL，前端按"未分类"处理。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'fail_reason_code');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `fail_reason_code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''失败原因分类：TORRENT_NOT_FOUND/ZOMBIE_TIMEOUT/OTHER，历史失败记录为 NULL 表示未分类'' AFTER `fail_reason`', 'SELECT ''Column fail_reason_code already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

- [ ] **步骤 2：`PtDownloadRecordPlus` 新增字段**

在 `/** 失败原因 */ @TableField("fail_reason") private String failReason;` 之后（原文件第 83-85 行）新增：

```java
    /** 失败原因 */
    @TableField("fail_reason")
    private String failReason;

    /** 失败原因结构化分类：TORRENT_NOT_FOUND/ZOMBIE_TIMEOUT/OTHER，历史记录（分类能力上线前产生）为 null */
    @TableField("fail_reason_code")
    private String failReasonCode;

    /** 推送时间 */
```

（即在 `failReason` 字段与 `pushedTime` 字段之间插入 `failReasonCode`）

- [ ] **步骤 3：`MysqlDdl.getSqlFiles()` 追加脚本路径**

在列表最后一项 `"sql/20260737-fix-menu-group-icon-duplication.sql"` 之后追加：

```java
                "sql/20260737-fix-menu-group-icon-duplication.sql",
                "sql/20260738-pt-download-record-fail-reason-code.sql"
        );
```

- [ ] **步骤 4：编译验证（纯数据结构变更，无独立可测试逻辑；由任务 5、任务 6 的测试间接覆盖 `failReasonCode` 的读写）**

运行：`mvn compile -pl ruoyi-common,ruoyi-openliststrm -am -q`
预期：无输出、退出码 0

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-common/src/main/resources/sql/20260738-pt-download-record-fail-reason-code.sql \
        ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/domain/PtDownloadRecordPlus.java \
        ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java
git commit -m "feat: pt_download_record 新增 fail_reason_code 列"
```

---

### 任务 3：`pt_subscription` 新增 `download_override` 列

**文件：**
- 创建：`ruoyi-common/src/main/resources/sql/20260739-pt-subscription-download-override.sql`
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/domain/PtSubscriptionPlus.java:67-73`
- 修改：`ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java`（任务 2 步骤 3 修改后的文件）

**背景**：与 `filter_override` 同类字段，紧跟其后声明，体现两者语义同源但用途不同（过滤 vs 下载追踪）。

- [ ] **步骤 1：创建迁移脚本**

```sql
-- ----------------------------
-- 20260739: pt_subscription 增加 download_override 列，用于订阅级下载追踪参数覆盖（幂等脚本）
-- 当前仅支持 zombieTimeoutHours 键，空表示全用全局配置，语义与 filter_override 一致但字段独立。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'download_override');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `download_override` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''订阅级下载追踪覆盖(JSON)，当前仅支持 zombieTimeoutHours 键，空表示全用全局配置'' AFTER `filter_override`', 'SELECT ''Column download_override already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
```

- [ ] **步骤 2：`PtSubscriptionPlus` 新增字段**

在 `/** 订阅级过滤覆盖(JSON)，空表示全用全局配置 */ @TableField("filter_override") private String filterOverride;`（原文件第 67-69 行）之后、`downloaderId` 字段之前插入：

```java
    /** 订阅级过滤覆盖(JSON)，空表示全用全局配置 */
    @TableField("filter_override")
    private String filterOverride;

    /** 订阅级下载追踪覆盖(JSON)，当前仅支持 zombieTimeoutHours 键，空表示全用全局配置 */
    @TableField("download_override")
    private String downloadOverride;

    /** 指定下载器，空表示用唯一启用的那个 */
```

- [ ] **步骤 3：`MysqlDdl.getSqlFiles()` 追加脚本路径**

在任务 2 步骤 3 追加的 `"sql/20260738-pt-download-record-fail-reason-code.sql"` 之后再追加一项：

```java
                "sql/20260738-pt-download-record-fail-reason-code.sql",
                "sql/20260739-pt-subscription-download-override.sql"
        );
```

- [ ] **步骤 4：编译验证**

运行：`mvn compile -pl ruoyi-common,ruoyi-openliststrm -am -q`
预期：无输出、退出码 0

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-common/src/main/resources/sql/20260739-pt-subscription-download-override.sql \
        ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/domain/PtSubscriptionPlus.java \
        ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java
git commit -m "feat: pt_subscription 新增 download_override 列"
```

---

### 任务 4：`DownloadTrackService` — 僵尸超时全局默认值 + 订阅级覆盖

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadTrackService.java`（全文，见下方对照）
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadTrackServiceTest.java`

**背景**：`ZOMBIE_TIMEOUT_MILLIS` 目前是写死的 `static final` 常量（24 小时）。改造后：全局默认值通过 `@Value("${pt.download.zombie-timeout-hours:24}")` 注入（默认值与现状一致，不改配置文件的用户无感知）；订阅可通过 `pt_subscription.download_override` JSON 里的 `zombieTimeoutHours` 键覆盖，解析逻辑与 `FilterCriteriaFactory`（`containsKey` 判断显式覆盖、`try/catch` 兜底、非法值回退）同一防御性写法，但因为只有 1 个字段，不单独成类，直接写成 `DownloadTrackService` 的私有方法。

- [ ] **步骤 1：编写失败的测试——先改 `DownloadTrackServiceTest` 的 `service()` 工厂方法与导入，让编译先失败**

在 `DownloadTrackServiceTest.java` 顶部导入区（原文件第 1-34 行）新增两个导入：

```java
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
```

（放在 `import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;` 之后、`import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;` 分别对应位置——`domain.PtSubscriptionPlus` 紧跟 `domain.PtSubscriptionEpisodePlus` 之后，`service.IPtSubscriptionPlusService` 紧跟 `service.IPtSubscriptionEpisodePlusService` 之后）

新增 mock 字段与修改 `service()` 工厂方法（原文件第 40-46 行）：

```java
    @Mock private IPtDownloadRecordPlusService recordService;
    @Mock private IPtSubscriptionEpisodePlusService episodeService;
    @Mock private DownloadCompletionSyncTrigger completionSyncTrigger;
    @Mock private IPtSubscriptionPlusService subscriptionService;

    private DownloadTrackService service() {
        // 默认桩：查不到任何订阅（对应"订阅已删除"分支），使全部现有用例回退全局默认值 24 小时，
        // 与改造前的行为保持一致，不用逐个用例改断言。
        when(subscriptionService.listByIds(any())).thenReturn(List.of());
        return new DownloadTrackService(recordService, episodeService, completionSyncTrigger, subscriptionService, 3, 24);
    }
```

新增四个测试方法（追加到类末尾 `}` 之前，即原文件第 289-304 行"失败重试熔断"分组之后）：

```java
    // ---------- 僵尸超时：全局默认值 + 订阅级覆盖 ----------

    @Test
    void 订阅设置僵尸超时覆盖_按覆盖值判定僵尸超时() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        // 覆盖为 1 小时，记录已推送 90 分钟——超过覆盖值 1 小时，但远不到全局默认 24 小时
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 90 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(10);
        sub.setDownloadOverride("{\"zombieTimeoutHours\": 1}");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("FAILED", captor.getValue().getState());
    }

    @Test
    void 订阅覆盖非法JSON_回退全局默认值不抛异常() {
        // 记录已推送 90 分钟：若非法覆盖被误当成短超时会判失败；正确回退 24 小时默认值应仍是下载中
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 90 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(10);
        sub.setDownloadOverride("{");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).updateById(captor.capture());
        assertEquals("DOWNLOADING", captor.getValue().getState());
    }

    @Test
    void 订阅覆盖僵尸超时为零_视为无效回退全局默认值() {
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 90 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        PtSubscriptionPlus sub = new PtSubscriptionPlus();
        sub.setId(10);
        sub.setDownloadOverride("{\"zombieTimeoutHours\": 0}");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).updateById(captor.capture());
        assertEquals("DOWNLOADING", captor.getValue().getState());
    }

    @Test
    void 订阅已删除_listByIds查不到_回退全局默认值() {
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 90 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(subscriptionService.listByIds(any())).thenReturn(List.of()); // 订阅已删除

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).updateById(captor.capture());
        assertEquals("DOWNLOADING", captor.getValue().getState());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=DownloadTrackServiceTest`
预期：编译失败（COMPILATION ERROR），报错找不到 `DownloadTrackService(IPtDownloadRecordPlusService, IPtSubscriptionEpisodePlusService, DownloadCompletionSyncTrigger, IPtSubscriptionPlusService, int, int)` 构造器（因为生产代码构造器还是旧的 4 参数签名）

- [ ] **步骤 3：编写最少实现代码——修改 `DownloadTrackService.java`**

导入区（原文件第 1-19 行）改为：

```java
package com.ruoyi.openliststrm.pt.task;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.helper.TgHelper;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionEpisodePlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionEpisodePlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.downloader.model.DownloaderTorrent;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionEpisodeState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
```

常量、字段与构造器（原文件第 39-62 行，`GRACE_MILLIS` 到构造器结束）整体替换为：

```java
    /** 推送后找不到对应种子的宽限期：超过它才判失败（qB 解析磁力元数据需要时间） */
    private static final long GRACE_MILLIS = 10 * 60 * 1000L;

    /** 同一集连续失败达到该次数后不再回退 MISSING，转 BLOCKED 停止自动重试，避免已下架/失效资源被无限次静默重试 */
    private final int maxConsecutiveFailures;

    /** 附录C 绝对时长兜底的全局默认值：推送超过该时长仍未完成的记录一律判失败并回退集，
     *  覆盖「种子还在下载器但 0 做种卡死」这类 grace 分支照不到的僵尸种子。
     *  订阅可通过 pt_subscription.download_override 里的 zombieTimeoutHours 键覆盖此默认值。
     *  代价：真实的超长慢速下载超过该时长也会被释放（其 guid 按附录B 拉黑，该集靠别的种子恢复）。 */
    private final long zombieTimeoutMillisDefault;

    private final IPtDownloadRecordPlusService recordService;
    private final IPtSubscriptionEpisodePlusService episodeService;
    private final DownloadCompletionSyncTrigger completionSyncTrigger;
    private final IPtSubscriptionPlusService subscriptionService;

    public DownloadTrackService(IPtDownloadRecordPlusService recordService,
                                IPtSubscriptionEpisodePlusService episodeService,
                                DownloadCompletionSyncTrigger completionSyncTrigger,
                                IPtSubscriptionPlusService subscriptionService,
                                @Value("${pt.download.max-consecutive-failures:3}") int maxConsecutiveFailures,
                                @Value("${pt.download.zombie-timeout-hours:24}") int zombieTimeoutHoursDefault) {
        this.recordService = recordService;
        this.episodeService = episodeService;
        this.completionSyncTrigger = completionSyncTrigger;
        this.subscriptionService = subscriptionService;
        this.maxConsecutiveFailures = maxConsecutiveFailures;
        this.zombieTimeoutMillisDefault = zombieTimeoutHoursDefault * 3600_000L;
    }
```

`track()` 方法（原文件第 64-96 行）整体替换为：

```java
    /**
     * 追踪一个下载器：拉回来的种子已按公共标签过滤过，这里只做状态推进。
     */
    public void track(PtDownloaderPlus downloader, List<DownloaderTorrent> torrents) {
        List<PtDownloadRecordPlus> active = recordService.list(
                new QueryWrapper<PtDownloadRecordPlus>()
                        .eq("downloader_id", downloader.getId())
                        .in("state", STATE_PUSHED, STATE_DOWNLOADING));
        if (active.isEmpty()) {
            return;
        }
        Map<Integer, PtSubscriptionPlus> subCache = loadSubscriptions(active);
        long now = System.currentTimeMillis();
        for (PtDownloadRecordPlus record : active) {
            DownloaderTorrent matched = findByTag(torrents, record.getTrackingTag());
            long age = record.getPushedTime() == null
                    ? Long.MAX_VALUE : now - record.getPushedTime().getTime();
            long zombieTimeoutMillis = resolveZombieTimeoutMillis(subCache.get(record.getSubId()));
            if (matched != null && matched.isCompleted()) {
                complete(record, downloader);
            } else if (matched == null) {
                if (age >= GRACE_MILLIS) {
                    fail(record, "下载器中已找不到该种子（可能被删除或元数据解析失败）");
                }
                // 未超宽限期：qB 可能还在解析元数据，本轮跳过
            } else {
                // 种子还在下载器但未完成
                if (age >= zombieTimeoutMillis) {
                    fail(record, "下载超过 " + (zombieTimeoutMillis / 3600000) + " 小时仍未完成，判定为僵尸种子");
                } else {
                    markDownloading(record, matched.getProgress());
                }
            }
        }
    }

    /**
     * 批量加载本次要处理的记录涉及的全部订阅，循环内按 subId 取用，避免逐条查询（批内缓存，
     * 与问题 1 的 {@code SubscriptionEngine} 批内缓存原则一致）。
     */
    private Map<Integer, PtSubscriptionPlus> loadSubscriptions(List<PtDownloadRecordPlus> records) {
        List<Integer> subIds = records.stream().map(PtDownloadRecordPlus::getSubId).distinct().toList();
        if (subIds.isEmpty()) {
            return Map.of();
        }
        return subscriptionService.listByIds(subIds).stream()
                .collect(Collectors.toMap(PtSubscriptionPlus::getId, s -> s));
    }

    /**
     * 解析订阅级僵尸超时覆盖：只有 downloadOverride JSON 里出现 zombieTimeoutHours 键才覆盖，
     * 格式损坏、值非法（&lt;=0）或订阅为 null（已被删除）时一律回退全局默认值，
     * 绝不让一条脏配置炸掉整轮轮询。写法与 {@code FilterCriteriaFactory} 同源。
     */
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

（`markDownloading`/`findByTag`/`notifySafely`/`complete`/`fail` 等其余方法本步骤不改，保持原样）

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=DownloadTrackServiceTest`
预期：`Tests run: 19, Failures: 0, Errors: 0`（15 个原有用例 + 本任务新增 4 个）

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadTrackService.java \
        ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadTrackServiceTest.java
git commit -m "feat: 僵尸超时支持全局配置与订阅级覆盖"
```

---

### 任务 5：`DownloadTrackService` — 失败原因结构化分类

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadTrackService.java`（任务 4 完成后的文件，动手前先 Read 确认当前行号）
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadTrackServiceTest.java`

**背景**：`fail()` 目前只有两个调用点（找不到种子、僵尸超时），恰好对应 `FailReasonCode.TORRENT_NOT_FOUND`/`ZOMBIE_TIMEOUT` 两个分类。`fail()` 签名扩展为 `fail(record, code, reason)`，`reason` 文本不变（分类给程序/UI 用，文本给人读，两者并存）。

- [ ] **步骤 1：编写失败的测试——在已有断言基础上补充分类断言**

修改测试 `找不到种子且推送已超宽限期_记录置失败且集回退缺失`（任务 4 之前的原文件第 124-139 行，内容未被任务 4 改动）：

```java
    @Test
    void 找不到种子且推送已超宽限期_记录置失败且集回退缺失() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        // 宽限期 10 分钟，这条推送了 20 分钟还找不到
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("FAILED", captor.getValue().getState());
        assertEquals("TORRENT_NOT_FOUND", captor.getValue().getFailReasonCode());
        // 关联集回退 MISSING
        verify(episodeService).update(any(), any(Wrapper.class));
    }
```

修改测试 `种子仍在下载器但超僵尸超时_判失败并回退集`（原文件第 186-199 行）：

```java
    @Test
    void 种子仍在下载器但超僵尸超时_判失败并回退集() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 25L * 3600_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));

        service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.5)));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).update(captor.capture(), any(Wrapper.class));
        assertEquals("FAILED", captor.getValue().getState());
        assertEquals("ZOMBIE_TIMEOUT", captor.getValue().getFailReasonCode());
        verify(episodeService).update(any(), any(Wrapper.class));
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=DownloadTrackServiceTest`
预期：这两个用例 FAIL，报错 `expected: <TORRENT_NOT_FOUND> but was: <null>`（`fail_reason_code` 还没有被写入，因为生产代码还没加分类）

- [ ] **步骤 3：编写最少实现代码**

在 `track()` 方法内（任务 4 落地后的版本）把两处 `fail(record, "...")` 调用改为带分类参数：

```java
            } else if (matched == null) {
                if (age >= GRACE_MILLIS) {
                    fail(record, FailReasonCode.TORRENT_NOT_FOUND, "下载器中已找不到该种子（可能被删除或元数据解析失败）");
                }
                // 未超宽限期：qB 可能还在解析元数据，本轮跳过
            } else {
                // 种子还在下载器但未完成
                if (age >= zombieTimeoutMillis) {
                    fail(record, FailReasonCode.ZOMBIE_TIMEOUT,
                            "下载超过 " + (zombieTimeoutMillis / 3600000) + " 小时仍未完成，判定为僵尸种子");
                } else {
                    markDownloading(record, matched.getProgress());
                }
            }
```

`fail()` 方法整体替换为（新增 `FailReasonCode code` 参数，方法体新增 `set.setFailReasonCode(code.value());`）：

```java
    /**
     * 判记录失败并回退其关联集。反转写序（先集、后记录）保证崩溃安全：
     * 无论崩在哪一步，记录仍处于 PUSHED/DOWNLOADING，会被下一轮重新处理，
     * 不会产生「记录已 FAILED 但集仍 IN_FLIGHT」的永久孤儿。
     * <p>
     * 每个关联集各自累加连续失败次数：达到阈值前回退 MISSING（RSS/补搜会重新捡回），
     * 达到阈值后转 BLOCKED 停止自动重试，避免已下架/失效的资源被无限次静默重试。
     * </p>
     */
    private void fail(PtDownloadRecordPlus record, FailReasonCode code, String reason) {
        // 1) 先回退关联集（幂等：只动 IN_FLIGHT 的；普通集1条、季包多条统一处理）
        List<PtSubscriptionEpisodePlus> episodes = episodeService.list(
                new QueryWrapper<PtSubscriptionEpisodePlus>()
                        .eq("download_id", record.getId())
                        .eq("state", EP_IN_FLIGHT));
        int blockedCount = 0;
        for (PtSubscriptionEpisodePlus episode : episodes) {
            int fails = (episode.getFailCount() == null ? 0 : episode.getFailCount()) + 1;
            boolean blocked = fails >= maxConsecutiveFailures;
            PtSubscriptionEpisodePlus set = new PtSubscriptionEpisodePlus();
            set.setState(blocked ? EP_BLOCKED : EP_MISSING);
            set.setDownloadId(null);
            set.setFailCount(fails);
            episodeService.update(set, new UpdateWrapper<PtSubscriptionEpisodePlus>()
                    .eq("id", episode.getId())
                    .eq("state", EP_IN_FLIGHT));
            if (blocked) {
                blockedCount++;
            }
        }
        // 2) 再置记录 FAILED（条件更新门控通知，避免重叠轮询重复发）
        PtDownloadRecordPlus set = new PtDownloadRecordPlus();
        set.setState(STATE_FAILED);
        set.setFailReason(reason);
        set.setFailReasonCode(code.value());
        boolean changed = recordService.update(set, new UpdateWrapper<PtDownloadRecordPlus>()
                .eq("id", record.getId())
                .in("state", STATE_PUSHED, STATE_DOWNLOADING));
        if (!changed) {
            return; // 已被并发轮次置为终态，避免重复通知
        }
        notifySafely("❌ 下载失败：" + record.getTitle() + "，已释放待下轮重新匹配");
        log.warn("下载记录[{}] 失败，{} 个集回退缺失：{}", record.getId(), episodes.size(), record.getTitle());
        if (blockedCount > 0) {
            notifySafely("🚫 " + record.getTitle() + " 连续失败达 " + maxConsecutiveFailures
                    + " 次，已停止自动重试，需到下载记录管理页人工重试");
        }
    }
```

（`FailReasonCode` 与 `DownloadTrackService` 同在 `com.ruoyi.openliststrm.pt.task` 包下，无需新增 import）

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=DownloadTrackServiceTest`
预期：`Tests run: 19, Failures: 0, Errors: 0`

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadTrackService.java \
        ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadTrackServiceTest.java
git commit -m "feat: 下载失败原因结构化分类落库"
```

---

### 任务 6：`DownloadRecordView`/`DownloadRecordAdminService` — 透传失败原因分类

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/dto/DownloadRecordView.java:34`
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadRecordAdminService.java:87-107`
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadRecordAdminServiceTest.java`

**背景**：`GET /api/openliststrm/pt-download-records` 通过 `DownloadRecordAdminService.enrich()` → `toView()` 把 `PtDownloadRecordPlus` 转成 `DownloadRecordView` 返回给前端，这里只需新增字段透传，不涉及业务逻辑，Controller 不用改。

- [ ] **步骤 1：编写失败的测试**

在 `DownloadRecordAdminServiceTest.java` 的 `// ---------- enrich ----------` 分组内（原文件第 84-106 行之后）新增：

```java
    @Test
    void enrich_透传失败原因分类字段() {
        PtDownloadRecordPlus r = record(1, 10, 5, "FAILED", 20, 30);
        r.setFailReasonCode("ZOMBIE_TIMEOUT");
        when(subscriptionService.listByIds(List.of(10))).thenReturn(List.of(tvSub(10, "某剧", 1, "ACTIVE")));
        when(indexerService.listByIds(List.of(20))).thenReturn(List.of());
        when(downloaderService.listByIds(List.of(30))).thenReturn(List.of());

        var result = service().enrich(PageResult.of(List.of(r), 1, 1, 10));

        assertEquals("ZOMBIE_TIMEOUT", result.getRecords().get(0).getFailReasonCode());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=DownloadRecordAdminServiceTest`
预期：编译失败，报错 `DownloadRecordView` 没有 `getFailReasonCode()` 方法（或 `PtDownloadRecordPlus` 没有 `setFailReasonCode` —— 若任务 2 已完成则后者已存在，只会报前者缺失）

- [ ] **步骤 3：编写最少实现代码**

`DownloadRecordView.java` 在 `private String failReason;`（原文件第 34 行）之后新增：

```java
    private String failReason;
    private String failReasonCode;
    private Date pushedTime;
```

`DownloadRecordAdminService.toView()`（原文件第 87-107 行）在 `view.setFailReason(r.getFailReason());`（原第 103 行）之后新增一行：

```java
        view.setState(r.getState());
        view.setProgress(r.getProgress());
        view.setFailReason(r.getFailReason());
        view.setFailReasonCode(r.getFailReasonCode());
        view.setPushedTime(r.getPushedTime());
        view.setCompletedTime(r.getCompletedTime());
        return view;
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=DownloadRecordAdminServiceTest`
预期：`Tests run: 15, Failures: 0, Errors: 0`（14 个原有用例 + 本任务新增 1 个）

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/dto/DownloadRecordView.java \
        ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadRecordAdminService.java \
        ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadRecordAdminServiceTest.java
git commit -m "feat: 下载记录展示视图透传失败原因分类字段"
```

---

### 任务 7：`SubscriptionEngine` — 批内缓存下载器列表与负载统计 + 负载均衡选择器

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java`（全文，见下方对照）
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngineTest.java`

**背景**：`process()` 一次批量处理可能匹配出多个 `(订阅, 集号)` 分组，每组都会调用 `handleGroup` → 原来的 `resolveDownloader`，而 `resolveDownloader` 内部每次都查一次启用下载器表。本任务把 `enabledDownloaders`（启用下载器列表）与 `downloaderLoadCache`（下载器 id → 当前 PUSHED/DOWNLOADING 记录数）提到循环外各查一次，随现有的 `episodeCache` 一起传给 `handleGroup`；`resolveDownloader` 同时从"永远选第一个启用的"改为"负载最少优先，订阅显式指定下载器时不变"。推送成功后 `downloaderLoadCache.merge(...)` 就地 `+1`，让同一批次内的后续分组能感知到本次推送。

- [ ] **步骤 1：编写失败的测试——新增下载器负载相关用例**

在 `SubscriptionEngineTest.java` 顶部导入区新增一个静态导入：

```java
import static org.mockito.ArgumentMatchers.argThat;
```

（放在 `import static org.mockito.ArgumentMatchers.eq;` 之后）

在 `// ---------- 下载器 ----------` 分组内（原文件第 306-316 行，`无启用的下载器_不推送并返回0` 测试之后）新增以下 4 个测试：

```java
    @Test
    void 两个启用下载器_未指定_选负载最小的推送() throws Exception {
        PtDownloaderPlus d1 = new PtDownloaderPlus();
        d1.setId(1); d1.setType("QBITTORRENT"); d1.setSavePath("/data/downloads"); d1.setTag("osr-pt"); d1.setEnabled("1");
        PtDownloaderPlus d2 = new PtDownloaderPlus();
        d2.setId(2); d2.setType("QBITTORRENT"); d2.setSavePath("/data/downloads2"); d2.setTag("osr-pt2"); d2.setEnabled("1");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(d1, d2));
        // 区分两类 recordService.list 查询：guid_hash 查询（excludeAlreadyRecorded）与
        // downloader_id/state 查询（loadDownloaderLoadCounts）用同一个方法签名，按 SQL 片段里的目标字段区分桩数据
        when(recordService.list(argThat(w -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("guid_hash"))))
                .thenReturn(List.of());
        PtDownloadRecordPlus loadForD1a = new PtDownloadRecordPlus();
        loadForD1a.setDownloaderId(1);
        PtDownloadRecordPlus loadForD1b = new PtDownloadRecordPlus();
        loadForD1b.setDownloaderId(1);
        when(recordService.list(argThat(w -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(List.of(loadForD1a, loadForD1b)); // d1 在途记录数=2，d2=0
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(2, captor.getValue().getDownloaderId());
    }

    @Test
    void 订阅指定下载器_即使负载更高_仍选择指定的() throws Exception {
        PtDownloaderPlus d1 = new PtDownloaderPlus();
        d1.setId(1); d1.setType("QBITTORRENT"); d1.setSavePath("/data/downloads"); d1.setTag("osr-pt"); d1.setEnabled("1");
        PtDownloaderPlus d2 = new PtDownloaderPlus();
        d2.setId(2); d2.setType("QBITTORRENT"); d2.setSavePath("/data/downloads2"); d2.setTag("osr-pt2"); d2.setEnabled("1");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(d1, d2));
        when(recordService.list(argThat(w -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("guid_hash"))))
                .thenReturn(List.of());
        PtDownloadRecordPlus loadForD1a = new PtDownloadRecordPlus();
        loadForD1a.setDownloaderId(1);
        PtDownloadRecordPlus loadForD1b = new PtDownloadRecordPlus();
        loadForD1b.setDownloaderId(1);
        when(recordService.list(argThat(w -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(List.of(loadForD1a, loadForD1b)); // d1 负载更高：2 对 0
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        sub.setDownloaderId(1);
        when(subscriptionService.listActive()).thenReturn(List.of(sub));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(1, captor.getValue().getDownloaderId());
    }

    @Test
    void 指定下载器已禁用_回退到负载最低的启用下载器() throws Exception {
        PtDownloaderPlus d1 = new PtDownloaderPlus();
        d1.setId(1); d1.setType("QBITTORRENT"); d1.setSavePath("/data/downloads"); d1.setTag("osr-pt"); d1.setEnabled("1");
        PtDownloaderPlus d2 = new PtDownloaderPlus();
        d2.setId(2); d2.setType("QBITTORRENT"); d2.setSavePath("/data/downloads2"); d2.setTag("osr-pt2"); d2.setEnabled("1");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(d1, d2));
        when(recordService.list(argThat(w -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("guid_hash"))))
                .thenReturn(List.of());
        PtDownloadRecordPlus loadForD1 = new PtDownloadRecordPlus();
        loadForD1.setDownloaderId(1);
        when(recordService.list(argThat(w -> w != null && w.getSqlSegment() != null && w.getSqlSegment().contains("downloader_id"))))
                .thenReturn(List.of(loadForD1)); // d1 负载=1，d2 负载=0
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        sub.setDownloaderId(99); // 不在启用列表里
        when(subscriptionService.listActive()).thenReturn(List.of(sub));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService).save(captor.capture());
        assertEquals(2, captor.getValue().getDownloaderId());
    }

    @Test
    void 同一批次连续命中_第二次感知前一次推送的负载增量() throws Exception {
        PtDownloaderPlus d1 = new PtDownloaderPlus();
        d1.setId(1); d1.setType("QBITTORRENT"); d1.setSavePath("/data/downloads"); d1.setTag("osr-pt"); d1.setEnabled("1");
        PtDownloaderPlus d2 = new PtDownloaderPlus();
        d2.setId(2); d2.setType("QBITTORRENT"); d2.setSavePath("/data/downloads2"); d2.setTag("osr-pt2"); d2.setEnabled("1");
        when(downloaderService.list(any(Wrapper.class))).thenReturn(List.of(d1, d2));
        // 两个下载器初始负载都是 0（沿用 setUp() 的默认空列表桩）
        when(subscriptionService.listActive()).thenReturn(List.of(
                tvSub(10, "Show A", 1, 1), tvSub(20, "Show B", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        when(episodeService.listBySubscription(20)).thenReturn(List.of(episode(201, 1, "MISSING")));

        engine.process(List.of(
                torrent("Show.A.S01E01.1080p", "gA", 10, "1080p"),
                torrent("Show.B.S01E01.1080p", "gB", 10, "1080p")));

        ArgumentCaptor<PtDownloadRecordPlus> captor = ArgumentCaptor.forClass(PtDownloadRecordPlus.class);
        verify(recordService, times(2)).save(captor.capture());
        List<PtDownloadRecordPlus> saved = captor.getAllValues();
        // 两个下载器初始负载相等，按顺序 tie-break 选第一个(d1)；
        // 第二次推送前 d1 已被 +1，第二组应该感知到这个变化转而选 d2
        assertEquals(1, saved.get(0).getDownloaderId());
        assertEquals(2, saved.get(1).getDownloaderId());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=SubscriptionEngineTest`
预期：新增的 4 个用例 FAIL（`expected: <2> but was: <1>` 或类似——因为生产代码 `resolveDownloader` 还是"永远选第一个"，没有负载均衡逻辑）

- [ ] **步骤 3：编写最少实现代码**

导入区（原文件第 1-34 行）新增一行 `import java.util.HashMap;`（插入到 `import java.util.Date;` 与 `import java.util.HashSet;` 之间，按字母序 HashMap < HashSet）：

```java
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
```

在常量区（原文件第 49-51 行）新增一个常量：

```java
    private static final String STATE_MISSING = SubscriptionEpisodeState.MISSING.value();
    private static final String STATE_IN_FLIGHT = SubscriptionEpisodeState.IN_FLIGHT.value();
    private static final String RECORD_PUSHED = DownloadRecordState.PUSHED.value();
    private static final String RECORD_DOWNLOADING = DownloadRecordState.DOWNLOADING.value();
```

`process()` 方法（原文件第 98-129 行）整体替换为：

```java
    public int process(List<TorrentInfo> torrents) {
        List<PtSubscriptionPlus> subscriptions = subscriptionService.listActive();
        if (subscriptions.isEmpty() || torrents.isEmpty()) {
            return 0;
        }
        PtFilterConfigPlus globalConfig = filterConfigService.getConfig();

        // 按 (订阅id, 集号) 分组；集号 -1 表示季包
        Map<String, List<TorrentInfo>> groups = new LinkedHashMap<>();
        Map<String, MatchResult> groupMatch = new LinkedHashMap<>();
        for (TorrentInfo torrent : torrents) {
            fillParsed(torrent);
            MatchResult match = matcher.match(torrent, subscriptions);
            if (match == null) {
                log.debug("种子未匹配到任何订阅：{}", torrent.getTitle());
                continue;
            }
            String key = match.getSubscription().getId() + "#" + match.getEpisode();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(torrent);
            groupMatch.putIfAbsent(key, match);
        }

        int pushed = 0;
        Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache = new LinkedHashMap<>();
        List<PtDownloaderPlus> enabledDownloaders = loadEnabledDownloaders();
        Map<Integer, Long> downloaderLoadCache = loadDownloaderLoadCounts(enabledDownloaders);
        for (Map.Entry<String, List<TorrentInfo>> entry : groups.entrySet()) {
            MatchResult match = groupMatch.get(entry.getKey());
            if (handleGroup(match, entry.getValue(), globalConfig, episodeCache,
                    enabledDownloaders, downloaderLoadCache, SearchLogService.SOURCE_RSS)) {
                pushed++;
            }
        }
        return pushed;
    }
```

`pushBest()` 方法（原文件第 137-142 行）整体替换为：

```java
    public boolean pushBest(PtSubscriptionPlus sub, int episode, List<TorrentInfo> candidates) {
        PtFilterConfigPlus globalConfig = filterConfigService.getConfig();
        MatchResult match = new MatchResult(sub, episode);
        Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache = new LinkedHashMap<>();
        List<PtDownloaderPlus> enabledDownloaders = loadEnabledDownloaders();
        Map<Integer, Long> downloaderLoadCache = loadDownloaderLoadCounts(enabledDownloaders);
        return handleGroup(match, candidates, globalConfig, episodeCache,
                enabledDownloaders, downloaderLoadCache, SearchLogService.SOURCE_SUPPLEMENT);
    }
```

`handleGroup()` 方法（原文件第 144-232 行）整体替换为：

```java
    /**
     * @return 是否成功推送了一个种子
     */
    boolean handleGroup(MatchResult match, List<TorrentInfo> candidates,
                                PtFilterConfigPlus globalConfig,
                                Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache,
                                List<PtDownloaderPlus> enabledDownloaders,
                                Map<Integer, Long> downloaderLoadCache,
                                String source) {
        PtSubscriptionPlus sub = match.getSubscription();
        List<PtSubscriptionEpisodePlus> allEpisodes = episodeCache.computeIfAbsent(
                sub.getId(), episodeService::listBySubscription);

        List<PtSubscriptionEpisodePlus> targets = resolveTargets(match, allEpisodes);
        if (targets.isEmpty()) {
            log.debug("订阅[{}] 集{} 无可占位的缺失集，跳过", sub.getId(), match.getEpisode());
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, "无可占位的缺失集（可能已入库或在途）");
            return false;
        }

        List<TorrentInfo> fresh = excludeAlreadyRecorded(candidates);
        if (fresh.isEmpty()) {
            log.debug("订阅[{}] 集{} 的候选都已有下载记录，跳过", sub.getId(), match.getEpisode());
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, "候选种子都已推送过，本轮跳过");
            return false;
        }

        FilterCriteria criteria = FilterCriteriaFactory.build(globalConfig, sub.getFilterOverride());
        List<TorrentFilterEngine.Verdict> verdicts = filterEngine.evaluate(fresh, criteria);
        searchLogService.recordVerdicts(sub.getId(), match.getEpisode(), source, verdicts);
        List<TorrentInfo> survivors = verdicts.stream()
                .filter(TorrentFilterEngine.Verdict::accepted)
                .map(TorrentFilterEngine.Verdict::torrent)
                .toList();
        TorrentInfo best = filterEngine.pickBest(survivors, criteria);
        if (best == null) {
            return false;
        }

        PtDownloaderPlus downloader = resolveDownloader(sub, enabledDownloaders, downloaderLoadCache);
        if (downloader == null) {
            log.warn("没有可用的下载器，订阅[{}] 本轮跳过", sub.getId());
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source, "没有可用的下载器");
            return false;
        }

        // 原子占位：条件更新按影响行数判断，防止并发轮询给同一集推两个种子
        List<PtSubscriptionEpisodePlus> claimed = new ArrayList<>();
        for (PtSubscriptionEpisodePlus target : targets) {
            if (claim(target)) {
                claimed.add(target);
            }
        }
        if (claimed.isEmpty()) {
            log.debug("订阅[{}] 集{} 已被并发轮询占位，跳过", sub.getId(), match.getEpisode());
            return false;
        }

        String guidHash = GuidHasher.hash(best.getGuid());
        PtDownloadRecordPlus record = buildRecord(sub, match.getEpisode(), best, guidHash, downloader);
        if (!recordService.save(record)) {
            releaseAll(claimed);
            return false;
        }

        try {
            String tags = downloader.getTag() + "," + record.getTrackingTag();
            downloaderClientFactory.get(downloader)
                    .addTorrent(downloader, best.getDownloadUrl(), downloader.getSavePath(), tags);
            // 就地自增：让同一批次内后续分组也能感知到这次推送，避免全部涌向"批次开始时最闲"的下载器
            downloaderLoadCache.merge(downloader.getId(), 1L, Long::sum);
        } catch (Exception e) {
            log.error("推送种子到下载器失败，已回滚：{}", best.getTitle(), e);
            searchLogService.recordSummary(sub.getId(), match.getEpisode(), source,
                    "推送到下载器失败：" + e.getMessage());
            recordService.removeById(record.getId());
            releaseAll(claimed);
            return false;
        }

        for (PtSubscriptionEpisodePlus ep : claimed) {
            ep.setDownloadId(record.getId());
            ep.setState(STATE_IN_FLIGHT);
        }
        episodeService.updateBatchById(claimed);

        sub.setLastMatchTime(new Date());
        subscriptionService.updateById(sub);

        log.info("订阅[{}] {} 已推送种子：{}（占位 {} 集）",
                sub.getId(), sub.getTitle(), best.getTitle(), claimed.size());
        return true;
    }
```

`resolveDownloader()` 方法（原文件第 352-368 行）整体替换为，并在其上方新增 `loadEnabledDownloaders()`/`loadDownloaderLoadCounts()` 两个私有方法：

```java
    /** 查询当前启用的下载器列表，供批内缓存复用 */
    private List<PtDownloaderPlus> loadEnabledDownloaders() {
        return downloaderService.list(new QueryWrapper<PtDownloaderPlus>().eq("enabled", "1"));
    }

    /** 统计每个启用下载器当前 PUSHED/DOWNLOADING 的在途记录数，供负载均衡使用 */
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

    /**
     * 订阅指定了下载器且该下载器仍启用就用它（不变，用户显式选择优先级最高）；
     * 否则从启用列表里选当前在途记录数最少的一个，并列时选列表里靠前的（顺序即数据库查询顺序，天然稳定）。
     */
    private PtDownloaderPlus resolveDownloader(PtSubscriptionPlus sub,
                                                List<PtDownloaderPlus> enabled,
                                                Map<Integer, Long> loadCache) {
        if (enabled.isEmpty()) {
            return null;
        }
        if (sub.getDownloaderId() != null) {
            for (PtDownloaderPlus downloader : enabled) {
                if (sub.getDownloaderId().equals(downloader.getId())) {
                    return downloader;
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

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=SubscriptionEngineTest`
预期：`Tests run: 30, Failures: 0, Errors: 0`（26 个原有用例 + 本任务新增 4 个）。原有用例默认桩 `recordService.list(any(Wrapper.class))` 返回空列表会被 `loadDownloaderLoadCounts` 复用，单下载器场景选择结果不变，不需要改动断言。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java \
        ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngineTest.java
git commit -m "feat: 批内缓存下载器列表与负载统计，resolveDownloader 改为负载均衡选择"
```

---

### 任务 8：前端 PC 端 — 下载记录页展示失败分类标签

**文件：**
- 修改：`openlist-web/src/views/openlist/ptDownloadRecord/index.vue:73-76,128-135`
- 测试：`openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`

**背景**：现有 `record-fail` 展示块只有一段自由文本 `item.failReason`。本任务在其前面加一个只读分类标签，`item.failReasonCode` 为空时不渲染（兼容历史数据）。`stateLabel`/`stateTagType` 已经是"就地写在 `<script setup>` 里"的映射写法，`failReasonCodeLabel`/`failReasonTagType` 照抄同样的写法，不抽公共 util。

- [ ] **步骤 1：编写失败的测试——扩展已有的 `index.spec.ts`**

现有文件只有一个"dump html"的冒烟测试，本步骤新增两个真正带断言的测试用例（追加在文件末尾 `describe('prototype', ...)` 之后）：

```typescript
describe('failReasonCode 标签', () => {
  it('有 failReasonCode 时渲染对应的分类标签文案', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom', failReasonCode: 'ZOMBIE_TIMEOUT' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.record-fail el-tag').exists()).toBe(true)
    expect(wrapper.text()).toContain('下载超时')
  })

  it('没有 failReasonCode 时不渲染分类标签（兼容历史数据）', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.record-fail el-tag').exists()).toBe(false)
  })
})
```

（这段追加在原文件第 28-37 行的 `describe('prototype', ...)` 块之后）

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`
预期：第一个新用例 FAIL（`expected false to be true`，因为模板里还没有分类标签元素）；第二个新用例 PASS（因为本来就没有标签，这是巧合通过，步骤 3 落地后仍应保持 PASS）

- [ ] **步骤 3：编写最少实现代码**

模板里的 `record-fail` 块（原文件第 73-76 行）：

```html
          <div class="record-fail" v-if="item.state === 'FAILED'">
            <el-icon><WarningFilled /></el-icon>
            <el-tag v-if="item.failReasonCode" size="small" :type="failReasonTagType(item.failReasonCode)">
              {{ failReasonCodeLabel(item.failReasonCode) }}
            </el-tag>
            <span>{{ item.failReason || '未知原因' }}</span>
          </div>
```

`<script setup>` 里在 `stateTagType`（原文件第 128-135 行）之后、`formatSize` 之前新增两个映射函数：

```typescript
const stateTagType = (state: string): 'success' | 'warning' | 'danger' | 'info' => {
  switch (state) {
    case 'COMPLETED': return 'success'
    case 'DOWNLOADING': return 'warning'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

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

const formatSize = (bytes: number): string => {
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`
预期：`Test Files 1 passed, Tests 3 passed`

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views/openlist/ptDownloadRecord/index.vue \
        openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts
git commit -m "feat: PC端下载记录页展示失败原因分类标签"
```

---

### 任务 9：前端移动端 — 下载记录页展示失败分类标签

**文件：**
- 修改：`openlist-web/src/views-mobile/ptDownloadRecord/index.vue:57-60,109-116`

**背景**：移动端页面结构与 PC 端几乎一致（`card-fail` 对应 `record-fail`），改法完全对称。仓库里 `views-mobile/` 目录目前没有任何 `__tests__` 目录/`.spec.ts` 文件（对比任务 8 已确认），这是既有约定——移动端页面不单独写 Vitest 组件测试，只靠 `npm run build` 的 `vue-tsc` 类型检查与人工验证兜底，本任务遵循这一约定不新增测试基础设施。

- [ ] **步骤 1：修改模板**

`card-fail` 块（原文件第 57-60 行）：

```html
        <div class="card-fail" v-if="item.state === 'FAILED'">
            <el-icon><WarningFilled /></el-icon>
            <el-tag v-if="item.failReasonCode" size="small" :type="failReasonTagType(item.failReasonCode)">
              {{ failReasonCodeLabel(item.failReasonCode) }}
            </el-tag>
            <span>{{ item.failReason || '未知原因' }}</span>
          </div>
```

- [ ] **步骤 2：修改 `<script setup>`**

在 `stateTagType`（原文件第 109-116 行）之后、`formatSize` 之前新增：

```typescript
const stateTagType = (state: string): 'success' | 'warning' | 'danger' | 'info' => {
  switch (state) {
    case 'COMPLETED': return 'success'
    case 'DOWNLOADING': return 'warning'
    case 'FAILED': return 'danger'
    default: return 'info'
  }
}

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

const formatSize = (bytes: number): string => {
```

- [ ] **步骤 3：类型检查验证**

运行：`cd openlist-web && npx vue-tsc --noEmit`
预期：无类型错误输出（`item` 是 `any[]`，模板里 `item.failReasonCode` 不受类型检查约束，本步骤主要验证 `<script setup>` 里两个新函数本身没有类型错误）

- [ ] **步骤 4：Commit**

```bash
git add openlist-web/src/views-mobile/ptDownloadRecord/index.vue
git commit -m "feat: 移动端下载记录页展示失败原因分类标签"
```

---

### 任务 10：全量验证与启动校验

**文件：** 无新增/修改，纯验证任务。

**背景**：本次改动涉及 `DownloadTrackService` 的构造器签名变化（新增 `IPtSubscriptionPlusService` 依赖），属于 bean 装配变化，按 `AGENTS.md` 要求必须做启动验证——单元测试用构造器直接 `new` 目标类会绕过 Spring 装配，"测试全绿"不代表"能启动"。同时两张表新增了列，需要确认 `MysqlDdl` 迁移在真实/测试数据库上执行成功。

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

- [ ] **步骤 5：Docker 启动验证（后端 bean 装配 + 数据库迁移）**

运行：`docker compose up -d --build --no-deps backend`

等待约 30 秒后运行：`docker ps --filter "name=osr-backend" --format "{{.Names}}\t{{.Status}}"`
预期：状态里 `restarts=0`（若容器不断重启说明 Spring 启动失败，需按 `AGENTS.md` 的排查步骤 `docker cp osr-backend:/data/logs ./tmp` 后看 `sys-error.log`）

- [ ] **步骤 6：确认数据库迁移生效**

运行（需要能连上 MySQL 容器，替换为实际的数据库连接方式，如 `docker exec -it <mysql容器名> mysql -uroot -p osr`）：

```sql
SHOW COLUMNS FROM pt_download_record LIKE 'fail_reason_code';
SHOW COLUMNS FROM pt_subscription LIKE 'download_override';
```

预期：两条 `SHOW COLUMNS` 各自返回一行，确认列已创建

- [ ] **步骤 7：确认接口能正常响应**

运行（替换为实际网关地址与已登录 token，或直接在浏览器里访问前端页面确认下载记录页正常加载）：

```bash
curl -s "http://localhost:6895/api/openliststrm/pt-download-records" -H "Authorization: Bearer <token>"
```

预期：返回 `{"code":200,...}` 且 `data.records` 里每条记录包含 `failReasonCode` 字段（值为 `null` 或具体分类均可）

- [ ] **步骤 8（可选）：不提交代码，仅记录验证结果**

本任务是纯验证，没有代码可提交；若步骤 1-7 全部通过，实现工作即告完成，可进入 `finishing-a-development-branch` 技能收尾（合并/PR/清理）。
