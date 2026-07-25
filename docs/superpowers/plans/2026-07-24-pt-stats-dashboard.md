# PT 订阅统计仪表盘 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 新增一个只读的 PT 统计仪表盘页面，把 `pt_download_record`/`pt_search_log`/`pt_subscription`/`pt_indexer` 四张已有表聚合成 5 个维度（下载量趋势、索引器命中率、平均耗时、失败原因分布、Top 活跃订阅），后端 5 个只读接口 + 前端 1 个独立页面。

**架构：** 后端新增 `PtStatsService`（MyBatis-Plus `QueryWrapper` 原生 `select`/`groupBy` 聚合，`IService.listMaps`/`count`/`listByIds` 完成分组统计，不新建表不新建 XML Mapper）+ `PtStatsRestController`（5 个 `GET` 端点，`days`/`limit` 参数白名单校验）；前端新增独立页面 `views/openlist/ptStatsDashboard/index.vue`（`el-card` + ECharts line/bar/pie + `el-table`），挂在"PT下载管理"菜单分组下，不复用现有 Dashboard 首页、不新增 composable。

**技术栈：** Spring Boot + MyBatis-Plus（`QueryWrapper` + `IService`）、JUnit5 + Mockito（`@ExtendWith(MockitoExtension.class)`）、Vue 3 `<script setup>` + Element Plus + ECharts（`echarts/core` 按需引入）、MySQL DDL 迁移（`MysqlDdl.getSqlFiles()`）。

**设计文档：** [docs/superpowers/specs/2026-07-24-pt-stats-dashboard-design.md](../specs/2026-07-24-pt-stats-dashboard-design.md) —— 完整的数据来源表、口径推导（索引器命中率为何不做时间筛选、API 为何拆分成 5 个端点等）都在这份文档里，本计划直接落地，不重复推导。

---

## 文件结构

**后端新增：**
- `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/PtStatsOverviewDTO.java`
- `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/PtStatsTrendPointDTO.java`
- `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/PtStatsIndexerHitRateDTO.java`
- `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/PtStatsFailReasonDTO.java`
- `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/PtStatsActiveSubscriptionDTO.java`
- `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java`
- `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtStatsRestController.java`
- `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java`
- `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/controller/api/PtStatsRestControllerTest.java`
- `ruoyi-common/src/main/resources/sql/20260738-pt-stats-menu.sql`

**后端修改：**
- `ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java:61-63`（注册新迁移文件）

**前端新增：**
- `openlist-web/src/api/openlist/ptStats.ts`
- `openlist-web/src/views/openlist/ptStatsDashboard/index.vue`

**前端修改：**
- `openlist-web/src/composables/useMenuIcon.ts:1-5,37`（新增 `fa fa-bar-chart` 图标映射）
- `openlist-web/src/router/index.ts:108-113`（新增 `componentMap` 条目，不用 `createDeviceView`）

---

### 任务 1：后端 DTO 类

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/PtStatsOverviewDTO.java`
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/PtStatsTrendPointDTO.java`
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/PtStatsIndexerHitRateDTO.java`
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/PtStatsFailReasonDTO.java`
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/PtStatsActiveSubscriptionDTO.java`

这 5 个类都是纯数据持有对象（跟 `pt/task/dto/DownloadRecordView.java` 同类风格：`@Data` + 字段，无行为），没有可测的逻辑，本任务不写单元测试，用编译通过作为验证。

- [ ] **步骤 1：创建 `PtStatsOverviewDTO.java`**

```java
package com.ruoyi.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * PT 统计总览：仪表盘顶部 5 张统计卡片的数据来源，一次查询覆盖，不做时间范围筛选。
 *
 * @author Jack
 */
@Data
public class PtStatsOverviewDTO {

    /** 总订阅数 */
    private long totalSubscriptions;

    /** 活跃订阅数（status=ACTIVE） */
    private long activeSubscriptions;

    /** 下载记录总数（不限状态） */
    private long totalDownloadRecords;

    /** 完成数（state=COMPLETED） */
    private long completedCount;

    /** 失败数（state=FAILED） */
    private long failedCount;

    /** 成功率，百分比数值(0~100)，保留1位小数；总数为0时记0，不做除零 */
    private double successRate;

    /** 全局平均下载耗时(分钟)，基于 COMPLETED 记录的 pushed_time~completed_time；无 COMPLETED 记录时记0 */
    private double avgDurationMinutes;
}
```

- [ ] **步骤 2：创建 `PtStatsTrendPointDTO.java`**

```java
package com.ruoyi.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * 下载量趋势的单日数据点：按 pushed_time 所在日期分组，日期连续补齐（缺失日期记0），
 * 前端折线图需要连续的日期轴，缺口交给 avgDurationMinutes=null 由前端跳过该点。
 *
 * @author Jack
 */
@Data
public class PtStatsTrendPointDTO {

    /** 日期，格式 yyyy-MM-dd */
    private String date;

    /** 当日推送数(该日 pushed_time 落在当天的记录数，不限最终状态) */
    private long pushedCount;

    /** 当日推送且已完成的数量 */
    private long completedCount;

    /** 当日推送且已失败的数量 */
    private long failedCount;

    /** 当日完成记录的平均耗时(分钟)；当日无完成记录时为 null，前端据此跳过该点不画线段 */
    private Double avgDurationMinutes;
}
```

- [ ] **步骤 3：创建 `PtStatsIndexerHitRateDTO.java`**

```java
package com.ruoyi.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * 索引器命中率：驱动集合是 pt_indexer 全量（不是只有产生过日志的），
 * 从未在 pt_search_log 里出现过的索引器 hasData=false，不参与图表比例计算。
 *
 * @author Jack
 */
@Data
public class PtStatsIndexerHitRateDTO {

    private Integer indexerId;

    private String indexerName;

    /** 通过过滤的候选数 */
    private long acceptedCount;

    /** 被淘汰的候选数 */
    private long rejectedCount;

    /** 命中率，0~1；分母(accepted+rejected)为0时记0 */
    private double hitRate;

    /** 该索引器是否在 pt_search_log 中出现过匹配记录 */
    private boolean hasData;
}
```

- [ ] **步骤 4：创建 `PtStatsFailReasonDTO.java`**

```java
package com.ruoyi.openliststrm.pt.stats.dto;

import lombok.Data;

/**
 * 失败原因分布：reason 就是 pt_download_record.fail_reason 原始字符串，
 * 本设计口径下只有两种固定文案，不做归一化/正则分桶。
 *
 * @author Jack
 */
@Data
public class PtStatsFailReasonDTO {

    private String reason;

    private long count;
}
```

- [ ] **步骤 5：创建 `PtStatsActiveSubscriptionDTO.java`**

```java
package com.ruoyi.openliststrm.pt.stats.dto;

import lombok.Data;

import java.util.Date;

/**
 * Top 活跃订阅：按下载记录数分组的订阅排行。订阅已被删除但历史下载记录还在时，
 * title 兜底显示"（订阅已删除）"，season/mediaType/lastMatchTime 为 null，不抛异常。
 *
 * @author Jack
 */
@Data
public class PtStatsActiveSubscriptionDTO {

    private Integer subId;

    /** 订阅已被删除时显示"（订阅已删除）" */
    private String title;

    /** 订阅已被删除时为 null */
    private Integer season;

    /** 订阅已被删除时为 null，取值 TV/MOVIE */
    private String mediaType;

    private long downloadCount;

    private long completedCount;

    private long failedCount;

    /** 订阅已被删除时为 null */
    private Date lastMatchTime;
}
```

- [ ] **步骤 6：编译验证**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am compile -q`
预期：无报错（`-am` 会先编译 `ruoyi-common`/`ruoyi-system`/`ruoyi-framework` 等被依赖模块）。

- [ ] **步骤 7：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/dto/
git commit -m "feat: 新增PT统计仪表盘的5个DTO类"
```

---

### 任务 2：`PtStatsService.overview()`（TDD）

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java`
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java`

`PtStatsService` 纯构造器注入 4 个 `*PlusService`，本任务先落地类骨架 + `overview()` 方法；`trend()`/`indexerHitRate()`/`failReasons()`/`topSubscriptions()` 在后续任务里陆续追加到同一个类。

- [ ] **步骤 1：编写失败的测试**

创建 `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java`：

```java
package com.ruoyi.openliststrm.pt.stats;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PtStatsServiceTest {

    @Mock private IPtDownloadRecordPlusService downloadRecordService;
    @Mock private IPtSearchLogPlusService searchLogService;
    @Mock private IPtSubscriptionPlusService subscriptionService;
    @Mock private IPtIndexerPlusService indexerService;

    private PtStatsService service() {
        return new PtStatsService(downloadRecordService, searchLogService, subscriptionService, indexerService);
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void overview_下载记录为空_返回全0而不抛异常() {
        when(subscriptionService.count()).thenReturn(0L);
        when(subscriptionService.count(any(Wrapper.class))).thenReturn(0L);
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of());

        PtStatsOverviewDTO dto = service().overview();

        assertEquals(0L, dto.getTotalSubscriptions());
        assertEquals(0L, dto.getActiveSubscriptions());
        assertEquals(0L, dto.getTotalDownloadRecords());
        assertEquals(0L, dto.getCompletedCount());
        assertEquals(0L, dto.getFailedCount());
        assertEquals(0.0, dto.getSuccessRate());
        assertEquals(0.0, dto.getAvgDurationMinutes());
    }

    @Test
    void overview_正常数据_成功率与平均耗时计算正确() {
        when(subscriptionService.count()).thenReturn(20L);
        when(subscriptionService.count(any(Wrapper.class))).thenReturn(15L);
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("total", 100L, "completed_count", 80L, "failed_count", 10L, "avg_duration_minutes", 45.5)));

        PtStatsOverviewDTO dto = service().overview();

        assertEquals(20L, dto.getTotalSubscriptions());
        assertEquals(15L, dto.getActiveSubscriptions());
        assertEquals(100L, dto.getTotalDownloadRecords());
        assertEquals(80L, dto.getCompletedCount());
        assertEquals(10L, dto.getFailedCount());
        assertEquals(80.0, dto.getSuccessRate());
        assertEquals(45.5, dto.getAvgDurationMinutes());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest -q`
预期：FAIL，编译错误 "cannot find symbol: class PtStatsService"（类还不存在）。

- [ ] **步骤 3：编写最少实现代码**

创建 `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java`：

```java
package com.ruoyi.openliststrm.pt.stats;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtIndexerPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSearchLogPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
import com.ruoyi.openliststrm.pt.subscription.SubscriptionService;
import com.ruoyi.openliststrm.pt.task.DownloadRecordState;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * PT 订阅统计仪表盘的聚合查询：全部用 QueryWrapper 原生 select/groupBy + IService.listMaps
 * 完成分组统计，不新建 XML Mapper，见设计文档 2.2 节。
 *
 * @author Jack
 */
@Service
public class PtStatsService {

    private static final String STATE_COMPLETED = DownloadRecordState.COMPLETED.value();
    private static final String STATE_FAILED = DownloadRecordState.FAILED.value();

    private final IPtDownloadRecordPlusService downloadRecordService;
    private final IPtSearchLogPlusService searchLogService;
    private final IPtSubscriptionPlusService subscriptionService;
    private final IPtIndexerPlusService indexerService;

    public PtStatsService(IPtDownloadRecordPlusService downloadRecordService,
                           IPtSearchLogPlusService searchLogService,
                           IPtSubscriptionPlusService subscriptionService,
                           IPtIndexerPlusService indexerService) {
        this.downloadRecordService = downloadRecordService;
        this.searchLogService = searchLogService;
        this.subscriptionService = subscriptionService;
        this.indexerService = indexerService;
    }

    /**
     * 总览统计：订阅总数/活跃数 + 下载记录一次性聚合(总数/完成/失败/成功率/全局平均耗时)，
     * 不做时间范围筛选(设计文档2.1，overview 覆盖全量历史)。
     */
    public PtStatsOverviewDTO overview() {
        PtStatsOverviewDTO dto = new PtStatsOverviewDTO();
        dto.setTotalSubscriptions(subscriptionService.count());
        dto.setActiveSubscriptions(subscriptionService.count(
                Wrappers.<PtSubscriptionPlus>query().eq("status", SubscriptionService.STATUS_ACTIVE)));

        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                Wrappers.<PtDownloadRecordPlus>query().select(
                        "count(*) as total, "
                                + "SUM(CASE WHEN state='" + STATE_COMPLETED + "' THEN 1 ELSE 0 END) as completed_count, "
                                + "SUM(CASE WHEN state='" + STATE_FAILED + "' THEN 1 ELSE 0 END) as failed_count, "
                                + "AVG(CASE WHEN state='" + STATE_COMPLETED
                                + "' THEN TIMESTAMPDIFF(MINUTE, pushed_time, completed_time) ELSE NULL END) as avg_duration_minutes"));
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);

        long total = asLong(row.get("total"));
        long completed = asLong(row.get("completed_count"));
        long failed = asLong(row.get("failed_count"));
        dto.setTotalDownloadRecords(total);
        dto.setCompletedCount(completed);
        dto.setFailedCount(failed);
        dto.setSuccessRate(total > 0 ? Math.round(completed * 1000.0 / total) / 10.0 : 0.0);
        Double avg = asDouble(row.get("avg_duration_minutes"));
        dto.setAvgDurationMinutes(avg == null ? 0.0 : avg);
        return dto;
    }

    private static long asLong(Object v) {
        return v == null ? 0L : Long.parseLong(v.toString());
    }

    private static Double asDouble(Object v) {
        return v == null ? null : Double.parseDouble(v.toString());
    }
}
```

别忘了在文件顶部补上 `import java.util.List;`（上面代码用到了 `List<Map<String, Object>>`）。

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest -q`
预期：PASS，2 个用例全部通过。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java
git commit -m "feat: PtStatsService新增overview总览统计"
```

---

### 任务 3：`PtStatsService.trend(days)`（TDD）

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java`（在 `overview()` 方法后追加 `trend()` 方法）
- 修改：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java`（追加测试方法）

- [ ] **步骤 1：编写失败的测试**

在 `PtStatsServiceTest` 类内追加（`overview_正常数据...` 方法之后）：

```java
    @Test
    void trend_缺失日期补齐为0且平均耗时为null() {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
        String onlyDay = today.format(fmt);
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("day", onlyDay, "pushed_count", 3L, "completed_count", 2L, "failed_count", 0L, "avg_duration_minutes", 30.0)));

        List<com.ruoyi.openliststrm.pt.stats.dto.PtStatsTrendPointDTO> points = service().trend(7);

        assertEquals(7, points.size());
        var last = points.get(points.size() - 1);
        assertEquals(onlyDay, last.getDate());
        assertEquals(3L, last.getPushedCount());
        assertEquals(2L, last.getCompletedCount());
        assertEquals(0L, last.getFailedCount());
        assertEquals(30.0, last.getAvgDurationMinutes());

        var first = points.get(0);
        assertEquals(0L, first.getPushedCount());
        assertEquals(0L, first.getCompletedCount());
        assertEquals(0L, first.getFailedCount());
        org.junit.jupiter.api.Assertions.assertNull(first.getAvgDurationMinutes());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest -q`
预期：FAIL，编译错误 "cannot find symbol: method trend(int)"（方法还不存在，整个测试类编译失败）。

- [ ] **步骤 3：编写最少实现代码**

在 `PtStatsService.java` 的 `overview()` 方法后面（`asLong`/`asDouble` 私有方法前面）追加：

```java
    /**
     * 下载量趋势：按 pushed_time 所在日期分组，日期区间连续补齐(设计文档2.1只用 pushed_time/state
     * 两个字段——按"推送日期"这一维度分类，而不是按完成/失败发生的日期分类)。
     */
    public List<PtStatsTrendPointDTO> trend(int days) {
        java.time.LocalDate start = java.time.LocalDate.now().minusDays(days - 1L);

        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                Wrappers.<PtDownloadRecordPlus>query()
                        .select("DATE_FORMAT(pushed_time,'%Y-%m-%d') as day, "
                                + "count(*) as pushed_count, "
                                + "SUM(CASE WHEN state='" + STATE_COMPLETED + "' THEN 1 ELSE 0 END) as completed_count, "
                                + "SUM(CASE WHEN state='" + STATE_FAILED + "' THEN 1 ELSE 0 END) as failed_count, "
                                + "AVG(CASE WHEN state='" + STATE_COMPLETED
                                + "' THEN TIMESTAMPDIFF(MINUTE, pushed_time, completed_time) ELSE NULL END) as avg_duration_minutes")
                        .ge("pushed_time", start.atStartOfDay())
                        .groupBy("DATE_FORMAT(pushed_time,'%Y-%m-%d')"));

        Map<String, Map<String, Object>> byDay = rows.stream()
                .collect(java.util.stream.Collectors.toMap(r -> String.valueOf(r.get("day")), r -> r));

        List<PtStatsTrendPointDTO> result = new java.util.ArrayList<>();
        for (int i = 0; i < days; i++) {
            java.time.LocalDate day = start.plusDays(i);
            String key = day.format(DAY_FORMATTER);
            Map<String, Object> row = byDay.get(key);
            PtStatsTrendPointDTO point = new PtStatsTrendPointDTO();
            point.setDate(key);
            if (row == null) {
                point.setPushedCount(0);
                point.setCompletedCount(0);
                point.setFailedCount(0);
                point.setAvgDurationMinutes(null);
            } else {
                point.setPushedCount(asLong(row.get("pushed_count")));
                point.setCompletedCount(asLong(row.get("completed_count")));
                point.setFailedCount(asLong(row.get("failed_count")));
                point.setAvgDurationMinutes(asDouble(row.get("avg_duration_minutes")));
            }
            result.add(point);
        }
        return result;
    }
```

同时在类顶部常量区（`STATE_FAILED` 声明后）加一行：

```java
    private static final java.time.format.DateTimeFormatter DAY_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
```

并在 import 区加上 `import com.ruoyi.openliststrm.pt.stats.dto.PtStatsTrendPointDTO;`。

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest -q`
预期：PASS，全部用例（含任务2遗留的2个）通过。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java
git commit -m "feat: PtStatsService新增trend下载量趋势统计"
```

---

### 任务 4：`PtStatsService.indexerHitRate()`（TDD）

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java`（追加 `indexerHitRate()` 方法）
- 修改：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java`（追加测试方法）

- [ ] **步骤 1：编写失败的测试**

在 `PtStatsServiceTest` 中追加：

```java
    @Test
    void indexerHitRate_按索引器计算命中率_除零记0且未产生日志的索引器仍出现() {
        com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus withData =
                new com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus();
        withData.setId(1);
        withData.setName("索引器A");
        com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus withoutData =
                new com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus();
        withoutData.setId(2);
        withoutData.setName("索引器B");
        when(indexerService.list()).thenReturn(List.of(withData, withoutData));
        when(searchLogService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("indexer_id", 1, "accepted_count", 30L, "rejected_count", 10L)));

        List<com.ruoyi.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO> result = service().indexerHitRate();

        assertEquals(2, result.size());
        var a = result.get(0);
        assertEquals(1, a.getIndexerId());
        assertEquals("索引器A", a.getIndexerName());
        assertEquals(30L, a.getAcceptedCount());
        assertEquals(10L, a.getRejectedCount());
        org.junit.jupiter.api.Assertions.assertTrue(a.isHasData());
        assertEquals(0.75, a.getHitRate());

        var b = result.get(1);
        assertEquals(2, b.getIndexerId());
        assertEquals(0L, b.getAcceptedCount());
        assertEquals(0L, b.getRejectedCount());
        org.junit.jupiter.api.Assertions.assertFalse(b.isHasData());
        assertEquals(0.0, b.getHitRate());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest -q`
预期：FAIL，编译错误 "cannot find symbol: method indexerHitRate()"（整个测试类编译失败）。

- [ ] **步骤 3：编写最少实现代码**

在 `PtStatsService.java` 的 `trend()` 方法后面追加：

```java
    /**
     * 索引器命中率：驱动集合是 pt_indexer 全量(indexerService.list())，不是只查有日志的索引器，
     * 新增索引器还没跑过时 hasData=false 也要出现在结果里(设计文档测试计划)。不做 days 筛选，
     * 见设计文档 2.4 节：pt_search_log 本身按订阅保留≤200条，再叠加时间筛选口径会不一致。
     */
    public List<PtStatsIndexerHitRateDTO> indexerHitRate() {
        List<Map<String, Object>> rows = searchLogService.listMaps(
                Wrappers.<PtSearchLogPlus>query()
                        .select("indexer_id as indexer_id, "
                                + "SUM(CASE WHEN accepted='1' THEN 1 ELSE 0 END) as accepted_count, "
                                + "SUM(CASE WHEN accepted='0' THEN 1 ELSE 0 END) as rejected_count")
                        .isNotNull("indexer_id")
                        .groupBy("indexer_id"));

        Map<Integer, Map<String, Object>> byIndexer = rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> ((Number) r.get("indexer_id")).intValue(), r -> r));

        List<PtIndexerPlus> indexers = indexerService.list();
        List<PtStatsIndexerHitRateDTO> result = new java.util.ArrayList<>();
        for (PtIndexerPlus indexer : indexers) {
            Map<String, Object> row = byIndexer.get(indexer.getId());
            PtStatsIndexerHitRateDTO dto = new PtStatsIndexerHitRateDTO();
            dto.setIndexerId(indexer.getId());
            dto.setIndexerName(indexer.getName());
            long accepted = row == null ? 0 : asLong(row.get("accepted_count"));
            long rejected = row == null ? 0 : asLong(row.get("rejected_count"));
            dto.setAcceptedCount(accepted);
            dto.setRejectedCount(rejected);
            long denom = accepted + rejected;
            dto.setHasData(denom > 0);
            dto.setHitRate(denom > 0 ? Math.round(accepted * 10000.0 / denom) / 10000.0 : 0.0);
            result.add(dto);
        }
        return result;
    }
```

并在 import 区加上：

```java
import com.ruoyi.openliststrm.mybatisplus.domain.PtIndexerPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSearchLogPlus;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO;
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest -q`
预期：PASS，全部用例通过。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java
git commit -m "feat: PtStatsService新增indexerHitRate索引器命中率统计"
```

---

### 任务 5：`PtStatsService.failReasons(days)`（TDD）

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java`（追加 `failReasons()` 方法）
- 修改：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java`（追加测试方法）

- [ ] **步骤 1：编写失败的测试**

在 `PtStatsServiceTest` 中追加：

```java
    @Test
    void failReasons_返回两种固定文案的计数与顺序() {
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("reason", "下载超过 24 小时仍未完成，判定为僵尸种子", "count", 12L),
                row("reason", "下载器中已找不到该种子（可能被删除或元数据解析失败）", "count", 5L)));

        List<com.ruoyi.openliststrm.pt.stats.dto.PtStatsFailReasonDTO> result = service().failReasons(30);

        assertEquals(2, result.size());
        assertEquals("下载超过 24 小时仍未完成，判定为僵尸种子", result.get(0).getReason());
        assertEquals(12L, result.get(0).getCount());
        assertEquals("下载器中已找不到该种子（可能被删除或元数据解析失败）", result.get(1).getReason());
        assertEquals(5L, result.get(1).getCount());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest -q`
预期：FAIL，编译错误 "cannot find symbol: method failReasons(int)"（整个测试类编译失败）。

- [ ] **步骤 3：编写最少实现代码**

在 `PtStatsService.java` 的 `indexerHitRate()` 方法后面追加：

```java
    /**
     * 失败原因分布：fail_reason 只由 DownloadTrackService.fail() 写入，固定两种文案，
     * 直接按原始字符串 GROUP BY 即可，不做归一化(设计文档2.1)。
     */
    public List<PtStatsFailReasonDTO> failReasons(int days) {
        java.time.LocalDate start = java.time.LocalDate.now().minusDays(days - 1L);
        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                Wrappers.<PtDownloadRecordPlus>query()
                        .select("fail_reason as reason, count(*) as count")
                        .eq("state", STATE_FAILED)
                        .ge("pushed_time", start.atStartOfDay())
                        .groupBy("fail_reason")
                        .orderByDesc("count"));

        return rows.stream().map(row -> {
            PtStatsFailReasonDTO dto = new PtStatsFailReasonDTO();
            dto.setReason(String.valueOf(row.get("reason")));
            dto.setCount(asLong(row.get("count")));
            return dto;
        }).toList();
    }
```

并在 import 区加上 `import com.ruoyi.openliststrm.pt.stats.dto.PtStatsFailReasonDTO;`。

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest -q`
预期：PASS，全部用例通过。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java
git commit -m "feat: PtStatsService新增failReasons失败原因分布统计"
```

---

### 任务 6：`PtStatsService.topSubscriptions(days, limit)`（TDD）

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java`（追加 `topSubscriptions()` 方法）
- 修改：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java`（追加测试方法）

- [ ] **步骤 1：编写失败的测试**

在 `PtStatsServiceTest` 中追加：

```java
    @Test
    void topSubscriptions_limit生效且订阅已删除时兜底展示() {
        when(downloadRecordService.listMaps(any(Wrapper.class))).thenReturn(List.of(
                row("sub_id", 10, "download_count", 8L, "completed_count", 6L, "failed_count", 1L),
                row("sub_id", 11, "download_count", 3L, "completed_count", 3L, "failed_count", 0L)));
        com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus sub10 =
                new com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus();
        sub10.setId(10);
        sub10.setTitle("怪奇物语");
        sub10.setSeason(4);
        sub10.setMediaType("TV");
        when(subscriptionService.listByIds(any())).thenReturn(List.of(sub10));

        List<com.ruoyi.openliststrm.pt.stats.dto.PtStatsActiveSubscriptionDTO> result =
                service().topSubscriptions(30, 2);

        assertEquals(2, result.size());
        assertEquals(10, result.get(0).getSubId());
        assertEquals("怪奇物语", result.get(0).getTitle());
        assertEquals(4, result.get(0).getSeason());
        assertEquals("TV", result.get(0).getMediaType());
        assertEquals(8L, result.get(0).getDownloadCount());

        assertEquals(11, result.get(1).getSubId());
        assertEquals("（订阅已删除）", result.get(1).getTitle());
        org.junit.jupiter.api.Assertions.assertNull(result.get(1).getSeason());
        org.junit.jupiter.api.Assertions.assertNull(result.get(1).getMediaType());
        org.junit.jupiter.api.Assertions.assertNull(result.get(1).getLastMatchTime());
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest -q`
预期：FAIL，编译错误 "cannot find symbol: method topSubscriptions(int,int)"（整个测试类编译失败）。

- [ ] **步骤 3：编写最少实现代码**

在 `PtStatsService.java` 的 `failReasons()` 方法后面追加：

```java
    /**
     * Top 活跃订阅：按 sub_id 分组的下载次数排行，limit 走 QueryWrapper.last("LIMIT n")
     * (跟 SearchLogService 里清理旧日志用的同一种写法，n 是后端已校验过的白名单/上限值，无拼接风险)。
     * 订阅已被删除时(historical download record 还在但 pt_subscription 查不到)兜底展示，不抛 NPE。
     */
    public List<PtStatsActiveSubscriptionDTO> topSubscriptions(int days, int limit) {
        java.time.LocalDate start = java.time.LocalDate.now().minusDays(days - 1L);
        List<Map<String, Object>> rows = downloadRecordService.listMaps(
                Wrappers.<PtDownloadRecordPlus>query()
                        .select("sub_id as sub_id, count(*) as download_count, "
                                + "SUM(CASE WHEN state='" + STATE_COMPLETED + "' THEN 1 ELSE 0 END) as completed_count, "
                                + "SUM(CASE WHEN state='" + STATE_FAILED + "' THEN 1 ELSE 0 END) as failed_count")
                        .ge("pushed_time", start.atStartOfDay())
                        .groupBy("sub_id")
                        .orderByDesc("download_count")
                        .last("LIMIT " + limit));

        List<Integer> subIds = rows.stream().map(r -> ((Number) r.get("sub_id")).intValue()).toList();
        Map<Integer, PtSubscriptionPlus> subs = subIds.isEmpty() ? Map.of() : subscriptionService.listByIds(subIds)
                .stream().collect(java.util.stream.Collectors.toMap(PtSubscriptionPlus::getId, s -> s));

        return rows.stream().map(row -> {
            int subId = ((Number) row.get("sub_id")).intValue();
            PtSubscriptionPlus sub = subs.get(subId);
            PtStatsActiveSubscriptionDTO dto = new PtStatsActiveSubscriptionDTO();
            dto.setSubId(subId);
            dto.setTitle(sub == null ? "（订阅已删除）" : sub.getTitle());
            dto.setSeason(sub == null ? null : sub.getSeason());
            dto.setMediaType(sub == null ? null : sub.getMediaType());
            dto.setDownloadCount(asLong(row.get("download_count")));
            dto.setCompletedCount(asLong(row.get("completed_count")));
            dto.setFailedCount(asLong(row.get("failed_count")));
            dto.setLastMatchTime(sub == null ? null : sub.getLastMatchTime());
            return dto;
        }).toList();
    }
```

并在 import 区加上 `import com.ruoyi.openliststrm.pt.stats.dto.PtStatsActiveSubscriptionDTO;`。

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest -q`
预期：PASS，全部 6 个用例通过（overview 2 + trend 1 + indexerHitRate 1 + failReasons 1 + topSubscriptions 1）。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/stats/PtStatsService.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/stats/PtStatsServiceTest.java
git commit -m "feat: PtStatsService新增topSubscriptions活跃订阅排行统计"
```

---

### 任务 7：`PtStatsRestController`（TDD）

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtStatsRestController.java`
- 创建：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/controller/api/PtStatsRestControllerTest.java`

Controller 只做 `days`/`limit` 参数白名单校验 + 转调 `PtStatsService`，不写聚合逻辑（聚合逻辑已在任务2-6覆盖）。用构造器注入 `PtStatsService`，方便测试直接 `new`，不用像 `PtSubscriptionRestControllerTest` 那样反射注入字段。

- [ ] **步骤 1：编写失败的测试**

创建 `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/controller/api/PtStatsRestControllerTest.java`：

```java
package com.ruoyi.openliststrm.controller.api;

import com.ruoyi.common.core.domain.Result;
import com.ruoyi.openliststrm.pt.stats.PtStatsService;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsTrendPointDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PtStatsRestControllerTest {

    @Mock
    private PtStatsService statsService;

    private PtStatsRestController controller() {
        return new PtStatsRestController(statsService);
    }

    @Test
    void normalizeDays_合法值原样返回() {
        assertEquals(7, PtStatsRestController.normalizeDays(7));
        assertEquals(30, PtStatsRestController.normalizeDays(30));
        assertEquals(90, PtStatsRestController.normalizeDays(90));
    }

    @Test
    void normalizeDays_非法值或null回退到30() {
        assertEquals(30, PtStatsRestController.normalizeDays(null));
        assertEquals(30, PtStatsRestController.normalizeDays(15));
        assertEquals(30, PtStatsRestController.normalizeDays(-1));
    }

    @Test
    void normalizeLimit_超过50截断() {
        assertEquals(50, PtStatsRestController.normalizeLimit(100));
        assertEquals(50, PtStatsRestController.normalizeLimit(50));
    }

    @Test
    void normalizeLimit_null或非正数回退到默认10() {
        assertEquals(10, PtStatsRestController.normalizeLimit(null));
        assertEquals(10, PtStatsRestController.normalizeLimit(0));
        assertEquals(10, PtStatsRestController.normalizeLimit(-5));
    }

    @Test
    void trend_非法days参数按30转调service() {
        when(statsService.trend(30)).thenReturn(List.of());

        Result<List<PtStatsTrendPointDTO>> result = controller().trend(999);

        assertEquals(200, result.getCode());
        verify(statsService).trend(30);
    }

    @Test
    void topSubscriptions_limit超过50被截断转调service() {
        when(statsService.topSubscriptions(30, 50)).thenReturn(List.of());

        controller().topSubscriptions(null, 999);

        verify(statsService).topSubscriptions(30, 50);
    }

    @Test
    void failReasons_合法days原样转调service() {
        when(statsService.failReasons(7)).thenReturn(List.of());

        controller().failReasons(7);

        verify(statsService).failReasons(7);
    }

    @Test
    void overview_直接转调service() {
        when(statsService.overview()).thenReturn(new com.ruoyi.openliststrm.pt.stats.dto.PtStatsOverviewDTO());

        Result<com.ruoyi.openliststrm.pt.stats.dto.PtStatsOverviewDTO> result = controller().overview();

        assertEquals(200, result.getCode());
    }

    @Test
    void indexerHitRate_直接转调service() {
        when(statsService.indexerHitRate()).thenReturn(List.of());

        Result<List<com.ruoyi.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO>> result = controller().indexerHitRate();

        assertEquals(200, result.getCode());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsRestControllerTest -q`
预期：FAIL，编译错误 "cannot find symbol: class PtStatsRestController"（类还不存在）。

- [ ] **步骤 3：编写最少实现代码**

创建 `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtStatsRestController.java`：

```java
package com.ruoyi.openliststrm.controller.api;

import com.ruoyi.common.core.domain.Result;
import com.ruoyi.openliststrm.pt.stats.PtStatsService;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsActiveSubscriptionDTO;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsFailReasonDTO;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsIndexerHitRateDTO;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsOverviewDTO;
import com.ruoyi.openliststrm.pt.stats.dto.PtStatsTrendPointDTO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * PT 统计仪表盘只读 REST API：5 个独立端点，不含业务逻辑，只做参数白名单校验后转调
 * {@link PtStatsService}(设计文档4节：Controller 瘦、Service 厚)。
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/pt-stats")
public class PtStatsRestController {

    private static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);
    static final int DEFAULT_DAYS = 30;
    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    private final PtStatsService statsService;

    public PtStatsRestController(PtStatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/overview")
    public Result<PtStatsOverviewDTO> overview() {
        return Result.success(statsService.overview());
    }

    @GetMapping("/trend")
    public Result<List<PtStatsTrendPointDTO>> trend(@RequestParam(value = "days", required = false) Integer days) {
        return Result.success(statsService.trend(normalizeDays(days)));
    }

    @GetMapping("/indexer-hit-rate")
    public Result<List<PtStatsIndexerHitRateDTO>> indexerHitRate() {
        return Result.success(statsService.indexerHitRate());
    }

    @GetMapping("/fail-reasons")
    public Result<List<PtStatsFailReasonDTO>> failReasons(@RequestParam(value = "days", required = false) Integer days) {
        return Result.success(statsService.failReasons(normalizeDays(days)));
    }

    @GetMapping("/top-subscriptions")
    public Result<List<PtStatsActiveSubscriptionDTO>> topSubscriptions(
            @RequestParam(value = "days", required = false) Integer days,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return Result.success(statsService.topSubscriptions(normalizeDays(days), normalizeLimit(limit)));
    }

    /** days 只允许 7/30/90，非法值(含null)一律回退到 30，避免前端传入超大天数触发无边界的全表扫描 */
    static int normalizeDays(Integer days) {
        return days != null && ALLOWED_DAYS.contains(days) ? days : DEFAULT_DAYS;
    }

    /** limit 上限 50，避免一次拉出过多订阅；非法值(null/<=0)回退默认 10 */
    static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsRestControllerTest -q`
预期：PASS，9 个用例全部通过。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtStatsRestController.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/controller/api/PtStatsRestControllerTest.java
git commit -m "feat: 新增PtStatsRestController，暴露PT统计仪表盘5个只读接口"
```

---

### 任务 8：数据库迁移脚本 + `MysqlDdl` 注册 + 启动验证

**文件：**
- 创建：`ruoyi-common/src/main/resources/sql/20260738-pt-stats-menu.sql`
- 修改：`ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java:61-63`

- [ ] **步骤 1：创建迁移脚本**

创建 `ruoyi-common/src/main/resources/sql/20260738-pt-stats-menu.sql`：

```sql
-- ----------------------------
-- 20260738: 新增"PT统计仪表盘"页面菜单
-- 挂在 PT下载管理(2070) 分组下，排在"PT下载记录"(2066, order_num=6)之后。
-- 页面与后端接口在同一批次上线，直接 visible='0'(显示)，参照 20260731 的先例。
-- 图标 fa fa-bar-chart 在 openlist-web/src/composables/useMenuIcon.ts 的 iconMap 里补了映射
-- (指向 TrendCharts)，跟同组内其余6个已用图标(rss/download/server/bookmark-o/sliders/list-ul)
-- 及父分组自己的 fa-bars 都不重复。
-- ----------------------------
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2071, 'PT统计仪表盘', 2070, 7, '/openlist/ptStatsDashboard', '', 'C', '0', '1', 'openliststrm:ptStatsDashboard:view', 'fa fa-bar-chart', 'admin', '2026-07-24 00:00:00', '', NULL, 'PT 订阅下载统计仪表盘：下载量趋势/索引器命中率/失败原因分布/Top活跃订阅');
```

- [ ] **步骤 2：注册进 `MysqlDdl.java`**

修改 `ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java`，把第 61-63 行：

```java
                "sql/20260736-menu-categories.sql",
                "sql/20260737-fix-menu-group-icon-duplication.sql"
        );
```

改成：

```java
                "sql/20260736-menu-categories.sql",
                "sql/20260737-fix-menu-group-icon-duplication.sql",
                "sql/20260738-pt-stats-menu.sql"
        );
```

- [ ] **步骤 3：编译并启动验证**

```bash
mvn clean package -DskipTests
docker compose up -d --build --no-deps backend
```

等后端容器重启完成后确认没有崩溃重启：

```bash
docker ps --filter "name=osr-backend" --format "{{.Names}}: {{.Status}}"
```

预期：`Up` 且不带 `Restarting`；如果反复重启，按 AGENTS.md 里的排查方法执行 `docker update --restart=no osr-backend && docker restart osr-backend`，再 `docker cp osr-backend:/data/logs ./tmp` 看 `tmp/sys-error.log`。

再查询确认菜单插入成功（密码从容器环境变量读取，不手输明文）：

```bash
docker exec osr-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" osr -N -e "SELECT menu_id, menu_name, parent_id, order_num, icon, perms FROM sys_menu WHERE menu_id=2071;"'
```

预期：返回 1 行，`parent_id=2070`，`order_num=7`，`icon=fa fa-bar-chart`，`perms=openliststrm:ptStatsDashboard:view`。

再验证幂等性——重复执行一次（重启后端容器）不应报错，且上面这条查询结果不变：

```bash
docker compose up -d --build --no-deps backend
```

- [ ] **步骤 4：Commit**

```bash
git add ruoyi-common/src/main/resources/sql/20260738-pt-stats-menu.sql ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java
git commit -m "feat: 新增PT统计仪表盘菜单迁移脚本"
```

---

### 任务 9：前端 API 封装 `ptStats.ts`

**文件：**
- 创建：`openlist-web/src/api/openlist/ptStats.ts`

前端 API 封装是纯声明式的 axios 调用封装（跟 `ptDownloadRecord.ts`/`dashboard.ts` 同类风格），没有独立可测的逻辑，本任务用 `vue-tsc` 类型检查作为验证，不写 Vitest 用例。

- [ ] **步骤 1：创建 API 文件**

创建 `openlist-web/src/api/openlist/ptStats.ts`：

```typescript
import request from '@/api/request'

export interface PtStatsOverview {
  totalSubscriptions: number
  activeSubscriptions: number
  totalDownloadRecords: number
  completedCount: number
  failedCount: number
  successRate: number
  avgDurationMinutes: number
}

export interface PtStatsTrendPoint {
  date: string
  pushedCount: number
  completedCount: number
  failedCount: number
  avgDurationMinutes: number | null
}

export interface PtStatsIndexerHitRate {
  indexerId: number
  indexerName: string
  acceptedCount: number
  rejectedCount: number
  hitRate: number
  hasData: boolean
}

export interface PtStatsFailReason {
  reason: string
  count: number
}

export interface PtStatsActiveSubscription {
  subId: number
  title: string
  season: number | null
  mediaType: string | null
  downloadCount: number
  completedCount: number
  failedCount: number
  lastMatchTime: string | null
}

export function getPtStatsOverviewApi() {
  return request.get<any, PtStatsOverview>('/openliststrm/pt-stats/overview')
}

export function getPtStatsTrendApi(days: number) {
  return request.get<any, PtStatsTrendPoint[]>('/openliststrm/pt-stats/trend', { params: { days } })
}

export function getPtStatsIndexerHitRateApi() {
  return request.get<any, PtStatsIndexerHitRate[]>('/openliststrm/pt-stats/indexer-hit-rate')
}

export function getPtStatsFailReasonsApi(days: number) {
  return request.get<any, PtStatsFailReason[]>('/openliststrm/pt-stats/fail-reasons', { params: { days } })
}

export function getPtStatsTopSubscriptionsApi(days: number, limit: number) {
  return request.get<any, PtStatsActiveSubscription[]>('/openliststrm/pt-stats/top-subscriptions', { params: { days, limit } })
}
```

- [ ] **步骤 2：类型检查验证**

运行：`cd openlist-web && npx vue-tsc --noEmit`
预期：无报错（此时页面组件还没创建，这一步只验证 `ptStats.ts` 自身类型正确；任务11创建页面组件后会再跑一次完整检查）。

- [ ] **步骤 3：Commit**

```bash
git add openlist-web/src/api/openlist/ptStats.ts
git commit -m "feat: 新增PT统计仪表盘前端API封装"
```

---

### 任务 10：图标映射 + 路由注册

**文件：**
- 修改：`openlist-web/src/composables/useMenuIcon.ts:1-5,37`
- 修改：`openlist-web/src/router/index.ts:108-113`

- [ ] **步骤 1：`useMenuIcon.ts` 新增图标映射**

把第 1-5 行：

```typescript
import {
  Setting, Document, Picture, Monitor, Tools, Calendar, Coin, Promotion,
  Watermelon, Menu as IconMenu, VideoPlay, RefreshRight, EditPen,
  FolderOpened, DocumentCopy, MagicStick, Connection, Download, Film, Filter
} from '@element-plus/icons-vue'
```

改成：

```typescript
import {
  Setting, Document, Picture, Monitor, Tools, Calendar, Coin, Promotion,
  Watermelon, Menu as IconMenu, VideoPlay, RefreshRight, EditPen,
  FolderOpened, DocumentCopy, MagicStick, Connection, Download, Film, Filter,
  TrendCharts
} from '@element-plus/icons-vue'
```

把第 37 行（iconMap 最后一个条目）：

```typescript
  'fa fa-sliders': Filter
```

改成：

```typescript
  'fa fa-sliders': Filter,
  'fa fa-bar-chart': TrendCharts
```

- [ ] **步骤 2：`router/index.ts` 新增 componentMap 条目**

把第 108-113 行：

```typescript
  'openlist/ptFilterConfig/index': () => import('@/views/openlist/ptFilterConfig/index.vue'),
  'openlist/ptDownloadRecord/index': createDeviceView(
    () => import('@/views/openlist/ptDownloadRecord/index.vue'),
    () => import('@/views-mobile/ptDownloadRecord/index.vue')
  )
}
```

改成：

```typescript
  'openlist/ptFilterConfig/index': () => import('@/views/openlist/ptFilterConfig/index.vue'),
  'openlist/ptDownloadRecord/index': createDeviceView(
    () => import('@/views/openlist/ptDownloadRecord/index.vue'),
    () => import('@/views-mobile/ptDownloadRecord/index.vue')
  ),
  'openlist/ptStatsDashboard/index': () => import('@/views/openlist/ptStatsDashboard/index.vue')
}
```

（不用 `createDeviceView`——本页面只做 PC 端，见设计文档 6.3 节；跟 `ptFilterConfig`/`renameConfig` 两个"配置类/低频访问"页面的写法一致。这一步会引用还不存在的 `views/openlist/ptStatsDashboard/index.vue`，`vue-tsc`/`vite` 的动态 `import()` 不会在类型检查阶段报错——下一个任务创建页面文件后即可解析。）

- [ ] **步骤 3：Commit**

```bash
git add openlist-web/src/composables/useMenuIcon.ts openlist-web/src/router/index.ts
git commit -m "feat: 新增PT统计仪表盘图标映射与路由注册"
```

---

### 任务 11：前端页面 `views/openlist/ptStatsDashboard/index.vue`

**文件：**
- 创建：`openlist-web/src/views/openlist/ptStatsDashboard/index.vue`

本任务是纯展示页面（无独立业务逻辑类可单测），设计文档 7 节明确"不新增 Playwright E2E"，验证方式是 `vue-tsc` 类型检查 + `npm run build` + 手动打开页面检查暂无数据/加载失败占位是否正常（放到任务12的端到端验证里）。

- [ ] **步骤 1：创建页面文件**

创建 `openlist-web/src/views/openlist/ptStatsDashboard/index.vue`：

```vue
<template>
  <div class="pt-stats-dashboard">
    <div class="toolbar">
      <span class="toolbar-label">统计范围</span>
      <el-radio-group v-model="rangeDays" size="default" @change="onRangeChange">
        <el-radio-button :label="7">近7天</el-radio-button>
        <el-radio-button :label="30">近30天</el-radio-button>
        <el-radio-button :label="90">近90天</el-radio-button>
      </el-radio-group>
      <el-button :icon="Refresh" class="refresh-btn" @click="loadAll">刷新</el-button>
    </div>

    <el-row :gutter="16" class="stat-row">
      <el-col :md="8" v-for="(stat, index) in statCards" :key="index">
        <el-card class="stat-card" :class="stat.type">
          <div class="stat-icon">
            <el-icon :size="28"><component :is="stat.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ stat.value }}</div>
            <div class="stat-label">{{ stat.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :md="24">
        <el-card class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">下载量趋势</span>
            </div>
          </template>
          <div ref="trendContainer" class="echarts-container trend-container" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :md="12">
        <el-card class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">索引器命中率</span>
              <span class="chart-subtitle">基于每订阅最近 200 条匹配记录</span>
            </div>
          </template>
          <div ref="indexerContainer" class="echarts-container" />
          <div v-if="noDataIndexerNames.length" class="no-data-indexers">
            暂无数据：{{ noDataIndexerNames.join('、') }}
          </div>
        </el-card>
      </el-col>
      <el-col :md="12">
        <el-card class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">失败原因分布</span>
            </div>
          </template>
          <div ref="failReasonContainer" class="echarts-container" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" class="chart-row">
      <el-col :md="24">
        <el-card class="chart-card">
          <template #header>
            <div class="chart-header">
              <span class="chart-title">Top 活跃订阅</span>
            </div>
          </template>
          <el-table :data="topSubscriptions" v-loading="topSubscriptionsLoading" style="width: 100%">
            <el-table-column prop="title" label="订阅标题" min-width="180" />
            <el-table-column label="季/类型" width="100">
              <template #default="{ row }">
                <span v-if="row.mediaType === 'MOVIE'">电影</span>
                <span v-else-if="row.season != null">S{{ row.season }}</span>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column prop="downloadCount" label="下载次数" width="100" />
            <el-table-column prop="completedCount" label="完成数" width="100" />
            <el-table-column prop="failedCount" label="失败数" width="100" />
            <el-table-column label="上次命中时间" width="180">
              <template #default="{ row }">{{ row.lastMatchTime || '-' }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
// 按需引入：本页只用到 line/bar/pie，避免全量引入 echarts 拖大打包体积
import * as echarts from 'echarts/core'
import { LineChart, BarChart, PieChart } from 'echarts/charts'
import { TitleComponent, TooltipComponent, LegendComponent, GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { Document, Connection, Download, CircleCheck, Clock, Refresh } from '@element-plus/icons-vue'
import {
  getPtStatsOverviewApi,
  getPtStatsTrendApi,
  getPtStatsIndexerHitRateApi,
  getPtStatsFailReasonsApi,
  getPtStatsTopSubscriptionsApi,
  type PtStatsActiveSubscription
} from '@/api/openlist/ptStats'
import type { Component } from 'vue'

echarts.use([LineChart, BarChart, PieChart, TitleComponent, TooltipComponent, LegendComponent, GridComponent, CanvasRenderer])

interface StatCard {
  label: string
  value: number | string
  icon: Component
  type: 'primary' | 'success' | 'warning' | 'info'
}

const rangeDays = ref(30)
const statCards = ref<StatCard[]>([])
const topSubscriptions = ref<PtStatsActiveSubscription[]>([])
const topSubscriptionsLoading = ref(false)
const noDataIndexerNames = ref<string[]>([])

const trendContainer = ref<HTMLElement | null>(null)
const indexerContainer = ref<HTMLElement | null>(null)
const failReasonContainer = ref<HTMLElement | null>(null)

let trendChart: any = null
let indexerChart: any = null
let failReasonChart: any = null
let resizeHandler: (() => void) | null = null

const defaultColors = ['#0d9488', '#22c55e', '#f59e0b', '#ef4444', '#6366f1', '#8b5cf6', '#ec4899', '#14b8a6']

// 失败原因分布的配色：照抄 views/dashboard/desktop.vue 的 colorMap/getColor 实现思路
// （同色系映射：名字里带"失败"字样的用红色，其余落到 defaultColors 轮转），
// 设计文档6.1节明确"直接照抄这段逻辑到本页面"，两处各自独立演化更简单，不抽公共 util。
const failReasonColorMap: Record<string, string> = {
  '成功': '#22c55e',
  '失败': '#ef4444',
  '未知': '#f59e0b',
  '处理中': '#0d9488'
}

function getFailReasonColor(name: string): string {
  if (failReasonColorMap[name]) return failReasonColorMap[name]
  const idx = Object.keys(failReasonColorMap).findIndex(k => name.includes(k))
  return idx >= 0
    ? failReasonColorMap[Object.keys(failReasonColorMap)[idx]]
    : defaultColors[(Object.keys(failReasonColorMap).length + idx) % defaultColors.length]
}

function emptyOption(text: string) {
  return { title: { text, left: 'center', top: 'center', textStyle: { fontSize: 14, color: '#94a3b8' } }, series: [] }
}

async function loadOverview() {
  try {
    const data = await getPtStatsOverviewApi()
    statCards.value = [
      { label: '总订阅数', value: data.totalSubscriptions, icon: Document, type: 'primary' },
      { label: '活跃订阅数', value: data.activeSubscriptions, icon: Connection, type: 'success' },
      { label: '下载记录总数', value: data.totalDownloadRecords, icon: Download, type: 'info' },
      { label: '成功率', value: data.totalDownloadRecords > 0 ? data.successRate + '%' : '--', icon: CircleCheck, type: 'success' },
      { label: '平均下载耗时', value: data.avgDurationMinutes > 0 ? Math.round(data.avgDurationMinutes) + ' 分钟' : '--', icon: Clock, type: 'warning' }
    ]
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load overview:', e)
    statCards.value = [
      { label: '总订阅数', value: '0', icon: Document, type: 'primary' },
      { label: '活跃订阅数', value: '0', icon: Connection, type: 'success' },
      { label: '下载记录总数', value: '0', icon: Download, type: 'info' },
      { label: '成功率', value: '--', icon: CircleCheck, type: 'success' },
      { label: '平均下载耗时', value: '--', icon: Clock, type: 'warning' }
    ]
  }
}

async function loadTrend() {
  if (!trendContainer.value) return
  if (!trendChart) trendChart = echarts.init(trendContainer.value)
  try {
    const data = await getPtStatsTrendApi(rangeDays.value)
    if (!data || data.length === 0) {
      trendChart.clear()
      trendChart.setOption(emptyOption('暂无数据'), true)
      return
    }
    trendChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { data: ['推送', '完成', '失败'], top: 0 },
      grid: { left: 40, right: 20, top: 40, bottom: 30 },
      xAxis: { type: 'category', data: data.map(p => p.date) },
      yAxis: { type: 'value' },
      series: [
        { name: '推送', type: 'line', data: data.map(p => p.pushedCount), itemStyle: { color: '#0d9488' } },
        { name: '完成', type: 'line', data: data.map(p => p.completedCount), itemStyle: { color: '#22c55e' } },
        { name: '失败', type: 'line', data: data.map(p => p.failedCount), itemStyle: { color: '#ef4444' } }
      ]
    }, true)
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load trend:', e)
    if (trendChart) {
      trendChart.clear()
      trendChart.setOption(emptyOption('加载失败'), true)
    }
  }
}

async function loadIndexerHitRate() {
  if (!indexerContainer.value) return
  if (!indexerChart) indexerChart = echarts.init(indexerContainer.value)
  try {
    const data = await getPtStatsIndexerHitRateApi()
    noDataIndexerNames.value = (data || []).filter(i => !i.hasData).map(i => i.indexerName)
    const withData = (data || []).filter(i => i.hasData)
    if (withData.length === 0) {
      indexerChart.clear()
      indexerChart.setOption(emptyOption('暂无数据'), true)
      return
    }
    indexerChart.setOption({
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
      legend: { data: ['通过', '淘汰'], top: 0 },
      grid: { left: 100, right: 20, top: 40, bottom: 20 },
      xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' } },
      yAxis: { type: 'category', data: withData.map(i => i.indexerName) },
      series: [
        { name: '通过', type: 'bar', stack: 'total', itemStyle: { color: '#22c55e' },
          data: withData.map(i => Math.round(i.hitRate * 1000) / 10) },
        { name: '淘汰', type: 'bar', stack: 'total', itemStyle: { color: '#ef4444' },
          data: withData.map(i => Math.round((1 - i.hitRate) * 1000) / 10) }
      ]
    }, true)
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load indexer hit rate:', e)
    if (indexerChart) {
      indexerChart.clear()
      indexerChart.setOption(emptyOption('加载失败'), true)
    }
  }
}

async function loadFailReasons() {
  if (!failReasonContainer.value) return
  if (!failReasonChart) failReasonChart = echarts.init(failReasonContainer.value)
  try {
    const data = await getPtStatsFailReasonsApi(rangeDays.value)
    if (!data || data.length === 0) {
      failReasonChart.clear()
      failReasonChart.setOption(emptyOption('暂无数据'), true)
      return
    }
    failReasonChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      series: [{
        type: 'pie',
        radius: ['35%', '65%'],
        center: ['50%', '55%'],
        avoidLabelOverlap: false,
        itemStyle: { borderRadius: 6, borderColor: '#fff', borderWidth: 3 },
        label: { show: true, formatter: '{b}\n{c}', fontSize: 11 },
        labelLine: { length: 15, length2: 10 },
        minAngle: 5,
        data: data.map(item => ({
          value: item.count,
          name: item.reason,
          itemStyle: { color: getFailReasonColor(item.reason) }
        }))
      }]
    }, true)
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load fail reasons:', e)
    if (failReasonChart) {
      failReasonChart.clear()
      failReasonChart.setOption(emptyOption('加载失败'), true)
    }
  }
}

async function loadTopSubscriptions() {
  topSubscriptionsLoading.value = true
  try {
    const data = await getPtStatsTopSubscriptionsApi(rangeDays.value, 10)
    topSubscriptions.value = data || []
  } catch (e) {
    console.error('[PtStatsDashboard] Failed to load top subscriptions:', e)
    topSubscriptions.value = []
  } finally {
    topSubscriptionsLoading.value = false
  }
}

async function loadAll() {
  await Promise.all([loadOverview(), loadTrend(), loadIndexerHitRate(), loadFailReasons(), loadTopSubscriptions()])
}

async function onRangeChange() {
  // 索引器命中率(2.4)和总览(2.1)不受时间挡位影响，只重新加载 trend/failReasons/topSubscriptions
  await Promise.all([loadTrend(), loadFailReasons(), loadTopSubscriptions()])
}

onMounted(async () => {
  await nextTick()
  await loadAll()

  resizeHandler = () => {
    trendChart?.resize()
    indexerChart?.resize()
    failReasonChart?.resize()
  }
  window.addEventListener('resize', resizeHandler)
})

onUnmounted(() => {
  resizeHandler && window.removeEventListener('resize', resizeHandler)
  trendChart?.dispose()
  indexerChart?.dispose()
  failReasonChart?.dispose()
})
</script>

<style scoped lang="scss">
.pt-stats-dashboard {
  padding: 24px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;

  .toolbar-label {
    font-size: 14px;
    color: var(--osr-text-secondary);
  }

  .refresh-btn {
    margin-left: auto;
  }
}

.stat-row {
  margin-bottom: 24px;
}

.stat-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
  margin-bottom: 16px;
  cursor: default;
  transition: all var(--osr-transition-base);

  &:hover {
    transform: translateY(-2px);
    box-shadow: var(--osr-shadow-md);
  }

  :deep(.el-card__body) {
    display: flex;
    align-items: center;
    padding: 20px;
    gap: 16px;
  }

  .stat-icon {
    width: 52px;
    height: 52px;
    border-radius: var(--osr-radius-md);
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }

  .stat-info {
    flex: 1;
    min-width: 0;

    .stat-value {
      font-size: 24px;
      font-weight: 700;
      color: var(--osr-text-primary);
      line-height: 1.2;
    }

    .stat-label {
      font-size: 13px;
      color: var(--osr-text-secondary);
      margin-top: 2px;
    }
  }

  &.primary .stat-icon {
    background-color: var(--osr-primary-light-9);
    color: var(--osr-primary);
  }
  &.success .stat-icon {
    background-color: var(--osr-success-light);
    color: var(--osr-success);
  }
  &.warning .stat-icon {
    background-color: var(--osr-warning-light);
    color: var(--osr-warning);
  }
  &.info .stat-icon {
    background-color: var(--osr-info-light);
    color: var(--osr-info);
  }
}

.chart-row {
  margin-bottom: 16px;
}

.chart-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
  margin-bottom: 16px;
  transition: box-shadow var(--osr-transition-base);

  &:hover {
    box-shadow: var(--osr-shadow-md);
  }

  :deep(.el-card__header) {
    padding: 16px 20px;
    border-bottom: 1px solid var(--osr-border-light);
    background-color: var(--osr-surface);
  }
}

.chart-header {
  display: flex;
  justify-content: space-between;
  align-items: center;

  .chart-title {
    font-size: 15px;
    font-weight: 600;
    color: var(--osr-text-primary);
  }

  .chart-subtitle {
    font-size: 12px;
    color: var(--osr-text-secondary);
  }
}

.echarts-container {
  height: 260px;
  width: 100%;
}

.trend-container {
  height: 300px;
}

.no-data-indexers {
  margin-top: 8px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

@media (max-width: 768px) {
  .pt-stats-dashboard {
    padding: 16px;
  }

  .stat-card :deep(.el-card__body) {
    padding: 16px;
  }

  .echarts-container {
    height: 220px !important;
  }
}
</style>
```

- [ ] **步骤 2：类型检查验证**

运行：`cd openlist-web && npx vue-tsc --noEmit`
预期：无报错。

- [ ] **步骤 3：Commit**

```bash
git add openlist-web/src/views/openlist/ptStatsDashboard/index.vue
git commit -m "feat: 新增PT统计仪表盘页面"
```

---

### 任务 12：端到端验证

**文件：** 无新增/修改，仅验证。

- [ ] **步骤 1：跑一次完整后端单元测试**

运行：`mvn -f "ruoyi-openliststrm/pom.xml" -am test -Dtest=PtStatsServiceTest,PtStatsRestControllerTest -q`
预期：全部通过（`PtStatsServiceTest` 6 个用例 + `PtStatsRestControllerTest` 9 个用例）。

- [ ] **步骤 2：跑一次完整前端构建（含类型检查）**

运行：`cd openlist-web && npm run build`
预期：构建成功，无 `vue-tsc` 类型错误。

- [ ] **步骤 3：前端 lint**

运行：`cd openlist-web && npm run lint`
预期：无报错（自动修复后无残留问题）。

- [ ] **步骤 4：后端启动验证**

```bash
mvn clean package -DskipTests
docker compose up -d --build --no-deps backend
docker compose up -d --build --no-deps frontend
```

```bash
docker ps --filter "name=osr-backend" --format "{{.Names}}: {{.Status}}"
```

预期：`Up`，无 `Restarting`。

- [ ] **步骤 5：人工登录检查页面**

1. 打开前端页面，登录后在侧边栏"PT下载管理"分组下确认新出现"PT统计仪表盘"菜单项（排在"PT下载记录"之后），图标是柱状图样式（`TrendCharts`），跟同组其余图标不重复
2. 点击进入页面，确认：
   - 5 张统计卡片正常显示数值（如果这套部署从没跑过 PT 订阅，卡片应显示 0/-- 而不是报错或空白）
   - 下载量趋势折线图、索引器命中率条形图、失败原因饼图、Top 活跃订阅表格，四个区域都能正常渲染或显示"暂无数据"（不应该有整页崩溃/白屏）
   - 顶部"近7天/近30天/近90天"切换后，趋势图/失败原因图/Top活跃订阅表格会重新加载；统计卡片和索引器命中率图不随之变化
   - 点击"刷新"按钮，5 个区域都重新拉取一次
3. 打开浏览器开发者工具 Network 面板，确认 5 个请求分别打到 `/api/openliststrm/pt-stats/overview`、`/trend`、`/indexer-hit-rate`、`/fail-reasons`、`/top-subscriptions`，且都是并行发出（不是排队等待前一个完成）

- [ ] **步骤 6：确认无遗留问题后，本任务计划执行完成**

无需额外 commit（前面每个任务已各自提交）。
