# 通知渠道抽象 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 把"发一条通知"从"发一条 Telegram 消息"中解耦出来，新增 `INotifier` 接口 + `TgNotifier`/`WebhookNotifier` 两个实现 + `NotifierManager` 分发器，`TgHelper` 降级为委托门面，且不改动任何现有调用点和测试断言。

**架构：** 新增包 `com.ruoyi.openliststrm.notify`：`INotifier`（接口，`void send(String message)`）、`TgNotifier`/`WebhookNotifier`（`@Component`，各自内部判空 no-op、吞异常只记 warn）、`NotifierManager`（`@Component`，构造注入 `List<INotifier>`，逐个 try/catch 分发）。`TgHelper.sendMsg(msg)` 方法体改为 `SpringUtils.getBean(NotifierManager.class).send(msg)`，类名、方法签名不变。

**技术栈：** Spring Bean 自动收集（`List<INotifier>` 构造注入）、OkHttp（复用 `HttpClientConfig.sharedOkHttpClient` 共享连接池）、FastJSON2、JUnit 5 + Mockito + MockWebServer。

---

## 背景与关键事实核对（写计划前已用 Read/Grep 核实，非按设计文档摘要臆测）

1. **`TgHelper.java` 现状**（`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/helper/TgHelper.java`，共 46 行）：纯静态类，`sendMsg(String msg)` 内部 `SpringUtils.getBean(OpenlistConfig.class)` 读 token/userId，`getBot()` 用 `static volatile` 字段缓存 `TgSendMsg` 实例，token/userId 变化时重建。

2. **`mockStatic(TgHelper.class)` 实测覆盖范围比设计文档记录的更广**：设计文档 2.2 节称"8 处 `mockStatic` 断言"（`DownloadTrackServiceTest` 3 处 + `SearchSupplementServiceTest` 5 处），并称 `RssPollServiceTest`"虽未见 `mockStatic` 用法"。**实测结果不同**：`RssPollServiceTest.java` 里也有 **6 处** `mockStatic(TgHelper.class)` + `tg.verify(() -> TgHelper.sendMsg(...))`（第 162/174/188/216/241/259 行）。三个文件加起来是 **14 处**断言，而不是 8 处。这不改变技术方案（`TgHelper.sendMsg(String)` 签名和类名本来就不变），但意味着 `RssPollServiceTest` 和另外两个文件一样是**硬约束**，回归验证时必须把它也当作强制断言的测试文件对待，而不是"顺带跑一遍"的辅助验证。

3. **`OpenlistConfig.java`**（197 行）：`@Component`，字段注入 `ISysConfigService sysConfigService`（不是构造注入）。`getOpenListTgToken()`/`getOpenListTgUserId()` 分别在第 42-44 行、47-49 行，读 `sys_config` 键 `openlist.tg.token`/`openlist.tg.userid`。仓库里**没有 `OpenlistConfigTest`**，其余 15 个 getter 均无对应单测——这是既有约定，本计划新增的 `getNotifyWebhookUrl()` 遵循同样约定，不单独写测试（消费侧的正确性由 `WebhookNotifierTest` 用 mock 的 `OpenlistConfig` 覆盖，端到端装配由最后的 Docker 启动验证覆盖）。

4. **`EmbyClient.java`/`QbittorrentClient.java` 是"构造注入 `OpenlistConfig` + 共享 `OkHttpClient`"的标准范本**：构造函数 `public EmbyClient(OkHttpClient sharedOkHttpClient)`，测试里直接 `new EmbyClient(new OkHttpClient())` + `MockWebServer`，不经过 Spring 容器。`HttpClientConfig.java` 里 `@Bean public OkHttpClient sharedOkHttpClient()` 是唯一的 `OkHttpClient` bean，构造注入按类型即可，不需要 `@Qualifier`。

5. **JSON POST 请求体的既有写法**（`OpenlistApi.java` 第 28/50-56 行）：`private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");`，`RequestBody.create(JSON_MEDIA_TYPE, jsonObject.toJSONString())`。OkHttp 该重载会自动在 `Content-Type` 头后追加 `; charset=utf-8`。

6. **SQL 迁移脚本的真实命名约定与设计文档不一致**：`ruoyi-common/src/main/resources/sql/` 目录下所有脚本文件名是 `20260737-xxx.sql` 这种**连续 8 位日期、不带横杠**的格式（如 `20260718-add-openlist-configs.sql`），设计文档写的 `2026-07-24-notify-webhook-config.sql`（带横杠）不符合仓库实际约定，本计划改用 `20260738-notify-webhook-config.sql`（`20260737` 是当前最新序号，见下一条）。

7. **⚠️ 设计文档完全没提到、但对本功能生效必不可少的一步**：新建 SQL 文件本身不会被自动执行。`ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java` 的 `getSqlFiles()` 方法里硬编码了一个**显式文件名列表**（第 22-63 行），`SimpleDdl` 只执行这个列表里出现的文件。当前列表最后一项是第 62 行 `"sql/20260737-fix-menu-group-icon-duplication.sql"`。**如果只新建 SQL 文件、不把文件名加进这个列表，`openlist.notify.webhook.url` 这个 `sys_config` 行永远不会被插入**，`getNotifyWebhookUrl()` 会一直读到 `null`，`WebhookNotifier` 表现为"永远不启用"且没有任何报错——这是一个极难排查的静默失败，因此本计划把"注册进 `MysqlDdl`"作为任务 1 的必需步骤，不是可选项。

---

## 任务 1：新增通知 Webhook 配置项（SQL 迁移 + 注册）

**文件：**
- 创建：`ruoyi-common/src/main/resources/sql/20260738-notify-webhook-config.sql`
- 修改：`ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java:62`

> 说明：数据库迁移脚本在本仓库没有单元测试传统（翻遍 `ruoyi-common`/`ruoyi-openliststrm` 的 `src/test`，没有任何一个 SQL 文件对应的测试类），此任务的"验证"环节是任务 7 的 Docker 启动验证里确认 `sys_config` 表出现新行，而不是 TDD 单测。以下步骤据此调整，不编造不存在的测试。

- [ ] **步骤 1：创建 SQL 迁移脚本**

创建 `ruoyi-common/src/main/resources/sql/20260738-notify-webhook-config.sql`：

```sql
-- ----------------------------
-- 20260738: 新增通用 Webhook 通知渠道配置项，配合 notify/WebhookNotifier 使用
-- 采用 INSERT ... WHERE NOT EXISTS 保证幂等，已存在的键不会被覆盖。
-- ----------------------------

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT '通知Webhook地址', 'openlist.notify.webhook.url', '', 'N', 'admin', '2026-07-24 00:00:00', '通用 Webhook 通知地址，POST JSON {"text": message}，留空则不启用'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.notify.webhook.url');
```

- [ ] **步骤 2：把新脚本注册进 `MysqlDdl.getSqlFiles()`**

打开 `ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java`，第 62 行现在是：

```java
                "sql/20260737-fix-menu-group-icon-duplication.sql"
        );
```

改为（给第 62 行加逗号，新增第 63 行）：

```java
                "sql/20260737-fix-menu-group-icon-duplication.sql",
                "sql/20260738-notify-webhook-config.sql"
        );
```

- [ ] **步骤 3：编译确认语法正确**

运行：`mvn -pl ruoyi-common -am compile -DskipTests`

预期：`BUILD SUCCESS`。

- [ ] **步骤 4：Commit**

```bash
git add ruoyi-common/src/main/resources/sql/20260738-notify-webhook-config.sql ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java
git commit -m "feat(notify): 新增通知Webhook地址配置项 openlist.notify.webhook.url"
```

---

## 任务 2：`OpenlistConfig` 新增 `getNotifyWebhookUrl()`

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/config/OpenlistConfig.java:49`（在 `getOpenListTgUserId()` 方法后插入新方法）

> 说明：与任务 1 同理，本仓库现有 15 个 `OpenlistConfig` 的 getter 全部没有专门的单测（无 `OpenlistConfigTest` 文件），且 `OpenlistConfig` 用字段注入（`@Autowired private ISysConfigService sysConfigService`），要单独测试这一行委托代码得引入 `ReflectionTestUtils` 这种和现有约定不一致的写法。遵循既有约定不为此单独建测试文件；该方法的正确性由任务 4 的 `WebhookNotifierTest`（用 mock 的 `OpenlistConfig`）验证消费侧行为，真实的 Spring 装配 + `sys_config` 读取由任务 7 的 Docker 启动验证兜底。

- [ ] **步骤 1：新增方法**

`OpenlistConfig.java` 第 46-50 行现状：

```java
    //tg用户id
    public String getOpenListTgUserId() {
        return sysConfigService.selectConfigByKey("openlist.tg.userid");
    }

    //Apikey
```

改为（在两者之间插入）：

```java
    //tg用户id
    public String getOpenListTgUserId() {
        return sysConfigService.selectConfigByKey("openlist.tg.userid");
    }

    //通知Webhook地址
    public String getNotifyWebhookUrl() {
        return sysConfigService.selectConfigByKey("openlist.notify.webhook.url");
    }

    //Apikey
```

- [ ] **步骤 2：编译确认**

运行：`mvn -pl ruoyi-openliststrm -am compile -DskipTests`

预期：`BUILD SUCCESS`。

- [ ] **步骤 3：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/config/OpenlistConfig.java
git commit -m "feat(notify): OpenlistConfig新增getNotifyWebhookUrl读取Webhook地址配置"
```

---

## 任务 3：`INotifier` 接口 + `NotifierManager` 分发器

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/INotifier.java`
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/NotifierManager.java`
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/notify/NotifierManagerTest.java`

`INotifier` 是纯接口、无逻辑，不单独写测试（与仓库里 `IDownloaderClient`/`IMediaServerClient` 的既有做法一致——这两个接口本身也没有对应的测试类，只在其消费方/实现类的测试里间接验证）。真正需要 TDD 的是 `NotifierManager` 的分发行为。

- [ ] **步骤 1：创建 `INotifier` 接口**

创建 `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/INotifier.java`：

```java
package com.ruoyi.openliststrm.notify;

/**
 * 通知渠道抽象。新增一个通知渠道时，只需新增一个实现本接口的 {@code @Component}，
 * Spring 会自动被 {@link NotifierManager} 收集，不需要改动任何分发逻辑或调用点。
 * <p>
 * 契约：实现类必须自行判断"是否已配置"（未配置时 no-op，不发送），
 * 且绝不能向外抛出异常——发送失败只记录日志，不能影响调用方，也不能影响其余渠道。
 *
 * @author Jack
 */
public interface INotifier {

    /**
     * 发送一条通知消息。
     * 实现类必须保证：未配置（如 token/url 为空）时静默跳过；发送失败时内部吞掉异常，只记录 warn 日志。
     */
    void send(String message);
}
```

- [ ] **步骤 2：编写 `NotifierManagerTest` 失败的测试**

创建 `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/notify/NotifierManagerTest.java`：

```java
package com.ruoyi.openliststrm.notify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotifierManagerTest {

    @Mock private INotifier notifierA;
    @Mock private INotifier notifierB;

    @Test
    void send_遍历所有渠道_全部收到相同消息() {
        NotifierManager manager = new NotifierManager(List.of(notifierA, notifierB));

        manager.send("hello");

        verify(notifierA).send("hello");
        verify(notifierB).send("hello");
    }

    @Test
    void send_某个渠道抛异常_不影响其余渠道继续发送() {
        doThrow(new RuntimeException("boom")).when(notifierA).send("hello");
        NotifierManager manager = new NotifierManager(List.of(notifierA, notifierB));

        manager.send("hello");

        verify(notifierB).send("hello");
    }

    @Test
    void send_空渠道列表_不抛异常() {
        NotifierManager manager = new NotifierManager(List.of());

        assertDoesNotThrow(() -> manager.send("hello"));
    }
}
```

- [ ] **步骤 3：运行测试验证失败**

运行：`mvn -pl ruoyi-openliststrm -am test-compile -Dtest=NotifierManagerTest`

预期：编译失败（`FAIL`），报错找不到符号 `NotifierManager`（类尚未创建）。

- [ ] **步骤 4：编写 `NotifierManager` 最少实现代码**

创建 `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/NotifierManager.java`：

```java
package com.ruoyi.openliststrm.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 通知渠道分发器：构造注入 {@code List<INotifier>}，Spring 会自动收集所有 {@link INotifier}
 * 实现类装配进这个列表（各实现类只需标注 {@code @Component}，不需要手工注册）。
 * <p>
 * {@link #send(String)} 对每个渠道单独 try/catch：任一渠道抛异常只记录 warn 日志、
 * 不影响其余渠道继续发送，也不会让本方法本身抛出异常——调用方（{@code TgHelper} 门面
 * 以及未来直接注入本类的新代码）因此不需要关心通知是否发送成功。
 *
 * @author Jack
 */
@Slf4j
@Component
public class NotifierManager {

    private final List<INotifier> notifiers;

    public NotifierManager(List<INotifier> notifiers) {
        this.notifiers = notifiers;
    }

    public void send(String message) {
        for (INotifier notifier : notifiers) {
            try {
                notifier.send(message);
            } catch (Exception e) {
                log.warn("通知渠道[{}]发送失败：{}", notifier.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`mvn -pl ruoyi-openliststrm -am test -Dtest=NotifierManagerTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：`Tests run: 3, Failures: 0, Errors: 0` / `BUILD SUCCESS`。

- [ ] **步骤 6：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/INotifier.java ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/NotifierManager.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/notify/NotifierManagerTest.java
git commit -m "feat(notify): 新增INotifier接口与NotifierManager多渠道分发器"
```

---

## 任务 4：`WebhookNotifier`（验证渠道可扩展性）

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/WebhookNotifier.java`
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/notify/WebhookNotifierTest.java`

依赖：任务 2 的 `OpenlistConfig.getNotifyWebhookUrl()` 必须已存在（否则编译不过）。

- [ ] **步骤 1：编写失败的测试**

创建 `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/notify/WebhookNotifierTest.java`：

```java
package com.ruoyi.openliststrm.notify;

import com.ruoyi.openliststrm.config.OpenlistConfig;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookNotifierTest {

    @Mock private OpenlistConfig config;

    private MockWebServer server;
    private WebhookNotifier notifier;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        notifier = new WebhookNotifier(config, new OkHttpClient());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void send_url未配置_不发起任何请求() {
        when(config.getNotifyWebhookUrl()).thenReturn("");

        notifier.send("hello");

        assertEquals(0, server.getRequestCount());
    }

    @Test
    void send_url已配置_发起POST请求且请求体正确() throws Exception {
        when(config.getNotifyWebhookUrl()).thenReturn(server.url("/hook").toString());
        server.enqueue(new MockResponse().setResponseCode(200));

        notifier.send("hello world");

        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getHeader("Content-Type").startsWith("application/json"));
        assertEquals("{\"text\":\"hello world\"}", request.getBody().readUtf8());
    }

    @Test
    void send_服务端返回5xx_不抛出异常() {
        when(config.getNotifyWebhookUrl()).thenReturn(server.url("/hook").toString());
        server.enqueue(new MockResponse().setResponseCode(500));

        assertDoesNotThrow(() -> notifier.send("hello"));
    }

    @Test
    void send_连接失败_不抛出异常() throws IOException {
        MockWebServer deadServer = new MockWebServer();
        deadServer.start();
        String url = deadServer.url("/hook").toString();
        deadServer.shutdown();
        when(config.getNotifyWebhookUrl()).thenReturn(url);

        assertDoesNotThrow(() -> notifier.send("hello"));
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl ruoyi-openliststrm -am test-compile -Dtest=WebhookNotifierTest`

预期：编译失败（`FAIL`），报错找不到符号 `WebhookNotifier`（类尚未创建）。

- [ ] **步骤 3：编写 `WebhookNotifier` 最少实现代码**

创建 `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/WebhookNotifier.java`：

```java
package com.ruoyi.openliststrm.notify;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 通用 Webhook 通知渠道：POST 一个最简单的 {"text": message} JSON 到配置的地址，
 * 用来验证"新增渠道只需新增一个 {@code @Component implements INotifier}"这套抽象是否可扩展。
 * 不做企业微信/飞书/Bark 等特定服务商的私有协议适配（见设计文档第 8 节"不做的事情"）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class WebhookNotifier implements INotifier {

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    private final OpenlistConfig config;
    private final OkHttpClient httpClient;

    public WebhookNotifier(OpenlistConfig config, OkHttpClient sharedOkHttpClient) {
        this.config = config;
        this.httpClient = sharedOkHttpClient;
    }

    @Override
    public void send(String message) {
        String url = config.getNotifyWebhookUrl();
        if (StringUtils.isBlank(url)) {
            return;
        }
        JSONObject body = new JSONObject();
        body.put("text", message);
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(JSON_MEDIA_TYPE, body.toJSONString()))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Webhook 通知发送失败，HTTP {}", response.code());
            }
        } catch (IOException e) {
            log.warn("Webhook 通知发送异常：{}", e.getMessage());
        }
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -pl ruoyi-openliststrm -am test -Dtest=WebhookNotifierTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：`Tests run: 4, Failures: 0, Errors: 0` / `BUILD SUCCESS`。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/WebhookNotifier.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/notify/WebhookNotifierTest.java
git commit -m "feat(notify): 新增WebhookNotifier通用Webhook通知渠道"
```

---

## 任务 5：`TgNotifier`（承接 `TgHelper` 原有 Telegram 逻辑）

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/TgNotifier.java`
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/notify/TgNotifierTest.java`

> 范围说明（对齐设计文档第 7 节）：不做"真的发到 Telegram"的集成测试——`TgSendMsg.sendMsg()`（`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/tg/TgSendMsg.java` 第 27-41 行）内部已经用 `try/catch` 吞掉了 `TelegramApiException`，这段逻辑原样迁移不需要重新证明。本任务只测试"token/userId 任一为空时不构造 `TgSendMsg`、不发起真实网络请求"这个 no-op 分支，通过包内可见的 `cachedBot` 字段侧面验证。

- [ ] **步骤 1：编写失败的测试**

创建 `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/notify/TgNotifierTest.java`：

```java
package com.ruoyi.openliststrm.notify;

import com.ruoyi.openliststrm.config.OpenlistConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TgNotifierTest {

    @Mock private OpenlistConfig config;

    @Test
    void send_token为空_不构造TgSendMsg也不抛异常() {
        when(config.getOpenListTgToken()).thenReturn("");
        when(config.getOpenListTgUserId()).thenReturn("user-1");
        TgNotifier notifier = new TgNotifier(config);

        assertDoesNotThrow(() -> notifier.send("hello"));

        assertNull(notifier.cachedBot);
    }

    @Test
    void send_userId为空_不构造TgSendMsg也不抛异常() {
        when(config.getOpenListTgToken()).thenReturn("token-1");
        when(config.getOpenListTgUserId()).thenReturn("");
        TgNotifier notifier = new TgNotifier(config);

        assertDoesNotThrow(() -> notifier.send("hello"));

        assertNull(notifier.cachedBot);
    }

    @Test
    void send_两者都为空_不构造TgSendMsg也不抛异常() {
        when(config.getOpenListTgToken()).thenReturn(null);
        when(config.getOpenListTgUserId()).thenReturn(null);
        TgNotifier notifier = new TgNotifier(config);

        assertDoesNotThrow(() -> notifier.send("hello"));

        assertNull(notifier.cachedBot);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn -pl ruoyi-openliststrm -am test-compile -Dtest=TgNotifierTest`

预期：编译失败（`FAIL`），报错找不到符号 `TgNotifier`（类尚未创建）。

- [ ] **步骤 3：编写 `TgNotifier` 最少实现代码**

创建 `ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/TgNotifier.java`：

```java
package com.ruoyi.openliststrm.notify;

import com.ruoyi.openliststrm.config.OpenlistConfig;
import com.ruoyi.openliststrm.tg.TgSendMsg;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Telegram 通知渠道，承接原 {@code TgHelper} 的逻辑：token/userId 均已配置才发送，
 * {@link TgSendMsg} 实例按 token/userId 缓存，配置变化时重建。
 * <p>
 * 与原 {@code TgHelper} 的差异：构造函数直接注入 {@link OpenlistConfig}（本类是
 * {@code @Component}，走正常 Spring 依赖注入，不再需要 {@code SpringUtils.getBean()}
 * 这个只有纯静态类才需要的绕行写法）；缓存字段从 {@code static volatile} 改为普通实例
 * 字段（本类本身是单例 Bean，不再需要"整个 JVM 只有一份"的静态缓存语义）。
 *
 * @author Jack
 */
@Slf4j
@Component
public class TgNotifier implements INotifier {

    private final OpenlistConfig config;

    /** 包内可见，供 {@code TgNotifierTest} 侧面验证 no-op 分支未构造真实 Bot 实例 */
    volatile TgSendMsg cachedBot;
    private volatile String cachedToken;
    private volatile String cachedUserId;

    public TgNotifier(OpenlistConfig config) {
        this.config = config;
    }

    @Override
    public void send(String message) {
        String token = config.getOpenListTgToken();
        String userId = config.getOpenListTgUserId();
        if (StringUtils.isAnyBlank(token, userId)) {
            return;
        }
        try {
            getBot(token, userId).sendMsg(message);
        } catch (Exception e) {
            log.warn("Telegram 通知发送失败：{}", e.getMessage());
        }
    }

    /** token/userId 在后台配置变更后会重建实例，其余情况复用缓存 */
    private TgSendMsg getBot(String token, String userId) {
        TgSendMsg bot = cachedBot;
        if (bot != null && token.equals(cachedToken) && userId.equals(cachedUserId)) {
            return bot;
        }
        synchronized (this) {
            if (cachedBot == null || !token.equals(cachedToken) || !userId.equals(cachedUserId)) {
                cachedBot = new TgSendMsg(token, userId);
                cachedToken = token;
                cachedUserId = userId;
            }
            return cachedBot;
        }
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn -pl ruoyi-openliststrm -am test -Dtest=TgNotifierTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：`Tests run: 3, Failures: 0, Errors: 0` / `BUILD SUCCESS`。

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/notify/TgNotifier.java ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/notify/TgNotifierTest.java
git commit -m "feat(notify): 新增TgNotifier承接TgHelper原有Telegram发送逻辑"
```

---

## 任务 6：`TgHelper` 改为委托门面（不改调用点、不改测试断言）

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/helper/TgHelper.java`（整个文件，当前 46 行）
- 回归验证（不改动）：
  - `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/DownloadTrackServiceTest.java`（3 处 `mockStatic` 断言，第 220/235 行两个 `try` 块）
  - `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SearchSupplementServiceTest.java`（5 处 `mockStatic` 断言，第 881/895/911/927/945 行）
  - `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/task/RssPollServiceTest.java`（6 处 `mockStatic` 断言，第 162/174/188/216/241/259 行——设计文档遗漏了这个文件，见文首"背景与关键事实核对"第 2 条）

这一步是纯重构：`TgHelper.sendMsg(String)` 的类名、包路径、方法签名完全不变，`mockStatic(TgHelper.class)` 是按类名+方法签名拦截，不会执行方法体，因此这三个文件不需要改一行代码。验证方式是"改动前基线是绿的 → 改动 → 改动后仍然是绿的"，而不是新写一个测试断言新行为（本类改动后没有新的可观察行为需要断言——`TgHelper` 本身不再包含任何逻辑）。

- [ ] **步骤 1：运行三个回归测试文件，确认修改前基线全绿**

运行：`mvn -pl ruoyi-openliststrm -am test -Dtest=DownloadTrackServiceTest,SearchSupplementServiceTest,RssPollServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：`BUILD SUCCESS`，三个类的全部测试通过（这是重构前的基线，用于和步骤 3 的结果对比）。

- [ ] **步骤 2：修改 `TgHelper.java`**

`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/helper/TgHelper.java` 整个文件替换为：

```java
package com.ruoyi.openliststrm.helper;

import com.ruoyi.common.utils.spring.SpringUtils;
import com.ruoyi.openliststrm.notify.NotifierManager;

/**
 * 兼容门面：历史遗留的静态调用入口。{@link #sendMsg(String)} 现在会转发给
 * {@link NotifierManager}，由其分发到所有已启用的通知渠道（不再只是 Telegram）。
 * <p>
 * 之所以保留这个静态方法而不是让调用方直接注入 {@link NotifierManager}，是因为
 * {@code DownloadTrackServiceTest}、{@code SearchSupplementServiceTest}、
 * {@code RssPollServiceTest} 三个测试文件里共 14 处
 * {@code Mockito.mockStatic(TgHelper.class)} 断言依赖这个类名和方法签名不变。
 * <p>
 * 新代码不应该再调用这个静态方法——应该走标准的构造函数注入：注入 {@link NotifierManager}
 * 并调用其 {@code send(msg)}，这样新代码天然可测（不需要 {@code mockStatic}）。
 *
 * @Author Jack
 * @Date 2025/7/20 18:49
 * @Version 1.0.0
 */
public class TgHelper {

    public static void sendMsg(String msg) {
        SpringUtils.getBean(NotifierManager.class).send(msg);
    }

}
```

- [ ] **步骤 3：重新运行三个回归测试文件，确认修改后仍然全绿**

运行：`mvn -pl ruoyi-openliststrm -am test -Dtest=DownloadTrackServiceTest,SearchSupplementServiceTest,RssPollServiceTest -Dsurefire.failIfNoSpecifiedTests=false`

预期：`BUILD SUCCESS`，测试数量和通过数量与步骤 1 完全一致（0 失败、0 错误）。如果任何一个 `mockStatic` 断言变红，说明委托改动意外影响了静态方法的可 mock 性，必须先排查再继续。

- [ ] **步骤 4：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/helper/TgHelper.java
git commit -m "refactor(notify): TgHelper改为委托NotifierManager分发，调用点与mockStatic测试不变"
```

---

## 任务 7：全量验证（编译、全模块测试、Docker 启动验证）

**文件：** 不新增/修改文件，仅验证。

新增了 3 个 `@Component`（`TgNotifier`/`WebhookNotifier`/`NotifierManager`），按 `AGENTS.md` 要求必须做真实启动验证——单元测试都是直接 `new NotifierManager(List.of(...))` 之类的手工构造，绕开了真实的 Spring 装配路径，尤其要确认 `NotifierManager` 构造注入 `List<INotifier>` 在容器里确实收集到了 `TgNotifier` 与 `WebhookNotifier` 两个实现（而不是空列表或装配失败）。

- [ ] **步骤 1：全量编译**

运行：`mvn clean package -DskipTests`

预期：`BUILD SUCCESS`，生成 `ruoyi-admin/target/ruoyi-admin.jar`。

- [ ] **步骤 2：全模块单元测试**

运行：`mvn -pl ruoyi-openliststrm -am test`

预期：`BUILD SUCCESS`，无失败用例（包含本计划新增的 `NotifierManagerTest`/`WebhookNotifierTest`/`TgNotifierTest`，以及任务 6 验证过的三个回归测试文件）。

- [ ] **步骤 3：Docker 重新构建后端并确认容器未反复重启**

运行：`docker compose up -d --build --no-deps backend`

等待约 30 秒后运行：`docker ps --filter "name=osr-backend" --format "{{.Names}}\t{{.Status}}"`

预期：`Status` 显示 `Up XX seconds`（不含 `Restarting`），说明 Spring 容器成功装配了 `NotifierManager(List<INotifier>)`（若装配失败，`APPLICATION FAILED TO START` 会在这一步暴露，`MysqlDdl` 迁移也不会执行）。若容器反复重启，按 `AGENTS.md` 的排查方法执行：

```bash
docker update --restart=no osr-backend
docker restart osr-backend
docker cp osr-backend:/data/logs ./tmp-logs
```

然后读 `./tmp-logs/sys-error.log` 定位堆栈（该日志不在 stdout）。

- [ ] **步骤 4：确认 `sys_config` 新增了 Webhook 配置行**

运行（按项目实际的 MySQL 连接方式，例如）：`docker exec osr-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" osr -e "SELECT config_key, config_value FROM sys_config WHERE config_key='openlist.notify.webhook.url';"`

预期：返回 1 行，`config_key = openlist.notify.webhook.url`，`config_value` 为空字符串（默认未配置，Webhook 渠道处于 no-op 状态，这正是设计文档成功标准第 5 条要求的"未配置则静默跳过"）。

- [ ] **步骤 5：确认系统参数管理页面可见新配置项（人工核查，无需自动化）**

登录后台 → 系统管理 → 参数设置，搜索 `config_key = openlist.notify.webhook.url`，确认能看到并编辑这一行（复用既有通用 CRUD 页面，验证设计文档"不需要新增前端组件"的假设成立）。

- [ ] **步骤 6：最终 Commit（如果前面步骤产生了任何遗留改动）**

如果前面步骤都是纯验证、没有修改任何文件，这一步跳过。如果验证过程中为了修复启动失败而改了代码，按 fix 提交：

```bash
git add -A
git commit -m "fix(notify): 修复NotifierManager真实Spring装配问题"
```

---

## 自检记录（写完计划后按 writing-plans 技能要求做的复查）

1. **规格覆盖度**：设计文档 4 节"后端组件改动清单"里的 6 行改动——`INotifier`（任务 3）、`TgNotifier`（任务 5）、`WebhookNotifier`（任务 4）、`NotifierManager`（任务 3）、`TgHelper`（任务 6）、`OpenlistConfig`（任务 2）——均有对应任务。第 3 节数据模型改动（`sys_config` 新增一项）对应任务 1，并且额外发现并修复了设计文档遗漏的 `MysqlDdl.getSqlFiles()` 注册步骤（否则该配置项永远不会被插入）。第 7 节测试计划的四类测试（`NotifierManagerTest`/`WebhookNotifierTest`/`TgNotifierTest`/三个既有测试文件的回归）均已落实到任务 3/4/5/6。第 8 节"不做的事情"未新增任何超出范围的任务。

2. **占位符扫描**：全文没有"待定"/"TODO"/"后续实现"/"补充细节"/"类似任务N"字样；任务 1、2 明确写出"不写单测"的技术理由（既有代码库无对应先例 + 消费侧由后续任务覆盖），不是含糊带过。

3. **类型一致性**：`INotifier.send(String message)` → `NotifierManager(List<INotifier> notifiers)` 构造参数类型一致；`TgNotifier`/`WebhookNotifier` 均 `implements INotifier`，方法签名 `void send(String message)` 与接口一致；`WebhookNotifier` 构造参数 `(OpenlistConfig config, OkHttpClient sharedOkHttpClient)` 与 `EmbyClient`/`QbittorrentClient` 的 `(OkHttpClient sharedOkHttpClient)` 风格一致（多一个 `OpenlistConfig` 是因为要读 `getNotifyWebhookUrl()`）；`OpenlistConfig.getNotifyWebhookUrl()` 在任务 2 定义，任务 4/5 的实现类和测试里的方法名前后一致；`TgNotifier.cachedBot` 字段名在任务 5 的实现代码与测试代码里一致（均为 `cachedBot`，包内可见）。
