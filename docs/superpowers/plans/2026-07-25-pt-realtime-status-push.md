# 订阅与下载记录实时状态推送 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 后端在下载记录三个状态推进点（下载中/完成/失败）与订阅命中时间更新点各推一条 WebSocket 消息，前端订阅列表页与下载记录页收到后原地patch本地列表数据，不用整页刷新。
**架构：** 新增 `PtStatusWebSocket`（`@ServerEndpoint("/websocket/pt/status")` + `@Component`，复用 `WebSocketConfig` 现有的 `ServerEndpointExporter`），提供两个无 Spring 依赖的静态广播方法，由 `DownloadTrackService`/`SubscriptionEngine` 在写库成功后各自直调；前端新增 `usePtStatusSocket` composable 封装连接生命周期，被 `usePtSubscription`/`usePtDownloadRecord` 引入并注册回调原地更新 `taskList` 里的行。
**技术栈：** Java 25 preview + Spring Boot（`jakarta.websocket` + `spring-boot-starter-websocket`，来自 `ruoyi-framework` 依赖传递）+ FastJSON2（后端）；Vue 3 `<script setup>` composable + 原生 `WebSocket`（前端）；JUnit 5 + Mockito `MockedStatic`（后端单测）；Vitest + `@vue/test-utils`（前端单测）。

---

## 前置说明（写计划前已确认的事实，直接影响下面的任务）

- **本计划是"前端轨道 C→B→A"里的 A，是本系列最后一个任务**，依赖 C（`docs/superpowers/plans/2026-07-24-pt-style-polish.md`）与 B（`docs/superpowers/plans/2026-07-24-pt-list-interaction-optimization.md`）均已合入。已完整重读以下文件的**当前真实内容**（设计文档 `docs/superpowers/specs/2026-07-24-pt-realtime-status-push-design.md` 里提到的行号/方法签名如与下方不一致，一律以本计划重新核实过的内容为准）：
  - `openlist-web/src/views/openlist/ptSubscription/index.vue`（PC 端，当前 731 行）与 `openlist-web/src/views-mobile/ptSubscription/index.vue`（移动端）：两者都调用同一个 `usePtSubscription()`，`item.lastMatchTime` 已在模板里直接绑定（PC 端第 122 行 `<span class="value">{{ item.lastMatchTime || '-' }}</span>`），本计划**不改动这两个 `.vue` 文件**——原地改 `taskList` 里对象字段即可被 Vue 响应式驱动视图更新。
  - `openlist-web/src/views/openlist/ptDownloadRecord/index.vue`（PC 端，当前 395 行）与 `openlist-web/src/views-mobile/ptDownloadRecord/index.vue`（移动端）：两者都调用同一个 `usePtDownloadRecord()`，`item.state`/`item.progress`/`item.failReason` 已在模板里直接绑定（PC 端第 76/83-85/109-112 行），本计划**不改动这两个 `.vue` 文件**。
  - `openlist-web/src/composables/usePtSubscription.ts`（当前 433 行，导出对象里含 `...base`，`base` 是 `useTaskList<PtSubscriptionQuery>({...})` 的返回值，`base.taskList` 是 `ref<any[]>`）。
  - `openlist-web/src/composables/usePtDownloadRecord.ts`（当前 128 行，**不复用** `useTaskList`，自己维护 `taskList`/`loading`/`total`/`queryParams`，`taskList` 同样是 `ref<any[]>`）。
  - `openlist-web/src/composables/__tests__/usePtSubscription.spec.ts`、`usePtDownloadRecord.spec.ts`（现有测试均直接调用 `usePtSubscription()`/`usePtDownloadRecord()`，不做组件挂载）。
  - `openlist-web/src/api/request.ts`（token 存取用 `Cookies.get('token')`，来自 `js-cookie`）、`openlist-web/src/views/monitor/log/realtime.vue`（现成的 WebSocket 连接生命周期范例：token 拼在 query string、`unauthorized` 文本帧特殊处理、普通 `onclose` 3 秒后重连、`onUnmounted` 主动 `disconnect()`）。
  - `openlist-web/vitest.config.ts`（`globals: false`，不加载 `unplugin-vue-components`/`unplugin-auto-import`，只认 `src/**/*.spec.ts`）。
  - 后端 `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadTrackService.java`（当前 253 行）：构造器已被 D/E4 两个计划扩展为 `DownloadTrackService(IPtDownloadRecordPlusService, IPtSubscriptionEpisodePlusService, DownloadCompletionSyncTrigger, IPtSubscriptionPlusService, int maxConsecutiveFailures, int zombieTimeoutHoursDefault)`；`fail()` 方法签名已变为 `fail(PtDownloadRecordPlus record, FailReasonCode code, String reason)`（不再是设计文档假设的两参数版本）；`markDownloading`/`complete`/`fail` 均为 **private** 方法，调用点在同一个类内部。
  - `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java`（当前 442 行）：构造器已扩展为 9 个参数（含 `DownloaderClientFactory`/`TorrentFilterEngine`/`SubscriptionMatcher`/`SearchLogService`），`handleGroup()` 现为包内可见（非 `private`），末尾在 `sub.setLastMatchTime(new Date()); subscriptionService.updateById(sub);` 之后紧跟 `log.info(...)`。
  - `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadTrackServiceTest.java`（当前 380 行，已导入 `MockedStatic`/`same`/`mockStatic`，供 `TgHelper` 静态桩使用，本计划复用同一套 import）与 `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngineTest.java`（当前 696 行，**尚未**导入 `MockedStatic`/`same`/`mockStatic`，本计划需要新增这三个 import）。
  - `ruoyi-admin/src/main/java/com/ruoyi/web/controller/monitor/LogWebSocket.java` 与 `ruoyi-framework/src/main/java/com/ruoyi/framework/config/WebSocketConfig.java`：`WebSocketConfig` 只注册了一个 `ServerEndpointExporter` bean，不关心具体端点，`LogWebSocket` 是"每连接一个容器实例、`@PostConstruct` 把 `@Autowired` 依赖转存成静态字段供跨实例访问"写法的现成范例——因为 `@ServerEndpoint` 注解的类由 WebSocket 容器直接反射实例化（每个连接一个新实例），**不是** Spring 管理的单例语义，容器创建的实例上 `@Autowired` 字段是 `null`；只有 Spring 自己创建的那一个 `@Component` 单例 bean 实例会跑一次 `@PostConstruct`，把注入到的依赖存进静态字段，所有实例（含容器实例）统一读静态字段。本计划新增的 `PtStatusWebSocket` 照抄这个写法。
  - `ruoyi-openliststrm/pom.xml` 未直接依赖 `spring-boot-starter-websocket`，但通过 `ruoyi-framework`（`ruoyi-framework/pom.xml:59` 已声明该依赖）传递可用，`jakarta.websocket.*` 类在 `ruoyi-openliststrm` 模块编译期可直接引用，无需改任何 `pom.xml`。
  - `ruoyi-common/src/main/java/com/ruoyi/common/utils/DateUtils.java` 提供 `DateUtils.YYYY_MM_DD_HH_MM_SS`（值 `"yyyy-MM-dd HH:mm:ss"`）常量与 `DateUtils.parseDateToStr(String format, Date date)` 方法；`ruoyi-admin/src/main/resources/application.yml:51-53` 配置 `spring.jackson.date-format: yyyy-MM-dd HH:mm:ss`（时区 `GMT+8`），`docker-compose.yml` 里后端容器设了 `TZ: Asia/Shanghai`，因此用 `DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, date)` 格式化 `lastMatchTime` 与现有 REST 列表接口返回的日期格式、时区完全一致，前端不需要额外处理时区。
- **本计划不新增/不改动 `.vue` 模板文件**：所有前端改动都在 `composables/` 目录内，符合设计文档"前端不改动列表页 `.vue` 模板本身"的结论（已用上面的 grep 结果验证 `lastMatchTime`/`state`/`progress`/`failReason` 字段确实已经绑定在模板里）。
- **`PtStatusWebSocket` 不写单元测试**（设计文档第 7 节的结论，任务 1 沿用）：它是货真价实的 `jakarta.websocket` 容器管理类，`onOpen`/`onClose` 依赖真实 `Session`，脱离真实容器无法有意义地单测，与现状的 `LogWebSocket`（同样没有专属单测）一致。任务 1 的验证步骤因此是"编译通过"而非"测试通过"，这是本计划唯一一个不遵循标准 TDD 五步结构的任务，已在任务描述里说明理由。
- 所有后端命令默认在仓库根目录执行；所有前端命令默认在 `openlist-web/` 目录下执行。

---

### 任务 1：新建 `PtStatusWebSocket`（WebSocket 端点 + 静态广播方法）

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/ws/PtStatusWebSocket.java`
- 测试：无（原因见"前置说明"最后一条：容器管理类脱离真实 WebSocket 容器无法有意义地单测，与 `LogWebSocket` 现状一致；用编译校验代替测试验证）

- [ ] **步骤 1：编写实现代码**

```java
package com.ruoyi.openliststrm.pt.ws;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.utils.DateUtils;
import com.ruoyi.common.utils.JwtTokenUtil;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import jakarta.annotation.PostConstruct;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 订阅/下载记录状态实时推送 WebSocket。
 * <p>
 * 只做 token 合法性校验（与 PT 模块现有 REST 接口"登录即可用"的门槛对齐，不做细粒度权限校验），
 * 连接建立后不推历史消息——REST 列表接口已提供全量快照，这里只负责快照之后的增量变化。
 * </p>
 * <p>
 * jakarta.websocket 容器为每个连接新建一个端点类实例，不是 Spring 单例语义：
 * {@code @Autowired} 字段只在 Spring 自己创建的那个单例 bean 实例上生效，容器为每个连接
 * 创建的实例该字段是 null。因此用 {@code @PostConstruct} 把依赖转存成静态字段，所有实例
 * （包括连接实例）统一读静态字段——写法与 {@code LogWebSocket} 完全一致。
 * </p>
 * <p>
 * 广播方法 {@link #pushDownloadEvent}/{@link #pushSubscriptionEvent} 不依赖任何 Spring bean
 * （{@code SESSIONS} 是自己的静态字段，序列化用 FastJSON2 静态 API），单测环境下裸调用也不会
 * 抛异常（集合为空就是空跑一轮 for 循环）；内部对每个 session 单独 try/catch，一个连接异常
 * （网络抖动、慢客户端）只记 debug 日志并从集合摘除，不影响其余连接，更不会向上抛出影响调用方
 * （{@code DownloadTrackService}/{@code SubscriptionEngine}）的状态推进主流程。
 * </p>
 *
 * @author Jack
 */
@Slf4j
@ServerEndpoint("/websocket/pt/status")
@Component
public class PtStatusWebSocket {

    private static JwtTokenUtil jwtTokenUtil;

    @Autowired
    private JwtTokenUtil tokenUtil;

    @PostConstruct
    void init() {
        jwtTokenUtil = tokenUtil;
    }

    static {
        log.info(">>> PtStatusWebSocket class loaded, @ServerEndpoint=/websocket/pt/status");
    }

    /** 所有已连接的会话，跨实例共享（每个连接对应一个容器实例，见类注释） */
    private static final Set<Session> SESSIONS = ConcurrentHashMap.newKeySet();

    @OnOpen
    public void onOpen(Session session) {
        String token = extractToken(session.getQueryString());
        boolean tokenValid = token != null;
        if (tokenValid) {
            try {
                tokenValid = !jwtTokenUtil.isTokenExpired(token);
            } catch (Exception e) {
                tokenValid = false;
            }
        }
        if (!tokenValid) {
            try {
                session.getBasicRemote().sendText("unauthorized");
                session.close();
            } catch (Exception e) {
                log.debug("关闭 WebSocket 连接时出错", e);
            }
            log.warn("PT 状态推送 WebSocket 连接被拒绝：token 无效或已过期");
            return;
        }
        SESSIONS.add(session);
    }

    @OnClose
    public void onClose(Session session) {
        SESSIONS.remove(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        SESSIONS.remove(session);
    }

    private String extractToken(String queryString) {
        if (queryString == null || queryString.isEmpty()) {
            return null;
        }
        for (String param : queryString.split("&")) {
            int idx = param.indexOf('=');
            if (idx > 0 && "token".equals(param.substring(0, idx))) {
                return param.substring(idx + 1);
            }
        }
        return null;
    }

    /**
     * 推送下载记录状态变化。{@code progress}/{@code failReason} 按需传 null，由调用方保证：
     * DOWNLOADING 传 progress、COMPLETED 传 progress=1.0、FAILED 传 failReason，其余传 null。
     */
    public static void pushDownloadEvent(PtDownloadRecordPlus record, String state, Double progress, String failReason) {
        JSONObject json = new JSONObject();
        json.put("type", "download");
        json.put("downloadId", record.getId());
        json.put("subId", record.getSubId());
        json.put("episode", record.getEpisode());
        json.put("state", state);
        if (progress != null) {
            json.put("progress", progress);
        }
        if (failReason != null) {
            json.put("failReason", failReason);
        }
        broadcast(json.toJSONString());
    }

    /** 推送订阅命中时间变化 */
    public static void pushSubscriptionEvent(PtSubscriptionPlus sub) {
        JSONObject json = new JSONObject();
        json.put("type", "subscription");
        json.put("subId", sub.getId());
        json.put("lastMatchTime", sub.getLastMatchTime() == null
                ? null : DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, sub.getLastMatchTime()));
        broadcast(json.toJSONString());
    }

    /**
     * 遍历当前所有连接逐个发送；单个 session 发送失败（客户端已断开等）只记 debug 日志并从集合
     * 摘除，不影响其余 session 收到消息，方法本身不抛出受检异常，调用方无需包 try/catch。
     */
    private static void broadcast(String message) {
        for (Session session : SESSIONS) {
            try {
                if (session.isOpen()) {
                    session.getBasicRemote().sendText(message);
                }
            } catch (Exception e) {
                log.debug("PT 状态推送发送失败，已移除该连接：{}", e.getMessage());
                SESSIONS.remove(session);
            }
        }
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn compile -pl ruoyi-openliststrm -am -q`

预期：无输出、退出码 0（新文件的 `@ServerEndpoint`/`jakarta.websocket.*`/FastJSON2 `JSONObject`/`DateUtils` 全部能正确解析，说明依赖传递、包路径都正确）。

- [ ] **步骤 3：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/ws/PtStatusWebSocket.java
git commit -m "feat(pt-ws): 新增订阅/下载记录状态实时推送 WebSocket 端点"
```

---

### 任务 2：`DownloadTrackService` 接入状态推送（TDD）

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadTrackService.java:16`（导入区）、`:152-156`（`markDownloading`）、`:187-203`（`complete`）、`:214-252`（`fail`）
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadTrackServiceTest.java:12`（导入区）、`:30`（导入区）、`:379`（追加新用例）

- [ ] **步骤 1：编写失败的测试**

在 `DownloadTrackServiceTest.java` 第 12 行 `import com.ruoyi.openliststrm.pt.downloader.model.DownloaderTorrent;` 之后插入：

```java
import com.ruoyi.openliststrm.pt.ws.PtStatusWebSocket;
```

在第 30 行 `import static org.mockito.ArgumentMatchers.eq;` 之后插入：

```java
import static org.mockito.ArgumentMatchers.isNull;
```

在第 379 行（`订阅已删除_listByIds查不到_回退全局默认值()` 方法结束的 `}`）之后、第 380 行（类结束的 `}`）之前插入：

```java

    // ---------- WebSocket 状态推送 ----------

    @Test
    void 下载中更新_推送WebSocket下载事件() {
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "PUSHED", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 0.35)));

            ws.verify(() -> PtStatusWebSocket.pushDownloadEvent(same(r), eq("DOWNLOADING"), eq(0.35), isNull()));
        }
    }

    @Test
    void 完成后_推送WebSocket下载事件() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        PtDownloaderPlus dl = downloader();

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            service().track(dl, List.of(torrent("osr-pt,osr-pt-aaa", 1.0)));

            ws.verify(() -> PtStatusWebSocket.pushDownloadEvent(same(r), eq("COMPLETED"), eq(1.0), isNull()));
        }
    }

    @Test
    void 完成但记录已被并发置终态_不推送WebSocket事件() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(false);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class);
             MockedStatic<TgHelper> tg = mockStatic(TgHelper.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-aaa", 1.0)));

            ws.verify(() -> PtStatusWebSocket.pushDownloadEvent(any(), any(), any(), any()), never());
        }
    }

    @Test
    void 失败后_推送WebSocket下载事件() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(true);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

            ws.verify(() -> PtStatusWebSocket.pushDownloadEvent(same(r), eq("FAILED"), isNull(),
                    eq("下载器中已找不到该种子（可能被删除或元数据解析失败）")));
        }
    }

    @Test
    void 失败但记录已被并发置终态_不推送WebSocket事件() {
        when(recordService.update(any(PtDownloadRecordPlus.class), any(Wrapper.class))).thenReturn(false);
        PtDownloadRecordPlus r = record(100, 2, "osr-pt-aaa", "DOWNLOADING", 20 * 60_000);
        when(recordService.list(any(Wrapper.class))).thenReturn(List.of(r));
        when(episodeService.list(any(Wrapper.class))).thenReturn(List.of(episodeRow(500)));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            service().track(downloader(), List.of(torrent("osr-pt,osr-pt-other", 0.5)));

            ws.verify(() -> PtStatusWebSocket.pushDownloadEvent(any(), any(), any(), any()), never());
        }
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=DownloadTrackServiceTest`

预期：新增的 5 个用例全部 FAIL，报错形如 `Wanted but not invoked: PtStatusWebSocket.pushDownloadEvent(...)`（因为 `DownloadTrackService` 尚未调用该静态方法），其余已有用例保持 PASS。

- [ ] **步骤 3：编写最少实现代码**

在 `DownloadTrackService.java` 第 16 行 `import com.ruoyi.openliststrm.pt.subscription.SubscriptionEpisodeState;` 之后插入：

```java
import com.ruoyi.openliststrm.pt.ws.PtStatusWebSocket;
```

把第 152-156 行的 `markDownloading` 方法：

```java
    private void markDownloading(PtDownloadRecordPlus record, double progress) {
        record.setState(STATE_DOWNLOADING);
        record.setProgress(progress);
        recordService.updateById(record);
    }
```

替换为：

```java
    private void markDownloading(PtDownloadRecordPlus record, double progress) {
        record.setState(STATE_DOWNLOADING);
        record.setProgress(progress);
        recordService.updateById(record);
        PtStatusWebSocket.pushDownloadEvent(record, STATE_DOWNLOADING, progress, null);
    }
```

把第 195-198 行（`complete()` 内部）：

```java
        if (!changed) {
            return; // 并发/重叠轮询已处理过，避免重复通知
        }
        notifySafely("✅ 下载完成：" + record.getTitle());
```

替换为：

```java
        if (!changed) {
            return; // 并发/重叠轮询已处理过，避免重复通知
        }
        PtStatusWebSocket.pushDownloadEvent(record, STATE_COMPLETED, 1.0, null);
        notifySafely("✅ 下载完成：" + record.getTitle());
```

把第 243-246 行（`fail()` 内部）：

```java
        if (!changed) {
            return; // 已被并发轮次置为终态，避免重复通知
        }
        notifySafely("❌ 下载失败：" + record.getTitle() + "，已释放待下轮重新匹配");
```

替换为：

```java
        if (!changed) {
            return; // 已被并发轮次置为终态，避免重复通知
        }
        PtStatusWebSocket.pushDownloadEvent(record, STATE_FAILED, null, reason);
        notifySafely("❌ 下载失败：" + record.getTitle() + "，已释放待下轮重新匹配");
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=DownloadTrackServiceTest`

预期：`BUILD SUCCESS`，全部用例（含新增 5 个）PASS。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/task/DownloadTrackService.java
git add ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadTrackServiceTest.java
git commit -m "feat(pt-ws): DownloadTrackService 三个状态推进点接入 WebSocket 实时推送"
```

---

### 任务 3：`SubscriptionEngine` 接入状态推送（TDD）

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java:22`（导入区）、`:245-249`（`handleGroup` 末尾）
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngineTest.java:17`、`:23`、`:39`（导入区）、`:695`（追加新用例）

- [ ] **步骤 1：编写失败的测试**

在 `SubscriptionEngineTest.java` 第 17 行 `import com.ruoyi.openliststrm.pt.model.TorrentInfo;` 之后插入：

```java
import com.ruoyi.openliststrm.pt.ws.PtStatusWebSocket;
```

在第 23 行 `import org.mockito.Mock;` 之后插入：

```java
import org.mockito.MockedStatic;
```

在第 39 行 `import static org.mockito.ArgumentMatchers.contains;` 之后插入：

```java
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mockStatic;
```

在第 695 行（`推送失败_记录摘要日志()` 方法结束的 `}`）之后、第 696 行（类结束的 `}`）之前插入：

```java

    // ---------- WebSocket 状态推送 ----------

    @Test
    void 推送成功后_推送WebSocket订阅事件() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);
        when(subscriptionService.listActive()).thenReturn(List.of(sub));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

            ws.verify(() -> PtStatusWebSocket.pushSubscriptionEvent(same(sub)));
        }
    }

    @Test
    void pushBest成功_也推送WebSocket订阅事件() throws Exception {
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 3);
        when(episodeService.listBySubscription(10)).thenReturn(List.of(
                episode(101, 1, "MISSING"), episode(102, 2, "MISSING"), episode(103, 3, "MISSING")));

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            boolean pushed = engine.pushBest(sub, 2, List.of(torrent("Some.Show.S01E02.1080p", "g1", 10, "1080p")));

            assertTrue(pushed);
            ws.verify(() -> PtStatusWebSocket.pushSubscriptionEvent(same(sub)));
        }
    }

    @Test
    void 推送失败回滚_不推送WebSocket订阅事件() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        when(recordService.save(any())).thenAnswer(inv -> {
            ((PtDownloadRecordPlus) inv.getArgument(0)).setId(999);
            return true;
        });
        org.mockito.Mockito.doThrow(new IOException("qb down"))
                .when(downloaderClient).addTorrent(any(), anyString(), anyString(), anyString());

        try (MockedStatic<PtStatusWebSocket> ws = mockStatic(PtStatusWebSocket.class)) {
            engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

            ws.verify(() -> PtStatusWebSocket.pushSubscriptionEvent(any()), never());
        }
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=SubscriptionEngineTest`

预期：新增的 3 个用例中，`推送成功后_推送WebSocket订阅事件`、`pushBest成功_也推送WebSocket订阅事件` FAIL（`Wanted but not invoked: PtStatusWebSocket.pushSubscriptionEvent(...)`）；`推送失败回滚_不推送WebSocket订阅事件` 本身会 PASS（因为此时 `handleGroup` 还完全不会调用 `pushSubscriptionEvent`，"从不调用"这个断言天然成立），但前两个的失败足以驱动后续实现，其余已有用例保持 PASS。

- [ ] **步骤 3：编写最少实现代码**

在 `SubscriptionEngine.java` 第 22 行 `import com.ruoyi.openliststrm.pt.task.DownloadRecordState;` 之后插入：

```java
import com.ruoyi.openliststrm.pt.ws.PtStatusWebSocket;
```

把第 245-249 行（`handleGroup` 末尾）：

```java
        sub.setLastMatchTime(new Date());
        subscriptionService.updateById(sub);

        log.info("订阅[{}] {} 已推送种子：{}（占位 {} 集）",
                sub.getId(), sub.getTitle(), best.getTitle(), claimed.size());
```

替换为：

```java
        sub.setLastMatchTime(new Date());
        subscriptionService.updateById(sub);
        PtStatusWebSocket.pushSubscriptionEvent(sub);

        log.info("订阅[{}] {} 已推送种子：{}（占位 {} 集）",
                sub.getId(), sub.getTitle(), best.getTitle(), claimed.size());
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=SubscriptionEngineTest`

预期：`BUILD SUCCESS`，全部用例（含新增 3 个）PASS。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java
git add ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngineTest.java
git commit -m "feat(pt-ws): SubscriptionEngine 推送成功后接入 WebSocket 订阅命中事件"
```

---

### 任务 4：新建前端 `usePtStatusSocket` composable（TDD）

**文件：**
- 创建：`openlist-web/src/composables/usePtStatusSocket.ts`
- 测试：创建 `openlist-web/src/composables/__tests__/usePtStatusSocket.spec.ts`

- [ ] **步骤 1：编写失败的测试**

```typescript
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { ElMessage } from 'element-plus'

vi.mock('js-cookie', () => ({
  default: { get: vi.fn() }
}))

vi.mock('@/stores/user', () => ({
  useUserStore: vi.fn()
}))

import Cookies from 'js-cookie'
import { useUserStore } from '@/stores/user'
import { usePtStatusSocket } from '../usePtStatusSocket'

/** 捕获所有实例，测试里按需手动触发 onmessage/onclose（jsdom 不会真的发起网络连接） */
class MockWebSocket {
  static instances: MockWebSocket[] = []
  url: string
  onmessage: ((event: { data: string }) => void) | null = null
  onclose: (() => void) | null = null
  closed = false

  constructor(url: string) {
    this.url = url
    MockWebSocket.instances.push(this)
  }

  close() {
    this.closed = true
  }
}

describe('usePtStatusSocket', () => {
  let clearToken: any
  let errorSpy: any

  beforeEach(() => {
    vi.useFakeTimers()
    MockWebSocket.instances = []
    ;(globalThis as any).WebSocket = MockWebSocket
    ;(Cookies.get as any).mockReturnValue('test-token')
    clearToken = vi.fn()
    ;(useUserStore as any).mockReturnValue({ clearToken })
    errorSpy = vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as any)
    delete (window as any).location
    ;(window as any).location = { protocol: 'http:', host: 'localhost', href: '' }
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('connect() 建立连接，url 带上 token 且路径不带 /api 前缀', () => {
    const { connect, disconnect } = usePtStatusSocket({})
    connect()

    expect(MockWebSocket.instances.length).toBe(1)
    expect(MockWebSocket.instances[0].url).toBe('ws://localhost/websocket/pt/status?token=test-token')
    disconnect()
  })

  it('收到 download 消息时分发给 onDownload 回调', () => {
    const onDownload = vi.fn()
    const { connect, disconnect } = usePtStatusSocket({ onDownload })
    connect()

    MockWebSocket.instances[0].onmessage?.({
      data: JSON.stringify({ type: 'download', downloadId: 1, subId: 2, episode: 3, state: 'DOWNLOADING', progress: 0.5 })
    })

    expect(onDownload).toHaveBeenCalledWith({ type: 'download', downloadId: 1, subId: 2, episode: 3, state: 'DOWNLOADING', progress: 0.5 })
    disconnect()
  })

  it('收到 subscription 消息时分发给 onSubscription 回调', () => {
    const onSubscription = vi.fn()
    const { connect, disconnect } = usePtStatusSocket({ onSubscription })
    connect()

    MockWebSocket.instances[0].onmessage?.({
      data: JSON.stringify({ type: 'subscription', subId: 2, lastMatchTime: '2026-07-24 15:30:00' })
    })

    expect(onSubscription).toHaveBeenCalledWith({ type: 'subscription', subId: 2, lastMatchTime: '2026-07-24 15:30:00' })
    disconnect()
  })

  it('收到 unauthorized 时清 token、跳登录页且不再自动重连', () => {
    const { connect } = usePtStatusSocket({})
    connect()
    const socket = MockWebSocket.instances[0]

    socket.onmessage?.({ data: 'unauthorized' })

    expect(clearToken).toHaveBeenCalled()
    expect(errorSpy).toHaveBeenCalled()
    expect(window.location.href).toBe('/login')

    socket.onclose?.()
    vi.advanceTimersByTime(5000)
    expect(MockWebSocket.instances.length).toBe(1)
  })

  it('普通断线 3 秒后自动重连', () => {
    const { connect, disconnect } = usePtStatusSocket({})
    connect()
    expect(MockWebSocket.instances.length).toBe(1)

    MockWebSocket.instances[0].onclose?.()
    expect(MockWebSocket.instances.length).toBe(1)

    vi.advanceTimersByTime(3000)
    expect(MockWebSocket.instances.length).toBe(2)
    disconnect()
  })

  it('disconnect() 关闭连接并清理重连定时器，之后不会再自动重连', () => {
    const { connect, disconnect } = usePtStatusSocket({})
    connect()
    const socket = MockWebSocket.instances[0]

    disconnect()

    expect(socket.closed).toBe(true)
    vi.advanceTimersByTime(5000)
    expect(MockWebSocket.instances.length).toBe(1)
  })

  it('组件挂载时自动 connect，卸载时自动 disconnect', () => {
    const TestComponent = defineComponent({
      setup() {
        usePtStatusSocket({})
        return () => h('div')
      }
    })
    const wrapper = mount(TestComponent)

    expect(MockWebSocket.instances.length).toBe(1)
    const socket = MockWebSocket.instances[0]

    wrapper.unmount()

    expect(socket.closed).toBe(true)
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtStatusSocket.spec.ts`

预期：FAIL，报错 `Failed to resolve import "../usePtStatusSocket"`（文件尚不存在）。

- [ ] **步骤 3：编写最少实现代码**

```typescript
import { onMounted, onUnmounted } from 'vue'
import Cookies from 'js-cookie'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/** 下载记录状态推送事件：DownloadTrackService 的 markDownloading/complete/fail 三个状态推进点各推一条 */
export interface PtDownloadStatusEvent {
  type: 'download'
  downloadId: number
  subId: number
  episode: number
  state: string
  progress?: number
  failReason?: string
}

/** 订阅命中时间推送事件：SubscriptionEngine.handleGroup 推送成功后追加一条 */
export interface PtSubscriptionStatusEvent {
  type: 'subscription'
  subId: number
  lastMatchTime: string
}

export interface PtStatusSocketHandlers {
  onDownload?: (event: PtDownloadStatusEvent) => void
  onSubscription?: (event: PtSubscriptionStatusEvent) => void
}

/**
 * PT 订阅/下载记录实时状态推送：封装 WebSocket 连接生命周期，写法与
 * `views/monitor/log/realtime.vue` 的 connectWebSocket 一致——token 鉴权失败（收到
 * "unauthorized" 文本帧）不重连，普通断线 3 秒后自动重连。
 *
 * 默认在组件 onMounted 时自动连接、onUnmounted 时自动断开；若调用方本身不是在组件
 * setup() 中直接使用（onMounted 不会生效），可自行在合适的生命周期钩子里调用返回的
 * connect()/disconnect()。
 */
export function usePtStatusSocket(handlers: PtStatusSocketHandlers) {
  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let unauthorized = false

  function connect() {
    if (typeof WebSocket === 'undefined') return
    const protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://'
    const host = window.location.host
    const token = Cookies.get('token') || ''
    const url = `${protocol}${host}/websocket/pt/status${token ? `?token=${token}` : ''}`

    ws = new WebSocket(url)

    ws.onmessage = (event: MessageEvent) => {
      if (event.data === 'unauthorized') {
        unauthorized = true
        const userStore = useUserStore()
        userStore.clearToken()
        ElMessage.error('登录已过期，请重新登录')
        window.location.href = '/login'
        return
      }
      let data: any
      try {
        data = JSON.parse(event.data)
      } catch (e) {
        console.error('解析 PT 状态推送消息失败', e)
        return
      }
      if (data.type === 'download') {
        handlers.onDownload?.(data)
      } else if (data.type === 'subscription') {
        handlers.onSubscription?.(data)
      }
    }

    ws.onclose = () => {
      if (unauthorized) return
      reconnectTimer = setTimeout(() => {
        connect()
      }, 3000)
    }
  }

  function disconnect() {
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (ws) {
      ws.onclose = null
      ws.close()
      ws = null
    }
  }

  onMounted(() => {
    connect()
  })

  onUnmounted(() => {
    disconnect()
  })

  return { connect, disconnect }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtStatusSocket.spec.ts`

预期：`Test Files 1 passed`，7 个用例全部 PASS。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/composables/usePtStatusSocket.ts
git add openlist-web/src/composables/__tests__/usePtStatusSocket.spec.ts
git commit -m "feat(pt-ws): 新增前端 usePtStatusSocket composable 封装 WebSocket 连接生命周期"
```

---

### 任务 5：`usePtSubscription.ts` 接入订阅命中时间实时更新（TDD）

**文件：**
- 修改：`openlist-web/src/composables/usePtSubscription.ts:20`（导入区）、`:44`（`useTaskList` 调用之后插入回调注册）
- 测试：`openlist-web/src/composables/__tests__/usePtSubscription.spec.ts`（追加新 `describe` 块）

- [ ] **步骤 1：编写失败的测试**

在 `usePtSubscription.spec.ts` 顶部（第 21 行 `import { usePtSubscription } from '../usePtSubscription'` 之前）追加 mock：

```typescript
vi.mock('../usePtStatusSocket', () => ({
  usePtStatusSocket: vi.fn()
}))
```

紧接着的 import 区（第 24-28 行现有 import 之后）追加：

```typescript
import { usePtStatusSocket } from '../usePtStatusSocket'
```

在文件末尾（现有最后一个 `describe` 块结束的 `}` 之后，即当前文件第 94 行 `})` 之后）追加：

```typescript

describe('usePtSubscription 实时状态推送', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
    ;(usePtStatusSocket as any).mockReturnValue({ connect: vi.fn(), disconnect: vi.fn() })
  })

  it('收到 subscription 事件后原地更新对应行的 lastMatchTime，不重新整页拉取', () => {
    const composable = usePtSubscription()
    composable.taskList.value = [
      { id: 1, lastMatchTime: null },
      { id: 2, lastMatchTime: '2026-01-01 00:00:00' }
    ]

    const handlers = (usePtStatusSocket as any).mock.calls[0][0]
    handlers.onSubscription({ type: 'subscription', subId: 1, lastMatchTime: '2026-07-24 15:30:00' })

    expect(composable.taskList.value[0].lastMatchTime).toBe('2026-07-24 15:30:00')
    expect(composable.taskList.value[1].lastMatchTime).toBe('2026-01-01 00:00:00')
  })

  it('找不到对应行时静默忽略，不抛异常', () => {
    const composable = usePtSubscription()
    composable.taskList.value = [{ id: 1, lastMatchTime: null }]

    const handlers = (usePtStatusSocket as any).mock.calls[0][0]
    expect(() => handlers.onSubscription({ type: 'subscription', subId: 999, lastMatchTime: '2026-07-24 15:30:00' })).not.toThrow()
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtSubscription.spec.ts`

预期：新增 2 个用例 FAIL，报错 `Cannot read properties of undefined (reading '0')`（`(usePtStatusSocket as any).mock.calls[0]` 为空，因为 `usePtSubscription.ts` 尚未调用 `usePtStatusSocket`），其余已有用例保持 PASS。

- [ ] **步骤 3：编写最少实现代码**

在 `usePtSubscription.ts` 第 3 行 `import { useTaskList } from './useTaskList'` 之后插入：

```typescript
import { usePtStatusSocket } from './usePtStatusSocket'
```

在第 44 行（`useTaskList<PtSubscriptionQuery>({...})` 调用结束的 `})`）之后、第 46 行（`// ---------- 建订阅向导 ----------`）之前插入：

```typescript

  // ---------- 实时状态推送：订阅命中时间原地更新，不用整页刷新 ----------
  usePtStatusSocket({
    onSubscription: (event) => {
      const row = base.taskList.value.find((item: any) => item.id === event.subId)
      if (row) {
        Object.assign(row, { lastMatchTime: event.lastMatchTime })
      }
    }
  })
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtSubscription.spec.ts`

预期：`Test Files 1 passed`，全部用例（含新增 2 个）PASS。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/composables/usePtSubscription.ts
git add openlist-web/src/composables/__tests__/usePtSubscription.spec.ts
git commit -m "feat(pt-ws): usePtSubscription 接入订阅命中时间实时推送"
```

---

### 任务 6：`usePtDownloadRecord.ts` 接入下载状态实时更新（TDD）

**文件：**
- 修改：`openlist-web/src/composables/usePtDownloadRecord.ts:4`（导入区）、`:50`（`resetQuery` 之后插入回调注册）
- 测试：`openlist-web/src/composables/__tests__/usePtDownloadRecord.spec.ts`（追加新 `describe` 块）

- [ ] **步骤 1：编写失败的测试**

在 `usePtDownloadRecord.spec.ts` 顶部（第 6-8 行 `vi.mock('vue-router', ...)` 之后）追加 mock：

```typescript
vi.mock('../usePtStatusSocket', () => ({
  usePtStatusSocket: vi.fn()
}))
```

紧接着现有 import 区（第 17-18 行）之后追加：

```typescript
import { usePtStatusSocket } from '../usePtStatusSocket'
```

在文件末尾（现有最后一个 `describe` 块结束的 `}` 之后，即当前文件第 75 行 `})` 之后）追加：

```typescript

describe('usePtDownloadRecord 实时状态推送', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
    ;(usePtStatusSocket as any).mockReturnValue({ connect: vi.fn(), disconnect: vi.fn() })
  })

  it('收到 download 事件后原地更新对应记录的状态/进度/失败原因', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'PUSHED', progress: null, failReason: null }]

    const handlers = (usePtStatusSocket as any).mock.calls[0][0]
    handlers.onDownload({ type: 'download', downloadId: 1, subId: 5, episode: 1, state: 'DOWNLOADING', progress: 0.6 })

    expect(composable.taskList.value[0].state).toBe('DOWNLOADING')
    expect(composable.taskList.value[0].progress).toBe(0.6)
  })

  it('找不到对应记录时静默忽略，不抛异常', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'PUSHED' }]

    const handlers = (usePtStatusSocket as any).mock.calls[0][0]
    expect(() => handlers.onDownload({ type: 'download', downloadId: 999, subId: 5, episode: 1, state: 'FAILED', failReason: '超时' })).not.toThrow()
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtDownloadRecord.spec.ts`

预期：新增 2 个用例 FAIL，报错 `Cannot read properties of undefined (reading '0')`（`usePtDownloadRecord.ts` 尚未调用 `usePtStatusSocket`），其余已有用例保持 PASS。

- [ ] **步骤 3：编写最少实现代码**

在 `usePtDownloadRecord.ts` 第 4 行 `import { getPtDownloadRecordListApi, ... } from '@/api/openlist/ptDownloadRecord'` 之后插入：

```typescript
import { usePtStatusSocket } from './usePtStatusSocket'
```

在第 50 行（`resetQuery` 方法结束的 `}`）之后、第 52 行（`// ---------- 重试 ----------`）之前插入：

```typescript

  // ---------- 实时状态推送：状态/进度原地更新，不用整页刷新 ----------
  usePtStatusSocket({
    onDownload: (event) => {
      const row = taskList.value.find((item: any) => item.id === event.downloadId)
      if (row) {
        Object.assign(row, { state: event.state, progress: event.progress, failReason: event.failReason })
      }
    }
  })
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/composables/__tests__/usePtDownloadRecord.spec.ts`

预期：`Test Files 1 passed`，全部用例（含新增 2 个）PASS。

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/composables/usePtDownloadRecord.ts
git add openlist-web/src/composables/__tests__/usePtDownloadRecord.spec.ts
git commit -m "feat(pt-ws): usePtDownloadRecord 接入下载状态/进度实时推送"
```

---

### 任务 7：整体回归验证与手动联调（无新代码变更）

**文件：** 无新增/修改文件，本任务只做验证，不改动实现代码。

**背景**：任务 1-6 各自的单测只覆盖各自新增的结构性/业务逻辑断言，不能替代"后端 WebSocket 端点真的能启动并接受连接""真实浏览器里 WS 帧能收到、页面确实原地刷新"的确认。这一步对齐设计文档第 7 节"测试计划"最后一条手工联调要求，也是 `AGENTS.md` 要求的"新增 `@Component`/端点后必须做启动验证"。

- [ ] **步骤 1：跑后端全量单元测试**

运行：`mvn test -pl ruoyi-openliststrm -am`

预期：`BUILD SUCCESS`，其中 `DownloadTrackServiceTest`（19 个既有用例 + 任务 2 新增 5 个 = 24 用例）、`SubscriptionEngineTest`（34 个既有用例 + 任务 3 新增 3 个 = 37 用例）全部通过。

- [ ] **步骤 2：后端完整打包**

运行：`mvn clean package -DskipTests`

预期：`BUILD SUCCESS`，生成 `ruoyi-admin/target/ruoyi-admin.jar`。

- [ ] **步骤 3：跑前端全量单元测试**

运行：`cd openlist-web && npm run test:unit`

预期：全部 Test Files/Tests 通过，其中应包含本计划新增/修改的 3 个测试文件：`usePtStatusSocket.spec.ts`（7 用例）、`usePtSubscription.spec.ts`（新增 2 用例）、`usePtDownloadRecord.spec.ts`（新增 2 用例）。

- [ ] **步骤 4：跑前端类型检查与完整构建**

运行：`cd openlist-web && npm run build`

预期：退出码 0，输出以 `✓ built in` 结尾（`vue-tsc` 无类型错误）。

- [ ] **步骤 5：跑前端 ESLint**

运行：`cd openlist-web && npm run lint`

预期：退出码 0，无残留错误。

- [ ] **步骤 6：容器化启动验证（新增 `@ServerEndpoint` 必须做）**

运行：`docker compose up -d --build --no-deps backend frontend`

等待约 30 秒后运行：`docker ps --filter "name=osr-backend" --format "{{.Names}}\t{{.Status}}"`

预期：状态里 `restarts` 计数为 0（若反复重启，按 `AGENTS.md` 说明 `docker cp osr-backend:/data/logs ./tmp` 后看 `sys-error.log` 排查；`PtStatusWebSocket` 的 `@Autowired JwtTokenUtil` 若装配失败，会在启动日志里体现为 bean 创建异常）。

- [ ] **步骤 7：手动联调——订阅列表页实时命中时间**

后端确认启动成功后，浏览器打开订阅列表页，同时打开 Chrome DevTools 的 Network 面板并筛选 WS（WebSocket），确认：

- 能看到一条到 `wss://<host>/websocket/pt/status?token=...`（或 `ws://`，取决于是否 HTTPS）的连接，状态为 `101 Switching Protocols`。
- 手动触发一次 RSS 轮询或对某个订阅执行"搜索补齐"使其命中一个种子后，Network 面板的 WS 帧列表里应出现一条 `{"type":"subscription","subId":...,"lastMatchTime":"..."}` 消息，且页面上对应订阅卡片的"上次命中"字段在不刷新整页的情况下自动更新为新时间。

- [ ] **步骤 8：手动联调——下载记录页实时状态/进度**

浏览器打开下载记录页，同时观察 WS 帧：

- 等待下一轮 `DownloadTrackTask` 轮询（或手动推送一个种子到下载器触发状态变化），确认收到 `{"type":"download","downloadId":...,"state":"DOWNLOADING","progress":...}` 消息，且对应记录卡片的进度条在不刷新整页的情况下同步变化。
- 让一条记录走到完成或失败分支，确认收到对应的 `COMPLETED`/`FAILED` 消息，卡片状态标签与进度条/失败原因同步更新。
- 手动断开网络或重启后端容器模拟断线，确认浏览器 Network 面板里旧连接关闭后，约 3 秒内会看到一条新的到 `/websocket/pt/status` 的连接请求（自动重连）。

---

## 自检记录（写计划时已完成，问题已直接改正，无需再走一遍审查）

- **规格覆盖度**：设计文档第 4 节"后端组件改动清单"（`PtStatusWebSocket` 新建、`DownloadTrackService` 三处改动、`SubscriptionEngine` 一处改动）→ 任务 1、2、3；第 5 节"API"（WebSocket 端点路径、鉴权、消息格式）→ 任务 1 的 `PtStatusWebSocket` 实现（`extractToken`/`onOpen` 鉴权逻辑、`pushDownloadEvent`/`pushSubscriptionEvent` 的 JSON 字段与设计文档 5.2 节示例逐字段对应）；第 6 节"前端改动"（`usePtStatusSocket` 新建、`usePtSubscription`/`usePtDownloadRecord` 接入）→ 任务 4、5、6；第 7 节"测试计划"（`DownloadTrackServiceTest`/`SubscriptionEngineTest` 补充用例、`PtStatusWebSocket` 不写单测、`usePtStatusSocket.ts` 前端用例、手工联调）→ 任务 2、3、1（说明理由）、4、7 逐条覆盖；第 8 节"不做的事情"未新增任何相关任务，符合范围限定（未引入通用消息总线、未推送进度详情/匹配日志弹窗、未做按 subId 服务端过滤、未做消息重放、未做心跳保活）。
- **占位符扫描**：全文档没有"待定/TODO/后续实现/补充细节/添加适当的错误处理/类似任务N"等模式，每个代码步骤都给了完整可运行代码块；唯一偏离标准 TDD 五步结构的任务 1（`PtStatusWebSocket` 无单测）已在任务描述与"前置说明"里明确写出理由（容器管理类脱离真实 WebSocket 容器无法有意义地单测，与现状 `LogWebSocket` 一致），并用"编译验证"替代"运行测试"作为可执行的验证步骤，不是含糊的占位符。
- **类型/方法名一致性**：`PtStatusWebSocket.pushDownloadEvent(PtDownloadRecordPlus, String, Double, String)`、`PtStatusWebSocket.pushSubscriptionEvent(PtSubscriptionPlus)`、`PtDownloadStatusEvent{type,downloadId,subId,episode,state,progress?,failReason?}`、`PtSubscriptionStatusEvent{type,subId,lastMatchTime}`、`PtStatusSocketHandlers{onDownload?,onSubscription?}`、`usePtStatusSocket(handlers)` 返回 `{connect,disconnect}` 在定义任务（1、4）与后续使用任务（2、3、5、6）里前后一致，已核对无漂移；`DownloadRecordState.DOWNLOADING/COMPLETED/FAILED` 与 `FailReasonCode` 沿用 `DownloadTrackService.java` 现有的 `STATE_DOWNLOADING`/`STATE_COMPLETED`/`STATE_FAILED` 常量，未重复定义新常量。
- **与设计文档的偏差已核实并按真实代码修正**：设计文档假设 `DownloadTrackService.fail()` 签名为两参数（`record, reason`），真实签名是三参数 `fail(PtDownloadRecordPlus record, FailReasonCode code, String reason)`（`code` 用于 `fail_reason_code`，与推送无关，`reason` 才是推送的 `failReason` 字段来源），任务 2 的实现代码按真实签名在 `reason` 可见的插入点（`if (!changed) return;` 之后）追加推送调用；`SubscriptionEngine`/`DownloadTrackService` 的构造器参数个数、`handleGroup`/`markDownloading`/`complete`/`fail` 的可见性与函数体，均以本计划"前置说明"重新读取到的当前真实内容为准，不依赖设计文档里可能过时的行号描述。
