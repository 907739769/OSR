# 种子/发布组黑名单（E1）实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 支持按 GUID 精确拉黑单个种子、按发布组整体拉黑，两者在 `TorrentFilterEngine` 的过滤判定链里生效；提供下载记录页"拉黑该种子/拉黑该发布组"按钮 + 独立黑名单管理页两个操作入口。

**架构：** 不新建过滤主链路，在 `TorrentFilterEngine.rejectReason()` 现有判定链里插入 GUID/发布组两处判定；新增 `TorrentBlacklist` 值对象（与 `FilterCriteria` 同级，`pt.filter` 包），由 `SubscriptionEngine.process()`/`pushBest()` 各查一次全量黑名单后传入 `filterEngine.evaluate(candidates, criteria, blacklist)`（新增 3 参重载，旧 2 参签名保留、内部转调传 `TorrentBlacklist.EMPTY`，现有 24 个过滤测试零改动）。发布组解析复用 `rename` 模块已有的 `MediaParser.parseLocal()`/`SourceAndGroupExtractor`，`TorrentInfo` 新增 `parsedReleaseGroup` 字段由 `SubscriptionEngine.fillParsed()` 一次性填充（RSS、搜索补集两条链路共用同一个方法，自动覆盖）。

**技术栈：** Spring Boot 4 + MyBatis-Plus（BaseMapper/IService）、JUnit 5 + Mockito（`MockitoSettings(strictness = LENIENT)`）、Vue 3 + Element Plus + Vitest。

---

## 前置说明：与设计文档的两处偏差（已核实，按此计划为准）

1. **SQL 编号与菜单 ID**：设计文档 `docs/superpowers/specs/2026-07-24-pt-torrent-blacklist-design.md` 第 3 节写的迁移脚本编号是 `20260738`、菜单 `menu_id=2071`。实际读取 `MysqlDdl.getSqlFiles()` 与 `ruoyi-common/src/main/resources/sql/` 目录确认：`20260738`~`20260742` 五个编号已被同批次并行完成的 D/E4 计划占用（最后一项是 `20260742-pt-downloader-max-concurrency.sql`），且 `menu_id=2071`（`order_num=7`，父菜单 2070）已被 `20260741-pt-stats-menu.sql` 的"PT统计仪表盘"占用。本计划改用 **`20260743`** 作为新脚本编号，菜单改用 **`menu_id=2072`、`order_num=8`**。
2. **`PtTorrentBlacklistPlusServiceImpl` 的存在性判断**：设计文档只说"服务层查一次是否已存在"，没有指定具体查询方式。本计划不使用 `lambdaQuery()...exists()` 链式调用（该链式调用最终落到 MyBatis-Plus 生成的 SQL，但在纯 Mockito 单元测试里给 `baseMapper` 打桩的可靠切入点是 `BaseMapper<T>` 接口上明确存在的 `selectCount(Wrapper)` 方法），改为 `getBaseMapper().selectCount(wrapper) > 0`，并在测试里用 `org.springframework.test.util.ReflectionTestUtils.setField(service, "baseMapper", baseMapper)` 把 mock 注入到 `ServiceImpl` 继承来的 `protected` 字段——这是仓库里已有的验证过的写法（见 `ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/service/impl/StrmServiceImplTest.java:31`），比依赖 Mockito `@InjectMocks` 对泛型父类字段的隐式类型擦除匹配更可靠。

---

## 任务清单

1. SQL 迁移脚本：建表 `pt_torrent_blacklist` + 菜单
2. `PtTorrentBlacklistPlus` 三件套 + `blockRecordGuid`/`blockRecordReleaseGroup`/`save`/`updateById` 业务逻辑
3. `TorrentBlacklist` 值对象（`pt.filter` 包）
4. `TorrentInfo.parsedReleaseGroup` 字段 + `TorrentFilterEngine` 黑名单判定
5. `SubscriptionEngine` 集成黑名单查询与传递
6. 下载记录页拉黑按钮后端端点
7. 黑名单管理页后端端点
8. 前端 API 层 + composable（`ptTorrentBlacklist`）
9. 前端管理页面 `views/openlist/ptTorrentBlacklist/index.vue` + 路由注册 + 图标映射
10. 前端下载记录页拉黑按钮
11. 全量验证与启动校验

---

### 任务 1：SQL 迁移脚本 — 建表 `pt_torrent_blacklist` + 菜单

**文件：**
- 创建：`ruoyi-common/src/main/resources/sql/20260743-pt-torrent-blacklist.sql`
- 修改：`ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java:67`

**背景**：`MysqlDdl.getSqlFiles()` 当前最后一项（第 67 行）是 `"sql/20260742-pt-downloader-max-concurrency.sql"`，本次新脚本编号为 `20260743`。菜单挂在"PT下载管理"（`menu_id=2070`）下，`order_num=7` 已被 `20260741-pt-stats-menu.sql` 的"PT统计仪表盘"（`menu_id=2071`）占用，本次用 `menu_id=2072`、`order_num=8`。这是纯 DDL + 菜单任务，没有可独立单测的逻辑，用编译验证代替（与 `2026-07-24-pt-download-concurrency.md` 任务 1 的既有做法一致）。

- [ ] **步骤 1：创建迁移脚本**

```sql
-- ----------------------------
-- 20260743: 新增 PT 种子/发布组手动黑名单功能（建表 + 菜单）
-- 建表 pt_torrent_blacklist，唯一索引 uk_type_value(type, value) 防止同一种子/发布组被
-- 重复拉黑，也让 PtTorrentBlacklistPlusServiceImpl.blockRecordGuid/blockRecordReleaseGroup
-- 的幂等判断有约束兜底。
-- 菜单：PT下载管理(2070) 下新增第 8 项——menu_id=2071/order_num=7 已被
-- 20260741-pt-stats-menu.sql 的"PT统计仪表盘"占用，本次用 menu_id=2072, order_num=8。
-- 页面与接口同批上线，直接 visible='0'(显示)。
-- ----------------------------

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

INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2072, 'PT黑名单', 2070, 8, '/openlist/ptTorrentBlacklist', '', 'C', '0', '1', 'openliststrm:ptTorrentBlacklist:view', 'fa fa-ban', 'admin', '2026-07-25 00:00:00', '', NULL, 'PT 种子/发布组手动黑名单管理');
```

- [ ] **步骤 2：`MysqlDdl.getSqlFiles()` 追加脚本路径**

在列表最后一项（原文件第 67 行）`"sql/20260742-pt-downloader-max-concurrency.sql"` 之后追加：

```java
                "sql/20260741-pt-stats-menu.sql",
                "sql/20260742-pt-downloader-max-concurrency.sql",
                "sql/20260743-pt-torrent-blacklist.sql"
        );
```

- [ ] **步骤 3：编译验证（纯 DDL + 菜单脚本，无独立可测试逻辑；由任务 2/7 的测试间接覆盖表结构的读写）**

运行：`mvn compile -pl ruoyi-common,ruoyi-openliststrm -am -q`
预期：无输出、退出码 0

- [ ] **步骤 4：Commit**

```bash
git add ruoyi-common/src/main/resources/sql/20260743-pt-torrent-blacklist.sql \
        ruoyi-common/src/main/java/com/ruoyi/common/mybatisplus/MysqlDdl.java
git commit -m "feat: 新增 pt_torrent_blacklist 建表脚本与黑名单管理菜单"
```

---

### 任务 2：`PtTorrentBlacklistPlus` 三件套 + 业务逻辑

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/domain/PtTorrentBlacklistPlus.java`
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/mapper/PtTorrentBlacklistPlusMapper.java`
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/service/IPtTorrentBlacklistPlusService.java`
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/service/impl/PtTorrentBlacklistPlusServiceImpl.java`
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/mybatisplus/service/impl/PtTorrentBlacklistPlusServiceImplTest.java`

**背景**：`blockRecordGuid`/`blockRecordReleaseGroup` 是本任务唯一有真实逻辑的方法（幂等判断 + 发布组解析 + 归一化），`save()`/`updateById()` 重载负责拒绝管理页手动新增/编辑 `GUID` 类型。发布组解析复用 `MediaParser.parseLocal()`；`MediaParser` 不是 Spring bean（一直靠 `new` + `RenameClientProvider` 管理），写法与 `SubscriptionEngine.java:73` 的既有注释一致：手动 `new MediaParser(null, null)` 作为字段初始值，不走构造器注入。

- [ ] **步骤 1：编写失败的测试**

创建 `PtTorrentBlacklistPlusServiceImplTest.java`：

```java
package com.ruoyi.openliststrm.mybatisplus.service.impl;

import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.ruoyi.openliststrm.mybatisplus.mapper.PtTorrentBlacklistPlusMapper;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * blockRecordGuid/blockRecordReleaseGroup 是本类唯一有真实逻辑的方法，
 * save()/updateById() 重载负责管理页新增/编辑的类型限制与归一化。
 *
 * @author Jack
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PtTorrentBlacklistPlusServiceImplTest {

    @Mock
    private PtTorrentBlacklistPlusMapper baseMapper;
    @Mock
    private IPtDownloadRecordPlusService recordService;

    private PtTorrentBlacklistPlusServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PtTorrentBlacklistPlusServiceImpl(recordService);
        ReflectionTestUtils.setField(service, "baseMapper", baseMapper);
    }

    private PtDownloadRecordPlus record(Integer id, String title, String guidHash) {
        PtDownloadRecordPlus r = new PtDownloadRecordPlus();
        r.setId(id);
        r.setTitle(title);
        r.setGuidHash(guidHash);
        return r;
    }

    // ---------- blockRecordGuid ----------

    @Test
    void blockRecordGuid_记录存在且未拉黑过_新增一行() {
        when(recordService.getById(1)).thenReturn(record(1, "Some Title", "hash1"));
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any())).thenReturn(1);

        boolean result = service.blockRecordGuid(1, null);

        assertTrue(result);
        ArgumentCaptor<PtTorrentBlacklistPlus> captor = ArgumentCaptor.forClass(PtTorrentBlacklistPlus.class);
        verify(baseMapper).insert(captor.capture());
        PtTorrentBlacklistPlus saved = captor.getValue();
        assertEquals("GUID", saved.getType());
        assertEquals("hash1", saved.getValue());
        assertEquals("Some Title", saved.getDisplayValue());
        assertTrue(saved.getReason() != null && !saved.getReason().isBlank());
    }

    @Test
    void blockRecordGuid_重复调用同一记录_不重复插入返回false() {
        when(recordService.getById(1)).thenReturn(record(1, "Some Title", "hash1"));
        when(baseMapper.selectCount(any())).thenReturn(1L);

        boolean result = service.blockRecordGuid(1, null);

        assertFalse(result);
        verify(baseMapper, never()).insert(any());
    }

    @Test
    void blockRecordGuid_记录不存在_抛IllegalArgumentException() {
        when(recordService.getById(999)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.blockRecordGuid(999, null));
    }

    // ---------- blockRecordReleaseGroup ----------

    @Test
    void blockRecordReleaseGroup_标题能解析出发布组_新增一行value为大写发布组() {
        when(recordService.getById(1)).thenReturn(
                record(1, "Show.Name.S01E01.1080p.WEB-DL.H264-chdweb", "hash1"));
        when(baseMapper.selectCount(any())).thenReturn(0L);
        when(baseMapper.insert(any())).thenReturn(1);

        boolean result = service.blockRecordReleaseGroup(1, "画质差");

        assertTrue(result);
        ArgumentCaptor<PtTorrentBlacklistPlus> captor = ArgumentCaptor.forClass(PtTorrentBlacklistPlus.class);
        verify(baseMapper).insert(captor.capture());
        PtTorrentBlacklistPlus saved = captor.getValue();
        assertEquals("RELEASE_GROUP", saved.getType());
        assertEquals("CHDWEB", saved.getValue());
        assertEquals("chdweb", saved.getDisplayValue());
        assertEquals("画质差", saved.getReason());
    }

    @Test
    void blockRecordReleaseGroup_标题解析不出发布组_抛IllegalArgumentException() {
        when(recordService.getById(1)).thenReturn(record(1, "纯中文电影标题", "hash1"));

        assertThrows(IllegalArgumentException.class, () -> service.blockRecordReleaseGroup(1, null));
    }

    @Test
    void blockRecordReleaseGroup_记录不存在_抛IllegalArgumentException() {
        when(recordService.getById(999)).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () -> service.blockRecordReleaseGroup(999, null));
    }

    // ---------- save()/updateById() 类型限制与归一化 ----------

    @Test
    void save_type为GUID_一律拒绝() {
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setType("GUID");
        entity.setValue("somehash");

        assertThrows(IllegalArgumentException.class, () -> service.save(entity));
        verify(baseMapper, never()).insert(any());
    }

    @Test
    void save_type为RELEASE_GROUP_value落库前归一化为去空白加大写() {
        when(baseMapper.insert(any())).thenReturn(1);
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setType("RELEASE_GROUP");
        entity.setValue("  chdweb  ");

        boolean result = service.save(entity);

        assertTrue(result);
        assertEquals("CHDWEB", entity.getValue());
    }

    @Test
    void save_type为RELEASE_GROUP_未填displayValue时回填为原始value() {
        when(baseMapper.insert(any())).thenReturn(1);
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setType("RELEASE_GROUP");
        entity.setValue("  chdweb  ");

        service.save(entity);

        assertEquals("chdweb", entity.getDisplayValue());
    }

    @Test
    void updateById_type为GUID_一律拒绝() {
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setId(5);
        entity.setType("GUID");

        assertThrows(IllegalArgumentException.class, () -> service.updateById(entity));
        verify(baseMapper, never()).updateById(any());
    }

    @Test
    void updateById_type为RELEASE_GROUP_value落库前归一化() {
        when(baseMapper.updateById(any())).thenReturn(1);
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setId(5);
        entity.setType("RELEASE_GROUP");
        entity.setValue(" mteam ");

        boolean result = service.updateById(entity);

        assertTrue(result);
        assertEquals("MTEAM", entity.getValue());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=PtTorrentBlacklistPlusServiceImplTest`
预期：COMPILATION ERROR，报错类似 `cannot find symbol: class PtTorrentBlacklistPlus`（domain/mapper/interface/impl 均不存在）

- [ ] **步骤 3：编写最少实现代码**

创建 `PtTorrentBlacklistPlus.java`：

```java
package com.ruoyi.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ruoyi.common.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * PT 种子/发布组手动黑名单
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Getter
@Setter
@TableName("pt_torrent_blacklist")
public class PtTorrentBlacklistPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    /** 拉黑类型：按 GUID 精确拉黑单个种子 */
    public static final String TYPE_GUID = "GUID";
    /** 拉黑类型：按发布组整体拉黑 */
    public static final String TYPE_RELEASE_GROUP = "RELEASE_GROUP";

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 拉黑类型：GUID / RELEASE_GROUP */
    @TableField("type")
    private String type;

    /** 匹配键：GUID 类型存 guid 的 SHA-256 哈希，RELEASE_GROUP 类型存归一化(大写)的发布组名 */
    @TableField("value")
    private String value;

    /** 展示用原文，仅供管理页展示，不参与匹配 */
    @TableField("display_value")
    private String displayValue;

    /** 拉黑原因 */
    @TableField("reason")
    private String reason;
}
```

创建 `PtTorrentBlacklistPlusMapper.java`：

```java
package com.ruoyi.openliststrm.mybatisplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ruoyi.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;

/**
 * <p>
 * PT 种子/发布组手动黑名单 Mapper 接口
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
public interface PtTorrentBlacklistPlusMapper extends BaseMapper<PtTorrentBlacklistPlus> {

}
```

创建 `IPtTorrentBlacklistPlusService.java`：

```java
package com.ruoyi.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ruoyi.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;

/**
 * <p>
 * PT 种子/发布组手动黑名单 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
public interface IPtTorrentBlacklistPlusService extends IService<PtTorrentBlacklistPlus> {

    /**
     * 拉黑指定下载记录对应的种子（GUID 维度），幂等。
     *
     * @return true=新增成功，false=已存在（幂等命中）
     * @throws IllegalArgumentException 下载记录不存在
     */
    boolean blockRecordGuid(Integer recordId, String reason);

    /**
     * 拉黑指定下载记录标题解析出的发布组，幂等。
     *
     * @return true=新增成功，false=已存在（幂等命中）
     * @throws IllegalArgumentException 下载记录不存在，或标题解析不出发布组
     */
    boolean blockRecordReleaseGroup(Integer recordId, String reason);
}
```

创建 `PtTorrentBlacklistPlusServiceImpl.java`：

```java
package com.ruoyi.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtDownloadRecordPlus;
import com.ruoyi.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.ruoyi.openliststrm.mybatisplus.mapper.PtTorrentBlacklistPlusMapper;
import com.ruoyi.openliststrm.mybatisplus.service.IPtDownloadRecordPlusService;
import com.ruoyi.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.ruoyi.openliststrm.rename.MediaParser;
import com.ruoyi.openliststrm.rename.model.MediaInfo;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * <p>
 * PT 种子/发布组手动黑名单 服务实现类
 * </p>
 *
 * @author Jack
 * @since 2026-07-25
 */
@Service
public class PtTorrentBlacklistPlusServiceImpl extends ServiceImpl<PtTorrentBlacklistPlusMapper, PtTorrentBlacklistPlus>
        implements IPtTorrentBlacklistPlusService {

    private static final String DEFAULT_REASON_GUID = "从下载记录页手动拉黑该种子";
    private static final String DEFAULT_REASON_GROUP = "从下载记录页手动拉黑该发布组";

    private final IPtDownloadRecordPlusService recordService;

    /**
     * 本地标题解析器，仅用于从种子标题解析发布组。parseLocal 只做本地正则抽取，不发任何
     * 网络请求，所以传 null 客户端即可；MediaParser 不是 Spring bean（同
     * {@link com.ruoyi.openliststrm.pt.subscription.SubscriptionEngine} 的既有写法），
     * 若通过构造器注入会导致本 Service 装配时找不到 MediaParser bean 而启动失败。
     */
    private final MediaParser mediaParser = new MediaParser(null, null);

    public PtTorrentBlacklistPlusServiceImpl(IPtDownloadRecordPlusService recordService) {
        this.recordService = recordService;
    }

    @Override
    public boolean blockRecordGuid(Integer recordId, String reason) {
        PtDownloadRecordPlus record = recordService.getById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("下载记录不存在");
        }
        String value = record.getGuidHash();
        if (existsByTypeAndValue(PtTorrentBlacklistPlus.TYPE_GUID, value)) {
            return false;
        }
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setType(PtTorrentBlacklistPlus.TYPE_GUID);
        entity.setValue(value);
        entity.setDisplayValue(record.getTitle());
        entity.setReason(StringUtils.isNotBlank(reason) ? reason : DEFAULT_REASON_GUID);
        return super.save(entity);
    }

    @Override
    public boolean blockRecordReleaseGroup(Integer recordId, String reason) {
        PtDownloadRecordPlus record = recordService.getById(recordId);
        if (record == null) {
            throw new IllegalArgumentException("下载记录不存在");
        }
        if (StringUtils.isBlank(record.getTitle())) {
            throw new IllegalArgumentException("该下载记录没有标题，无法解析发布组");
        }
        MediaInfo info = mediaParser.parseLocal(record.getTitle());
        String group = info.getReleaseGroup();
        if (StringUtils.isBlank(group)) {
            throw new IllegalArgumentException("无法从标题解析出发布组");
        }
        String normalized = group.trim().toUpperCase(Locale.ROOT);
        if (existsByTypeAndValue(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP, normalized)) {
            return false;
        }
        PtTorrentBlacklistPlus entity = new PtTorrentBlacklistPlus();
        entity.setType(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP);
        entity.setValue(normalized);
        entity.setDisplayValue(group);
        entity.setReason(StringUtils.isNotBlank(reason) ? reason : DEFAULT_REASON_GROUP);
        return super.save(entity);
    }

    @Override
    public boolean save(PtTorrentBlacklistPlus entity) {
        rejectGuidType(entity);
        normalizeReleaseGroupValue(entity);
        return super.save(entity);
    }

    @Override
    public boolean updateById(PtTorrentBlacklistPlus entity) {
        rejectGuidType(entity);
        normalizeReleaseGroupValue(entity);
        return super.updateById(entity);
    }

    private void rejectGuidType(PtTorrentBlacklistPlus entity) {
        if (PtTorrentBlacklistPlus.TYPE_GUID.equals(entity.getType())) {
            throw new IllegalArgumentException("管理页不支持手动新增/编辑 GUID 类型的黑名单规则，请通过下载记录页的拉黑按钮操作");
        }
    }

    /** RELEASE_GROUP 的 value 落库前归一化为去空白+大写；displayValue 为空时回填原始输入，方便管理页展示 */
    private void normalizeReleaseGroupValue(PtTorrentBlacklistPlus entity) {
        if (PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP.equals(entity.getType()) && StringUtils.isNotBlank(entity.getValue())) {
            String raw = entity.getValue().trim();
            if (StringUtils.isBlank(entity.getDisplayValue())) {
                entity.setDisplayValue(raw);
            }
            entity.setValue(raw.toUpperCase(Locale.ROOT));
        }
    }

    private boolean existsByTypeAndValue(String type, String value) {
        Long count = getBaseMapper().selectCount(new LambdaQueryWrapper<PtTorrentBlacklistPlus>()
                .eq(PtTorrentBlacklistPlus::getType, type)
                .eq(PtTorrentBlacklistPlus::getValue, value));
        return count != null && count > 0;
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=PtTorrentBlacklistPlusServiceImplTest`
预期：`Tests run: 11, Failures: 0, Errors: 0`

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/domain/PtTorrentBlacklistPlus.java \
        ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/mapper/PtTorrentBlacklistPlusMapper.java \
        ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/service/IPtTorrentBlacklistPlusService.java \
        ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/mybatisplus/service/impl/PtTorrentBlacklistPlusServiceImpl.java \
        ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/mybatisplus/service/impl/PtTorrentBlacklistPlusServiceImplTest.java
git commit -m "feat: 新增 PT 种子/发布组黑名单三件套与拉黑业务逻辑"
```

---

### 任务 3：`TorrentBlacklist` 值对象（`pt.filter` 包）

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/filter/TorrentBlacklist.java`
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/filter/TorrentBlacklistTest.java`

**背景**：与 `FilterCriteria` 同一角色的不可变值对象，装 `Set<String> guidHashes` + `Set<String> releaseGroupsUpper`，`EMPTY` 常量供旧调用点使用，`from(List<PtTorrentBlacklistPlus>)` 静态工厂做分组归一化（发布组统一大写）。

- [ ] **步骤 1：编写失败的测试**

创建 `TorrentBlacklistTest.java`：

```java
package com.ruoyi.openliststrm.pt.filter;

import com.ruoyi.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentBlacklistTest {

    private PtTorrentBlacklistPlus rule(String type, String value) {
        PtTorrentBlacklistPlus r = new PtTorrentBlacklistPlus();
        r.setType(type);
        r.setValue(value);
        return r;
    }

    @Test
    void from_发布组归一化为大写() {
        TorrentBlacklist blacklist = TorrentBlacklist.from(List.of(
                rule(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP, "chdweb")));

        assertTrue(blacklist.releaseGroupsUpper().contains("CHDWEB"));
    }

    @Test
    void from_null列表_返回EMPTY等价的空集合() {
        TorrentBlacklist blacklist = TorrentBlacklist.from(null);

        assertTrue(blacklist.guidHashes().isEmpty());
        assertTrue(blacklist.releaseGroupsUpper().isEmpty());
    }

    @Test
    void from_空列表_返回EMPTY等价的空集合() {
        TorrentBlacklist blacklist = TorrentBlacklist.from(List.of());

        assertTrue(blacklist.guidHashes().isEmpty());
        assertTrue(blacklist.releaseGroupsUpper().isEmpty());
    }

    @Test
    void from_重复value去重() {
        TorrentBlacklist blacklist = TorrentBlacklist.from(List.of(
                rule(PtTorrentBlacklistPlus.TYPE_GUID, "abc123"),
                rule(PtTorrentBlacklistPlus.TYPE_GUID, "abc123")));

        assertEquals(1, blacklist.guidHashes().size());
    }

    @Test
    void from_GUID与发布组分别归类() {
        TorrentBlacklist blacklist = TorrentBlacklist.from(List.of(
                rule(PtTorrentBlacklistPlus.TYPE_GUID, "abc123"),
                rule(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP, "MTeam")));

        assertEquals(Set.of("abc123"), blacklist.guidHashes());
        assertEquals(Set.of("MTEAM"), blacklist.releaseGroupsUpper());
    }

    @Test
    void EMPTY_两个集合均为空() {
        assertTrue(TorrentBlacklist.EMPTY.guidHashes().isEmpty());
        assertTrue(TorrentBlacklist.EMPTY.releaseGroupsUpper().isEmpty());
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=TorrentBlacklistTest`
预期：COMPILATION ERROR，报错类似 `cannot find symbol: class TorrentBlacklist`

- [ ] **步骤 3：编写最少实现代码**

创建 `TorrentBlacklist.java`：

```java
package com.ruoyi.openliststrm.pt.filter;

import com.ruoyi.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 过滤引擎的黑名单输入：一次性从库里查好的全量规则，按类型归一化拆成两个集合。
 * 不可变，与 {@link FilterCriteria} 同一角色——引擎本身不读数据库，生效的黑名单
 * 由调用方（{@link com.ruoyi.openliststrm.pt.subscription.SubscriptionEngine}）
 * 一次性查好传入。
 *
 * @param guidHashes         GUID 黑名单命中集合（已是 SHA-256 哈希，无需再归一化大小写）
 * @param releaseGroupsUpper 发布组黑名单命中集合，统一大写
 * @author Jack
 */
public record TorrentBlacklist(Set<String> guidHashes, Set<String> releaseGroupsUpper) {

    /** 未配置任何黑名单时使用，供旧的两参 {@code evaluate}/{@code filter} 签名内部转调 */
    public static final TorrentBlacklist EMPTY = new TorrentBlacklist(Set.of(), Set.of());

    public TorrentBlacklist {
        guidHashes = guidHashes == null ? Set.of() : Set.copyOf(guidHashes);
        releaseGroupsUpper = releaseGroupsUpper == null ? Set.of() : Set.copyOf(releaseGroupsUpper);
    }

    /**
     * 从数据库全量规则构建。{@code null}/空列表都返回等价于 {@link #EMPTY} 的空集合。
     */
    public static TorrentBlacklist from(List<PtTorrentBlacklistPlus> rules) {
        if (rules == null || rules.isEmpty()) {
            return EMPTY;
        }
        Set<String> guids = new HashSet<>();
        Set<String> groups = new HashSet<>();
        for (PtTorrentBlacklistPlus rule : rules) {
            if (rule == null || rule.getValue() == null) {
                continue;
            }
            if (PtTorrentBlacklistPlus.TYPE_GUID.equals(rule.getType())) {
                guids.add(rule.getValue());
            } else if (PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP.equals(rule.getType())) {
                groups.add(rule.getValue().toUpperCase(Locale.ROOT));
            }
        }
        return new TorrentBlacklist(guids, groups);
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=TorrentBlacklistTest`
预期：`Tests run: 6, Failures: 0, Errors: 0`

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/filter/TorrentBlacklist.java \
        ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/filter/TorrentBlacklistTest.java
git commit -m "feat: 新增 TorrentBlacklist 值对象，供过滤引擎接收黑名单规则"
```

---

### 任务 4：`TorrentInfo.parsedReleaseGroup` 字段 + `TorrentFilterEngine` 黑名单判定

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/model/TorrentInfo.java:80-82`
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/filter/TorrentFilterEngine.java`（全文件，见下）
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/filter/TorrentFilterEngineFilterTest.java`（追加用例，不改现有 23 个）

**背景**：当前 `rejectReason()`（原文件第 108-147 行）判定顺序：做种数→体积上下限→免费→分辨率白名单→标题为空→排除词→包含词。本任务在最前插入 GUID 判定（最便宜、语义上最强确定性），在"标题为空"检查之后插入发布组判定（依赖 `parsedReleaseGroup`，该字段与 `excludeKeywords` 一样要求标题非空）。`evaluate()`/`filter()`（原文件第 37-64 行）新增 3 参重载，旧 2 参签名保留、内部转调传 `TorrentBlacklist.EMPTY`，现有 23 个测试零改动。

- [ ] **步骤 1：编写失败的测试**

在 `TorrentFilterEngineFilterTest.java` 文件末尾、最后一个测试方法 `白名单未命中_淘汰原因写明白名单内容与实际分辨率()`（原文件第 280-306 行）之后、类的闭合大括号（原文件第 307 行）之前新增：

```java
    // ---------- 种子/发布组黑名单（不改变以上任何现有用例的调用方式或断言） ----------

    @Test
    void GUID命中黑名单_淘汰原因包含拉黑() {
        TorrentInfo t = ok();
        t.setGuid("bad-guid");
        TorrentBlacklist blacklist = new TorrentBlacklist(
                Set.of(com.ruoyi.openliststrm.pt.indexer.GuidHasher.hash("bad-guid")), Set.of());

        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertFalse(verdicts.get(0).accepted());
        assertTrue(verdicts.get(0).rejectReason().contains("拉黑"));
    }

    @Test
    void GUID未命中黑名单_不受影响_走原有判定链() {
        TorrentInfo t = ok();
        t.setGuid("good-guid");
        TorrentBlacklist blacklist = new TorrentBlacklist(
                Set.of(com.ruoyi.openliststrm.pt.indexer.GuidHasher.hash("other-guid")), Set.of());

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertEquals(1, result.size());
    }

    @Test
    void 发布组命中黑名单_大小写不一致也命中_淘汰() {
        TorrentInfo t = ok();
        t.setParsedReleaseGroup("chdweb");
        TorrentBlacklist blacklist = new TorrentBlacklist(Set.of(), Set.of("CHDWEB"));

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertTrue(result.isEmpty());
    }

    @Test
    void 发布组未命中黑名单_不受影响() {
        TorrentInfo t = ok();
        t.setParsedReleaseGroup("someother");
        TorrentBlacklist blacklist = new TorrentBlacklist(Set.of(), Set.of("CHDWEB"));

        List<TorrentInfo> result = engine.filter(List.of(t),
                criteria(1, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertEquals(1, result.size());
    }

    @Test
    void 标题为空_即使parsedReleaseGroup非空_仍先被标题为空淘汰() {
        TorrentInfo t = torrent(null, 10, 100L, false);
        t.setParsedReleaseGroup("CHDWEB");
        TorrentBlacklist blacklist = new TorrentBlacklist(Set.of(), Set.of("CHDWEB"));

        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                criteria(0, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertTrue(verdicts.get(0).rejectReason().contains("标题为空"));
    }

    @Test
    void 同时命中GUID和做种数不足_淘汰原因是GUID命中() {
        TorrentInfo t = torrent("t", 1, 100L, false);
        t.setGuid("bad-guid");
        TorrentBlacklist blacklist = new TorrentBlacklist(
                Set.of(com.ruoyi.openliststrm.pt.indexer.GuidHasher.hash("bad-guid")), Set.of());

        List<TorrentFilterEngine.Verdict> verdicts = engine.evaluate(List.of(t),
                criteria(10, 0L, 0L, false, List.of(), List.of()), blacklist);

        assertTrue(verdicts.get(0).rejectReason().contains("拉黑"));
    }

    @Test
    void 未传黑名单参数_两参旧签名_行为与改动前完全一致() {
        List<TorrentInfo> result = engine.filter(List.of(ok()),
                criteria(1, 0L, 0L, false, List.of(), List.of()));

        assertEquals(1, result.size());
    }

    @Test
    void TorrentBlacklistEMPTY_与两参旧签名结果一致() {
        List<TorrentInfo> withEmpty = engine.filter(List.of(ok()),
                criteria(1, 0L, 0L, false, List.of(), List.of()), TorrentBlacklist.EMPTY);
        List<TorrentInfo> withoutBlacklist = engine.filter(List.of(ok()),
                criteria(1, 0L, 0L, false, List.of(), List.of()));

        assertEquals(withoutBlacklist.size(), withEmpty.size());
    }
```

在文件顶部导入区（原文件第 1-14 行）追加：

```java
import java.util.Set;
```

（`TorrentBlacklist` 与本测试类同包 `com.ruoyi.openliststrm.pt.filter`，无需导入；`TorrentInfo` 已导入。）

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=TorrentFilterEngineFilterTest`
预期：COMPILATION ERROR，报错类似 `method evaluate(List,FilterCriteria,TorrentBlacklist) not found` 与 `cannot find symbol: method setParsedReleaseGroup`

- [ ] **步骤 3：编写最少实现代码**

在 `TorrentInfo.java` 里 `parsedSource` 字段（原文件第 80-81 行）之后、`parsedPubTime` 字段（原文件第 83-84 行）之前插入：

```java
    /** 解析出的媒介来源，如 WEB-DL、BluRay、Remux */
    private String parsedSource;

    /** 解析出的发布组，如 CHDWEB；未解析出时为 null */
    private String parsedReleaseGroup;

    /** 解析后的发布时间；原始字符串见 {@link #pubDate}，本字段不变动 pubDate */
    private Date parsedPubTime;
```

`TorrentFilterEngine.java` 整体替换为：

```java
package com.ruoyi.openliststrm.pt.filter;

import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.pt.indexer.GuidHasher;
import com.ruoyi.openliststrm.pt.model.TorrentInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 种子过滤与择优引擎。纯逻辑，不读数据库、不发网络请求——生效条件由调用方
 * 通过 {@link FilterCriteria} 传入（见 {@link FilterCriteriaFactory}），
 * 黑名单规则由调用方通过 {@link TorrentBlacklist} 传入。
 *
 * @author Jack
 */
@Slf4j
@Component
public class TorrentFilterEngine {

    /**
     * 候选种子的过滤裁决：{@code rejectReason} 为 null 表示通过。
     */
    public record Verdict(TorrentInfo torrent, String rejectReason) {
        public boolean accepted() {
            return rejectReason == null;
        }
    }

    /**
     * 逐条给出候选的过滤裁决与具体原因，供调用方落库供前端排查
     * （见 {@link com.ruoyi.openliststrm.pt.subscription.SubscriptionEngine}）。
     * {@link #filter} 基于本方法实现，两者的淘汰判定逻辑保证一致。
     * <p>不传黑名单时等价于 {@link TorrentBlacklist#EMPTY}，行为与改动前完全一致。</p>
     */
    public List<Verdict> evaluate(List<TorrentInfo> candidates, FilterCriteria criteria) {
        return evaluate(candidates, criteria, TorrentBlacklist.EMPTY);
    }

    /**
     * 三参重载：额外传入生效的种子/发布组黑名单。
     */
    public List<Verdict> evaluate(List<TorrentInfo> candidates, FilterCriteria criteria, TorrentBlacklist blacklist) {
        List<Verdict> verdicts = new ArrayList<>();
        for (TorrentInfo torrent : candidates) {
            verdicts.add(new Verdict(torrent, rejectReason(torrent, criteria, blacklist)));
        }
        return verdicts;
    }

    /**
     * 硬性过滤：淘汰不满足条件的候选，保留原顺序。
     * <p>
     * 被淘汰的种子不落库，只记 debug 日志并带上具体原因（哪条规则、阈值、实际值）——
     * 这些日志是后续调优过滤规则的主要素材。
     * </p>
     *
     * @return 新的可变列表，调用方修改它不会影响入参
     */
    public List<TorrentInfo> filter(List<TorrentInfo> candidates, FilterCriteria criteria) {
        return filter(candidates, criteria, TorrentBlacklist.EMPTY);
    }

    /**
     * 三参重载：额外传入生效的种子/发布组黑名单。
     */
    public List<TorrentInfo> filter(List<TorrentInfo> candidates, FilterCriteria criteria, TorrentBlacklist blacklist) {
        List<TorrentInfo> survivors = new ArrayList<>();
        for (Verdict verdict : evaluate(candidates, criteria, blacklist)) {
            if (verdict.accepted()) {
                survivors.add(verdict.torrent());
            } else {
                log.debug("种子被过滤：{} —— {}", verdict.torrent().getTitle(), verdict.rejectReason());
            }
        }
        return survivors;
    }

    /**
     * 从候选中挑出最优的一个。
     * <p>
     * 按 {@link FilterCriteria#sortPriority()} 的维度顺序，把各维度的比较器用
     * thenComparing 串联后取排在最前的那个。维度顺序由配置决定，因此同一批候选
     * 在不同配置下会选出不同的赢家——这正是「排序权重可调」的实现方式。
     * </p>
     * <p>
     * 全部维度都判同级时返回列表中的第一个（比较过程不改变入参列表的顺序）。
     * </p>
     *
     * @return 最优候选；候选为空时返回 null
     */
    public TorrentInfo pickBest(List<TorrentInfo> candidates, FilterCriteria criteria) {
        if (candidates.isEmpty()) {
            return null;
        }
        Comparator<TorrentInfo> comparator = null;
        for (SortDimension dimension : criteria.sortPriority()) {
            Comparator<TorrentInfo> next = dimension.comparator(criteria);
            comparator = (comparator == null) ? next : comparator.thenComparing(next);
        }
        if (comparator == null) {
            // FilterCriteria 保证 sortPriority 非空，这里只是防御
            return candidates.get(0);
        }

        TorrentInfo best = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            // 严格小于才替换，保证同级时保留先出现的那个
            if (comparator.compare(candidates.get(i), best) < 0) {
                best = candidates.get(i);
            }
        }
        log.debug("择优结果：{}（候选 {} 个，维度顺序 {}）",
                best.getTitle(), candidates.size(), criteria.sortPriority());
        return best;
    }

    /**
     * 返回淘汰原因；返回 null 表示通过。判定顺序：
     * GUID 黑名单 → 做种数 → 体积上下限 → 免费 → 分辨率白名单 → 标题为空
     * → 发布组黑名单 → 排除词 → 包含词。
     * <p>
     * GUID 判定放最前：不依赖标题解析、不依赖任何统计字段，是最便宜的判定，
     * 而且"拉黑一个具体种子"是用户的强确定性意图，语义上应该比软性阈值更早生效。
     * 发布组判定放在"标题为空"之后：该判定依赖 {@code parsedReleaseGroup}，
     * 这个字段本质上是标题解析的产物，与 excludeKeywords/includeKeywords 一样
     * 要求标题非空。
     * </p>
     */
    private String rejectReason(TorrentInfo torrent, FilterCriteria criteria, TorrentBlacklist blacklist) {
        if (!blacklist.guidHashes().isEmpty()) {
            String guid = torrent.getGuid();
            if (StringUtils.isNotBlank(guid) && blacklist.guidHashes().contains(GuidHasher.hash(guid))) {
                return "该种子已被手动拉黑（GUID）";
            }
        }
        if (torrent.getSeeders() < criteria.minSeeders()) {
            return "做种数 " + torrent.getSeeders() + " 低于下限 " + criteria.minSeeders();
        }
        if (criteria.minSize() > 0 && torrent.getSize() < criteria.minSize()) {
            return "体积 " + torrent.getSize() + " 小于下限 " + criteria.minSize();
        }
        if (criteria.maxSize() > 0 && torrent.getSize() > criteria.maxSize()) {
            return "体积 " + torrent.getSize() + " 超过上限 " + criteria.maxSize();
        }
        if (criteria.freeOnly() && !torrent.isFree()) {
            return "非免费种(下载量系数 " + torrent.getDownloadVolumeFactor() + ")，而配置为仅要免费";
        }
        List<String> whitelist = criteria.resolutionWhitelist();
        if (!whitelist.isEmpty()) {
            String resolution = torrent.getParsedResolution();
            // 解析不出分辨率时无法判定是否在白名单内，不能放行；只有白名单为空(不限)才不受此约束
            if (StringUtils.isBlank(resolution) || !containsIgnoreCase(whitelist, resolution.trim())) {
                String actual = StringUtils.isBlank(resolution) ? "(未知)" : resolution;
                return "分辨率 " + actual + " 不在白名单 " + whitelist + " 内";
            }
        }

        String title = torrent.getTitle();
        // 标题缺失的条目无法做关键词判定，一律淘汰而非放行
        if (StringUtils.isBlank(title)) {
            return "标题为空，无法判定";
        }

        if (!blacklist.releaseGroupsUpper().isEmpty()) {
            String group = torrent.getParsedReleaseGroup();
            if (StringUtils.isNotBlank(group) && blacklist.releaseGroupsUpper().contains(group.toUpperCase(Locale.ROOT))) {
                return "发布组「" + group + "」已被手动拉黑";
            }
        }

        String lower = title.toLowerCase(Locale.ROOT);

        for (String keyword : criteria.excludeKeywords()) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return "命中排除词「" + keyword + "」";
            }
        }
        if (!criteria.includeKeywords().isEmpty() && !containsAny(lower, criteria.includeKeywords())) {
            return "未命中任何包含词 " + criteria.includeKeywords();
        }
        return null;
    }

    private boolean containsAny(String lowerTitle, List<String> keywords) {
        for (String keyword : keywords) {
            if (lowerTitle.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** 白名单命中判定：整词相等而非子串包含，大小写不敏感——索引器标题里 1080P 与 1080p 都出现过 */
    private boolean containsIgnoreCase(List<String> whitelist, String resolution) {
        for (String allowed : whitelist) {
            if (allowed.equalsIgnoreCase(resolution)) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=TorrentFilterEngineFilterTest,TorrentFilterEnginePickBestTest`
预期：`Tests run: 31, Failures: 0, Errors: 0`（`TorrentFilterEngineFilterTest` 原有 23 个 + 本任务新增 8 个，`TorrentFilterEnginePickBestTest` 不受影响）

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/model/TorrentInfo.java \
        ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/filter/TorrentFilterEngine.java \
        ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/filter/TorrentFilterEngineFilterTest.java
git commit -m "feat: TorrentFilterEngine 支持 GUID/发布组黑名单判定"
```

---

### 任务 5：`SubscriptionEngine` 集成黑名单查询与传递

**文件：**
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java`（全文件级改动，见下）
- 测试：`ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngineTest.java`

**背景**：构造器新增 `IPtTorrentBlacklistPlusService blacklistService` 参数（放在现有 9 个参数之后）；`process()`（原文件第 100-134 行）与 `pushBest()`（原文件第 142-150 行）顶部各查一次全量黑名单（与 `globalConfig` 同一位置、同一生命周期），构建 `TorrentBlacklist` 后经 `handleGroup`（原文件第 155-160 行签名、第 180 行调用点）传给 `filterEngine.evaluate`；`fillParsed()`（原文件第 254-266 行）补一行 `parsedReleaseGroup` 赋值。由于构造器签名变化，`SubscriptionEngineTest.java` 里所有 31 个现有用例共用的 `setUp()`（原文件第 60-90 行）唯一构造点需要同步更新。

- [ ] **步骤 1：编写失败的测试**

在 `SubscriptionEngineTest.java` 顶部导入区（原文件第 1-43 行）追加：

```java
import com.ruoyi.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
```

在 `@Mock` 字段声明区（原文件第 49-56 行）追加：

```java
    @Mock private SearchLogService searchLogService;
    @Mock private IPtTorrentBlacklistPlusService blacklistService;
```

（保留原有的 `@Mock private SearchLogService searchLogService;` 一行，只在其后新增一行 `blacklistService`。）

把 `setUp()` 方法（原文件第 60-90 行）里的构造调用：

```java
        engine = new SubscriptionEngine(
                subscriptionService, episodeService, recordService, downloaderService,
                filterConfigService, downloaderClientFactory,
                new TorrentFilterEngine(), new SubscriptionMatcher(), searchLogService);
```

替换为：

```java
        engine = new SubscriptionEngine(
                subscriptionService, episodeService, recordService, downloaderService,
                filterConfigService, downloaderClientFactory,
                new TorrentFilterEngine(), new SubscriptionMatcher(), searchLogService, blacklistService);
        when(blacklistService.list()).thenReturn(new ArrayList<>());
```

在文件末尾、最后一个测试方法 `推送失败_记录摘要日志()`（原文件第 681-695 行）之后、类的闭合大括号（原文件第 696 行）之前新增：

```java

    // ---------- 黑名单 ----------

    @Test
    void 黑名单命中GUID_淘汰不推送() throws Exception {
        when(subscriptionService.listActive()).thenReturn(List.of(tvSub(10, "Some Show", 1, 1)));
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        PtTorrentBlacklistPlus rule = new PtTorrentBlacklistPlus();
        rule.setType(PtTorrentBlacklistPlus.TYPE_GUID);
        rule.setValue(com.ruoyi.openliststrm.pt.indexer.GuidHasher.hash("g1"));
        when(blacklistService.list()).thenReturn(List.of(rule));

        int pushed = engine.process(List.of(torrent("Some.Show.S01E01.1080p", "g1", 10, "1080p")));

        assertEquals(0, pushed);
        verify(downloaderClient, never()).addTorrent(any(), anyString(), anyString(), anyString());
        ArgumentCaptor<List> verdicts = ArgumentCaptor.forClass(List.class);
        verify(searchLogService).recordVerdicts(eq(10), eq(1), eq(SearchLogService.SOURCE_RSS), verdicts.capture());
        TorrentFilterEngine.Verdict verdict = (TorrentFilterEngine.Verdict) verdicts.getValue().get(0);
        assertFalse(verdict.accepted());
        assertTrue(verdict.rejectReason().contains("拉黑"));
    }

    @Test
    void pushBest路径_黑名单命中发布组_淘汰不推送() {
        when(episodeService.listBySubscription(10)).thenReturn(List.of(episode(101, 1, "MISSING")));
        PtTorrentBlacklistPlus rule = new PtTorrentBlacklistPlus();
        rule.setType(PtTorrentBlacklistPlus.TYPE_RELEASE_GROUP);
        rule.setValue("CHDWEB");
        when(blacklistService.list()).thenReturn(List.of(rule));
        PtSubscriptionPlus sub = tvSub(10, "Some Show", 1, 1);

        TorrentInfo candidate = torrent("Show.Name.S01E01.1080p.WEB-DL.H264-CHDWEB", "g1", 10, "1080p");
        boolean pushed = engine.pushBest(sub, 1, List.of(candidate));

        assertFalse(pushed);
        verify(searchLogService).recordVerdicts(eq(10), eq(1), eq(SearchLogService.SOURCE_SUPPLEMENT), any(List.class));
    }
```

- [ ] **步骤 2：运行测试验证失败**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=SubscriptionEngineTest`
预期：COMPILATION ERROR，报错类似 `constructor SubscriptionEngine cannot be applied to given types`（构造器还没有第 10 个参数）

- [ ] **步骤 3：编写最少实现代码**

`SubscriptionEngine.java` 导入区（原文件第 1-35 行）新增：

```java
import com.ruoyi.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
```

紧跟 `import com.ruoyi.openliststrm.pt.downloader.DownloaderClientFactory;`（原文件第 15 行）之后插入：

```java
import com.ruoyi.openliststrm.pt.downloader.DownloaderClientFactory;
import com.ruoyi.openliststrm.pt.filter.FilterCriteria;
import com.ruoyi.openliststrm.pt.filter.FilterCriteriaFactory;
import com.ruoyi.openliststrm.pt.filter.TorrentBlacklist;
import com.ruoyi.openliststrm.pt.filter.TorrentFilterEngine;
```

字段声明区（原文件第 58-66 行）追加一个字段：

```java
    private final TorrentFilterEngine filterEngine;
    private final SubscriptionMatcher matcher;
    private final SearchLogService searchLogService;
    private final IPtTorrentBlacklistPlusService blacklistService;
```

构造器（原文件第 75-93 行）整体替换为：

```java
    public SubscriptionEngine(IPtSubscriptionPlusService subscriptionService,
                              IPtSubscriptionEpisodePlusService episodeService,
                              IPtDownloadRecordPlusService recordService,
                              IPtDownloaderPlusService downloaderService,
                              IPtFilterConfigPlusService filterConfigService,
                              DownloaderClientFactory downloaderClientFactory,
                              TorrentFilterEngine filterEngine,
                              SubscriptionMatcher matcher,
                              SearchLogService searchLogService,
                              IPtTorrentBlacklistPlusService blacklistService) {
        this.subscriptionService = subscriptionService;
        this.episodeService = episodeService;
        this.recordService = recordService;
        this.downloaderService = downloaderService;
        this.filterConfigService = filterConfigService;
        this.downloaderClientFactory = downloaderClientFactory;
        this.filterEngine = filterEngine;
        this.matcher = matcher;
        this.searchLogService = searchLogService;
        this.blacklistService = blacklistService;
    }
```

`process()` 方法（原文件第 100-134 行）整体替换为：

```java
    public int process(List<TorrentInfo> torrents) {
        List<PtSubscriptionPlus> subscriptions = subscriptionService.listActive();
        if (subscriptions.isEmpty() || torrents.isEmpty()) {
            return 0;
        }
        PtFilterConfigPlus globalConfig = filterConfigService.getConfig();
        TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());

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
                    enabledDownloaders, downloaderLoadCache, SearchLogService.SOURCE_RSS, blacklist)) {
                pushed++;
            }
        }
        return pushed;
    }
```

`pushBest()` 方法（原文件第 142-150 行）整体替换为：

```java
    public boolean pushBest(PtSubscriptionPlus sub, int episode, List<TorrentInfo> candidates) {
        PtFilterConfigPlus globalConfig = filterConfigService.getConfig();
        TorrentBlacklist blacklist = TorrentBlacklist.from(blacklistService.list());
        MatchResult match = new MatchResult(sub, episode);
        Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache = new LinkedHashMap<>();
        List<PtDownloaderPlus> enabledDownloaders = loadEnabledDownloaders();
        Map<Integer, Long> downloaderLoadCache = loadDownloaderLoadCounts(enabledDownloaders);
        return handleGroup(match, candidates, globalConfig, episodeCache,
                enabledDownloaders, downloaderLoadCache, SearchLogService.SOURCE_SUPPLEMENT, blacklist);
    }
```

`handleGroup()` 方法签名与内部过滤调用（原文件第 155-160 行签名、第 179-180 行）替换为：

```java
    boolean handleGroup(MatchResult match, List<TorrentInfo> candidates,
                                PtFilterConfigPlus globalConfig,
                                Map<Integer, List<PtSubscriptionEpisodePlus>> episodeCache,
                                List<PtDownloaderPlus> enabledDownloaders,
                                Map<Integer, Long> downloaderLoadCache,
                                String source,
                                TorrentBlacklist blacklist) {
```

（方法体第 161-178 行不变；第 179-180 行）：

```java
        FilterCriteria criteria = FilterCriteriaFactory.build(globalConfig, sub.getFilterOverride());
        List<TorrentFilterEngine.Verdict> verdicts = filterEngine.evaluate(fresh, criteria, blacklist);
```

`fillParsed()` 方法（原文件第 254-266 行）整体替换为：

```java
    /** 用本地解析结果填充种子的 parsedXxx 字段，不发任何网络请求 */
    void fillParsed(TorrentInfo torrent) {
        MediaInfo info = mediaParser.parseLocal(torrent.getTitle());
        // 注意：parseLocal 不做 TMDb 富化，MediaInfo.title 恒为 null
        // （TitleProcessor.processTitle 只写 originalTitle/englishTitle，见该类第46-48行的注释代码）。
        // 必须用 originalTitle，否则本地解析出的种子标题永远匹配不到任何订阅。
        torrent.setParsedTitle(info.getOriginalTitle());
        torrent.setParsedTitleEn(info.getEnglishTitle());
        torrent.setParsedYear(info.getYear());
        torrent.setParsedSeason(toInt(info.getSeason()));
        torrent.setParsedEpisode(toInt(info.getEpisode()));
        torrent.setParsedResolution(info.getResolution());
        torrent.setParsedSource(info.getSource());
        torrent.setParsedReleaseGroup(info.getReleaseGroup());
    }
```

- [ ] **步骤 4：运行测试验证通过**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=SubscriptionEngineTest`
预期：`Tests run: 33, Failures: 0, Errors: 0`（原有 31 个 + 本任务新增 2 个）

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngine.java \
        ruoyi-openliststrm/src/test/java/com/ruoyi/openliststrm/pt/subscription/SubscriptionEngineTest.java
git commit -m "feat: SubscriptionEngine 集成种子/发布组黑名单查询与过滤"
```

---

### 任务 6：下载记录页拉黑按钮后端端点

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/req/BlacklistReq.java`
- 修改：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtDownloadRecordRestController.java:1-83`（全文件）

**背景**：新增 `POST /{id}/blacklist-guid`、`POST /{id}/blacklist-release-group`，转调 `blacklistService` 对应方法，`IllegalArgumentException` 转 `Result.error`——写法与本文件已有的 `retry()`（原文件第 63-70 行）完全一致。这个改动没有独立可测试的新逻辑（真正的业务逻辑已在任务 2 的 `PtTorrentBlacklistPlusServiceImplTest` 里覆盖），本仓库对同类"薄转发 + catch IllegalArgumentException"的端点（`PtIndexerRestController.test()/categories()`、`PtDownloaderRestController.test()`）均未写专门的控制器测试，本任务遵循同一惯例，用编译验证代替，由任务 11 的全量测试与手动验证兜底。

- [ ] **步骤 1：创建请求体 DTO**

创建 `BlacklistReq.java`：

```java
package com.ruoyi.openliststrm.req;

import lombok.Data;

/**
 * 拉黑操作的可选请求体："拉黑该种子"/"拉黑该发布组" 两个端点共用。
 *
 * @author Jack
 */
@Data
public class BlacklistReq {

    /** 拉黑原因，可选；不填则使用 Service 层默认文案 */
    private String reason;
}
```

- [ ] **步骤 2：编译验证（新文件尚未被引用，先确认能独立编译）**

运行：`mvn compile -pl ruoyi-openliststrm -am -q`
预期：无输出、退出码 0

- [ ] **步骤 3：`PtDownloadRecordRestController` 新增两个端点**

`PtDownloadRecordRestController.java` 导入区（原文件第 1-24 行）追加：

```java
import com.ruoyi.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import com.ruoyi.openliststrm.req.BlacklistReq;
import org.springframework.web.bind.annotation.RequestBody;
```

在 `@Autowired private DownloadRecordAdminService adminService;`（原文件第 38-39 行）之后追加：

```java
    @Autowired
    private DownloadRecordAdminService adminService;

    @Autowired
    private IPtTorrentBlacklistPlusService blacklistService;
```

在 `batchRetry()` 方法（原文件第 75-82 行）之后、类的闭合大括号（原文件第 83 行）之前新增：

```java

    /**
     * 拉黑该下载记录对应的种子（GUID 维度）。记录不存在时返回错误；已拉黑过时幂等返回 false。
     */
    @PostMapping("/{id}/blacklist-guid")
    public Result<Boolean> blacklistGuid(@PathVariable("id") Integer id,
                                          @RequestBody(required = false) BlacklistReq req) {
        try {
            return Result.success(blacklistService.blockRecordGuid(id, req == null ? null : req.getReason()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    /**
     * 拉黑该下载记录标题解析出的发布组。标题解析不出发布组时返回错误；已拉黑过时幂等返回 false。
     */
    @PostMapping("/{id}/blacklist-release-group")
    public Result<Boolean> blacklistReleaseGroup(@PathVariable("id") Integer id,
                                                  @RequestBody(required = false) BlacklistReq req) {
        try {
            return Result.success(blacklistService.blockRecordReleaseGroup(id, req == null ? null : req.getReason()));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
```

- [ ] **步骤 4：运行测试验证通过（回归 + 编译）**

运行：`mvn test -pl ruoyi-openliststrm -am -Dtest=PtSubscriptionRestControllerTest`
预期：`Tests run` 全部 PASS（确认控制器包整体仍能正常编译装配，未破坏既有回归测试）

- [ ] **步骤 5：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/req/BlacklistReq.java \
        ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtDownloadRecordRestController.java
git commit -m "feat: 下载记录页新增拉黑该种子/拉黑该发布组端点"
```

---

### 任务 7：黑名单管理页后端端点

**文件：**
- 创建：`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtTorrentBlacklistRestController.java`

**背景**：`extends BaseCrudRestController<IPtTorrentBlacklistPlusService, PtTorrentBlacklistPlus>` 复用 `list`/`getById`/`delete`；`buildQueryWrapper` 支持按 `type` 精确、`displayValue` 模糊查询。`BaseCrudRestController.add()`/`edit()`（`ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/BaseCrudRestController.java:59-74`）不会 catch 任何异常，而任务 2 的 `save()`/`updateById()` 重载对 `type=GUID` 会抛 `IllegalArgumentException`——必须在本控制器里覆写 `add()`/`edit()` 并重新声明 `@PostMapping`/`@PutMapping`（Java 覆写方法不会继承父类方法上的注解，必须显式重新声明，否则该端点会从路由表里消失）来捕获并转成 `Result.error`。本类同样遵循任务 6 的惯例，不写专门的控制器单元测试，用编译验证 + 任务 11 的手动验证兜底。

- [ ] **步骤 1：创建控制器**

创建 `PtTorrentBlacklistRestController.java`：

```java
package com.ruoyi.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.core.domain.Result;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PT 种子/发布组手动黑名单 REST API 控制器。
 * <p>
 * 管理页新增/编辑只支持 {@code RELEASE_GROUP} 类型——{@code GUID} 类型的规则只能通过
 * {@link PtDownloadRecordRestController#blacklistGuid} 由后端直接从已知的
 * {@code guidHash} 生成，不经过用户手填。这条限制由 Service 层
 * （{@code PtTorrentBlacklistPlusServiceImpl.save()/updateById()}）强制执行，
 * 本控制器负责把该校验异常转成前端能看懂的错误信息。
 * </p>
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/pt-torrent-blacklists")
public class PtTorrentBlacklistRestController extends BaseCrudRestController<IPtTorrentBlacklistPlusService, PtTorrentBlacklistPlus> {

    @Override
    protected Wrapper<PtTorrentBlacklistPlus> buildQueryWrapper(PtTorrentBlacklistPlus entity) {
        LambdaQueryWrapper<PtTorrentBlacklistPlus> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(entity.getType())) {
            wrapper.eq(PtTorrentBlacklistPlus::getType, entity.getType());
        }
        if (StringUtils.isNotBlank(entity.getDisplayValue())) {
            wrapper.like(PtTorrentBlacklistPlus::getDisplayValue, entity.getDisplayValue());
        }
        wrapper.orderByDesc(PtTorrentBlacklistPlus::getId);
        return wrapper;
    }

    @Override
    @PostMapping
    public Result<Void> add(@RequestBody PtTorrentBlacklistPlus entity) {
        try {
            return super.add(entity);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Override
    @PutMapping
    public Result<Void> edit(@RequestBody PtTorrentBlacklistPlus entity) {
        try {
            return super.edit(entity);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
```

- [ ] **步骤 2：编译 + 全量后端测试验证**

运行：`mvn test -pl ruoyi-openliststrm -am`
预期：`BUILD SUCCESS`，无 `Tests in error`/`Tests failed`（本任务新增控制器无独立测试，靠全量回归确认未破坏任何既有装配）

- [ ] **步骤 3：Commit**

```bash
git add ruoyi-openliststrm/src/main/java/com/ruoyi/openliststrm/controller/api/PtTorrentBlacklistRestController.java
git commit -m "feat: 新增 PT 黑名单管理页 REST 端点"
```

---

### 任务 8：前端 API 层 + composable（`ptTorrentBlacklist`）

**文件：**
- 创建：`openlist-web/src/api/openlist/ptTorrentBlacklist.ts`
- 创建：`openlist-web/src/composables/usePtTorrentBlacklist.ts`

**背景**：仿 `ptIndexer.ts`/`usePtIndexer.ts`，基于 `useTaskList` 封装列表+新增+删除。管理页新增表单只暴露"发布组"一种类型（不提供 GUID 选项），`initForm()` 固定 `type: 'RELEASE_GROUP'`。`useTaskList` 的 `TaskListApiConfig` 要求 `updateApi` 为必填字段（`openlist-web/src/composables/useTaskList.ts:11`），后端也确实支持 PUT（任务 7），因此仍然定义 `updatePtTorrentBlacklistApi` 满足类型要求，即使本页面 UI 不提供"编辑"按钮触发它。这两个文件是纯请求封装 + 状态编排，仓库里 `usePtIndexer.ts`/`api/openlist/ptIndexer.ts` 均无独立单测（`useTaskList.ts` 本身也没有专门测试），本任务遵循同一惯例，用 `vue-tsc` 类型检查代替单元测试，由任务 11 兜底。

- [ ] **步骤 1：创建 API 层**

创建 `ptTorrentBlacklist.ts`：

```ts
import request from '@/api/request'
import type { PageResult, SearchParams } from '@/types'

export interface PtTorrentBlacklistQuery extends SearchParams {
  type?: string
  displayValue?: string
}

export function getPtTorrentBlacklistListApi(params: PtTorrentBlacklistQuery) {
  return request.get<any, PageResult<any>>('/openliststrm/pt-torrent-blacklists', { params })
}

/** 新增：管理页只支持发布组类型，服务层会拒绝 type=GUID 的请求 */
export function addPtTorrentBlacklistApi(data: any) {
  return request.post('/openliststrm/pt-torrent-blacklists', data)
}

/** 修改：同样仅支持发布组类型；管理页当前不提供编辑入口，保留此接口与后端能力对齐 */
export function updatePtTorrentBlacklistApi(data: any) {
  return request.put('/openliststrm/pt-torrent-blacklists', data)
}

export function deletePtTorrentBlacklistApi(id: number) {
  return request.delete(`/openliststrm/pt-torrent-blacklists/${id}`)
}
```

- [ ] **步骤 2：创建 composable**

创建 `usePtTorrentBlacklist.ts`：

```ts
import { ref } from 'vue'
import { useTaskList } from './useTaskList'
import {
  getPtTorrentBlacklistListApi,
  addPtTorrentBlacklistApi,
  updatePtTorrentBlacklistApi,
  deletePtTorrentBlacklistApi
} from '@/api/openlist/ptTorrentBlacklist'
import type { PtTorrentBlacklistQuery } from '@/api/openlist/ptTorrentBlacklist'

/**
 * PT 种子/发布组黑名单 composable。
 * 管理页只暴露"新增发布组规则"与"删除"，不提供修改入口——GUID 类型的规则只能通过
 * 下载记录页的拉黑按钮产生，管理页新增一律按发布组类型处理，后端会拒绝 type=GUID 的写请求。
 */
export function usePtTorrentBlacklist() {
  const base = useTaskList<PtTorrentBlacklistQuery>({
    listApi: getPtTorrentBlacklistListApi,
    addApi: addPtTorrentBlacklistApi,
    updateApi: updatePtTorrentBlacklistApi,
    deleteApi: deletePtTorrentBlacklistApi,
    idField: 'id',
    initForm: () => ({
      id: undefined,
      type: 'RELEASE_GROUP',
      value: undefined,
      reason: undefined
    }),
    rules: {
      value: [{ required: true, message: '发布组名不能为空', trigger: 'blur' }]
    },
    defaultQuery: {
      type: undefined,
      displayValue: undefined
    }
  })

  const searchCollapsed = ref(true)

  base.getList()

  return { ...base, searchCollapsed }
}
```

- [ ] **步骤 3：类型检查验证**

运行：`cd openlist-web && npx vue-tsc --noEmit`
预期：无类型错误输出

- [ ] **步骤 4：Commit**

```bash
git add openlist-web/src/api/openlist/ptTorrentBlacklist.ts \
        openlist-web/src/composables/usePtTorrentBlacklist.ts
git commit -m "feat: 新增 PT 黑名单前端 API 层与 composable"
```

---

### 任务 9：前端管理页面 + 路由注册 + 图标映射

**文件：**
- 创建：`openlist-web/src/views/openlist/ptTorrentBlacklist/index.vue`
- 修改：`openlist-web/src/router/index.ts:108`（`componentMap` 追加一项）
- 修改：`openlist-web/src/composables/useMenuIcon.ts:1-40`

**背景**：`sys_menu.component` 由后端 `SysMenu.getComponentPath()`（`ruoyi-common/src/main/java/com/ruoyi/common/core/domain/entity/SysMenu.java:208-221`）从 `url` 派生，规则是去掉开头 `/` 再补 `/index`——任务 1 里 `url='/openlist/ptTorrentBlacklist'` 会派生出 `component='openlist/ptTorrentBlacklist/index'`。前端 `router/index.ts` 的 `componentMap`（原文件第 55-114 行）按这个 key 查表决定渲染哪个组件，不注册就会落到 404 页面——这是设计文档第 6 节没有提到、但从实际路由派生规则推出的必要改动。本页面无独立于 `ptDownloadRecord` 页面的移动端适配需求（发布组黑名单管理属于低频配置操作），仿 `ptFilterConfig/index` 的写法只注册单一组件、不做 PC/移动端拆分。`fa fa-ban` 是新图标类名，需要同批补充 `useMenuIcon.ts` 映射，否则会重蹈 `20260428`/`20260737` 那次"图标类名没进 iconMap 导致侧边栏图标不显示"的坑（设计文档第 3 节已指出）。

- [ ] **步骤 1：`useMenuIcon.ts` 新增图标映射**

导入区（原文件第 1-6 行）替换为：

```ts
import {
  Setting, Document, Picture, Monitor, Tools, Calendar, Coin, Promotion,
  Watermelon, Menu as IconMenu, VideoPlay, RefreshRight, EditPen,
  FolderOpened, DocumentCopy, MagicStick, Connection, Download, Film, Filter,
  TrendCharts, CircleClose
} from '@element-plus/icons-vue'
```

`iconMap`（原文件第 13-40 行）最后一项 `'fa fa-bar-chart': TrendCharts` 之后追加：

```ts
  'fa fa-bar-chart': TrendCharts,
  'fa fa-ban': CircleClose
}
```

- [ ] **步骤 2：`router/index.ts` 的 `componentMap` 追加页面注册**

在 `'openlist/ptStatsDashboard/index': () => import('@/views/openlist/ptStatsDashboard/index.vue')`（原文件第 113 行）之后追加：

```ts
  'openlist/ptStatsDashboard/index': () => import('@/views/openlist/ptStatsDashboard/index.vue'),
  'openlist/ptTorrentBlacklist/index': () => import('@/views/openlist/ptTorrentBlacklist/index.vue')
}
```

- [ ] **步骤 3：创建管理页面**

创建 `views/openlist/ptTorrentBlacklist/index.vue`：

```html
<template>
  <div class="page-container">
    <el-card class="search-card" v-if="showSearch">
      <el-form :model="queryParams" ref="queryRef" :inline="true" label-width="80px">
        <el-form-item label="类型" prop="type">
          <el-select v-model="queryParams.type" placeholder="类型" clearable :style="{ width: '140px' }">
            <el-option label="种子(GUID)" value="GUID" />
            <el-option label="发布组" value="RELEASE_GROUP" />
          </el-select>
        </el-form-item>
        <el-form-item label="展示内容" prop="displayValue">
          <el-input v-model="queryParams.displayValue" placeholder="标题或发布组名" clearable @keyup.enter="handleQuery" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleQuery">
            <el-icon><Search /></el-icon> 搜索
          </el-button>
          <el-button @click="resetQuery">
            <el-icon><Refresh /></el-icon> 重置
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <el-button type="primary" @click="handleAdd('新增发布组黑名单')">
            <el-icon><Plus /></el-icon> 新增发布组规则
          </el-button>
        </div>
        <el-button text @click="showSearch = !showSearch">
          <el-icon><Filter /></el-icon>
          {{ showSearch ? '隐藏搜索' : '显示搜索' }}
        </el-button>
      </div>

      <div class="card-grid" v-loading="loading">
        <div v-for="item in taskList" :key="item.id" class="item-card">
          <div class="card-header">
            <span class="card-title" :title="item.displayValue">{{ item.displayValue || '(无展示内容)' }}</span>
            <el-tag :type="item.type === 'GUID' ? 'danger' : 'warning'" size="small">
              {{ item.type === 'GUID' ? '种子' : '发布组' }}
            </el-tag>
          </div>
          <div class="card-body">
            <div class="card-row">
              <span class="label">匹配键</span>
              <span class="value" :title="item.value">{{ item.type === 'GUID' ? shortHash(item.value) : item.value }}</span>
            </div>
            <div class="card-row">
              <span class="label">原因</span>
              <span class="value">{{ item.reason || '-' }}</span>
            </div>
            <div class="card-row">
              <span class="label">创建时间</span>
              <span class="value">{{ item.createTime || '-' }}</span>
            </div>
          </div>
          <div class="card-footer">
            <el-button link type="danger" @click="handleDelete(item)">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </div>
        </div>
        <el-empty v-if="!loading && taskList.length === 0" description="暂无黑名单规则" />
      </div>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="queryParams.pageNum"
          v-model:page-size="queryParams.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @current-change="getList"
          @size-change="getList"
        />
      </div>
    </el-card>

    <el-dialog v-model="open" :title="dialogTitle" width="480px" append-to-body class="modern-dialog">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="发布组名" prop="value">
          <el-input v-model="form.value" placeholder="如 CHDWEB，大小写不敏感" />
        </el-form-item>
        <el-form-item label="原因" prop="reason">
          <el-input v-model="form.reason" type="textarea" :rows="2" placeholder="可选，如“转码质量差”" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submitForm">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { usePtTorrentBlacklist } from '@/composables/usePtTorrentBlacklist'

const showSearch = ref(window.innerWidth >= 768)

const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  open, dialogTitle, submitLoading, formRef, form, rules,
  handleAdd, submitForm, handleDelete
} = usePtTorrentBlacklist()

const shortHash = (value: string) => {
  if (!value) return '-'
  return value.length > 12 ? `${value.slice(0, 6)}...${value.slice(-4)}` : value
}
</script>

<style scoped lang="scss">
.page-container {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.search-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);

  :deep(.el-card__body) {
    padding: 14px 16px;
  }
}

.table-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);

  :deep(.el-card__body) {
    padding: 16px;
    display: flex;
    flex-direction: column;
  }
}

.action-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: auto;
  padding-top: 12px;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 14px;
  min-height: 120px;
}

.item-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  transition: box-shadow var(--osr-transition-fast), border-color var(--osr-transition-fast);

  &:hover {
    box-shadow: var(--osr-shadow-md);
    border-color: var(--osr-border-base);
  }
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;

  .card-title {
    flex: 1;
    min-width: 0;
    font-size: 14px;
    font-weight: 600;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.card-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.card-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;

  .label {
    flex-shrink: 0;
    width: 64px;
    color: var(--osr-text-secondary);
  }

  .value {
    flex: 1;
    min-width: 0;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.card-footer {
  display: flex;
  justify-content: flex-end;
  gap: 4px;
  padding-top: 8px;
  border-top: 1px solid var(--osr-border-light);
}

@media (max-width: 768px) {
  .page-container {
    gap: 10px;
  }

  .search-card :deep(.el-form) {
    .el-form-item {
      margin-right: 0;
    }

    .el-input,
    .el-select {
      width: 100% !important;
    }
  }

  .table-card :deep(.el-card__body) {
    padding: 12px;
  }

  .card-grid {
    grid-template-columns: 1fr;
  }
}
</style>
```

- [ ] **步骤 4：类型检查 + 构建验证**

运行：`cd openlist-web && npx vue-tsc --noEmit`
预期：无类型错误输出

运行：`cd openlist-web && npm run build`
预期：`vite build` 成功生成 `dist/`

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/views/openlist/ptTorrentBlacklist/index.vue \
        openlist-web/src/router/index.ts \
        openlist-web/src/composables/useMenuIcon.ts
git commit -m "feat: 新增 PT 黑名单管理页面，注册路由与图标映射"
```

---

### 任务 10：前端下载记录页拉黑按钮

**文件：**
- 修改：`openlist-web/src/api/openlist/ptDownloadRecord.ts:1-27`（全文件）
- 修改：`openlist-web/src/composables/usePtDownloadRecord.ts:1-129`（全文件）
- 修改：`openlist-web/src/views/openlist/ptDownloadRecord/index.vue:1,114-124,143-154`
- 测试：`openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`

**背景**：`.record-actions` 区域（原文件第 114-124 行）目前只在 `item.state === 'FAILED'` 时整体显示，只包含"立即重试"按钮。本任务把外层 `v-if` 去掉（黑名单操作对任意状态的记录都有意义），"立即重试"按钮自己保留 `v-if="item.state === 'FAILED'"`，新增两个恒显示的拉黑按钮。`usePtDownloadRecord.ts` 新增 `blacklistingIds`（防重复点击）+ `handleBlacklistGuid`/`handleBlacklistReleaseGroup`。现有组件测试的 `baseComposable()` 辅助函数（`__tests__/index.spec.ts:15-33`）需要同步补上这三个新字段的默认值，否则模板里的 `blacklistingIds.has(item.id)` 会在没覆盖这些字段的既有用例里读到 `undefined` 而报错。

- [ ] **步骤 1：编写失败的测试**

在 `__tests__/index.spec.ts` 的 `baseComposable()` 辅助函数（原文件第 15-33 行）里，`handleBatchRetry: vi.fn()`（原文件第 30 行）之后追加：

```ts
    handleBatchRetry: vi.fn(),
    blacklistingIds: reactive(new Set<number>()),
    handleBlacklistGuid: vi.fn(),
    handleBlacklistReleaseGroup: vi.fn(),
    ...overrides
```

（即在 `...overrides` 之前插入这三行，替换原来紧邻 `...overrides` 的 `handleBatchRetry: vi.fn(),` 结尾。）

在文件末尾、最后一个 `describe('PtDownloadRecord 批量重试', ...)` 块（原文件第 104-169 行）之后、文件结尾追加一个新 `describe` 块：

```ts

describe('PtDownloadRecord 拉黑操作', () => {
  it('非 FAILED 状态的卡片也显示拉黑按钮，不显示立即重试按钮', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.blacklist-guid-btn').exists()).toBe(true)
    expect(wrapper.find('.blacklist-group-btn').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('立即重试')
  })

  it('点击拉黑该种子按钮调用 handleBlacklistGuid', async () => {
    const handleBlacklistGuid = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }]),
      handleBlacklistGuid
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.blacklist-guid-btn').trigger('click')
    expect(handleBlacklistGuid).toHaveBeenCalled()
  })

  it('点击拉黑该发布组按钮调用 handleBlacklistReleaseGroup', async () => {
    const handleBlacklistReleaseGroup = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }]),
      handleBlacklistReleaseGroup
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.blacklist-group-btn').trigger('click')
    expect(handleBlacklistReleaseGroup).toHaveBeenCalled()
  })
})
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`
预期：新增的 3 个用例 FAIL（`.blacklist-guid-btn`/`.blacklist-group-btn` 尚不存在，`find(...).exists()` 返回 `false`）

- [ ] **步骤 3：编写最少实现代码**

`api/openlist/ptDownloadRecord.ts` 文件末尾（`batchRetryPtDownloadRecordApi` 定义之后，原文件第 22-26 行）追加：

```ts
/** 拉黑该记录对应的种子（GUID 维度），reason 可选；返回 true=新增成功，false=已在黑名单中 */
export function blacklistGuidApi(id: number, reason?: string) {
  return request.post<any, boolean>(
    `/openliststrm/pt-download-records/${id}/blacklist-guid`, reason ? { reason } : {}
  )
}

/** 拉黑该记录标题解析出的发布组，reason 可选；返回 true=新增成功，false=已在黑名单中 */
export function blacklistReleaseGroupApi(id: number, reason?: string) {
  return request.post<any, boolean>(
    `/openliststrm/pt-download-records/${id}/blacklist-release-group`, reason ? { reason } : {}
  )
}
```

`usePtDownloadRecord.ts` 的 import 语句（原文件第 4 行）替换为：

```ts
import {
  getPtDownloadRecordListApi, retryPtDownloadRecordApi, batchRetryPtDownloadRecordApi,
  blacklistGuidApi, blacklistReleaseGroupApi
} from '@/api/openlist/ptDownloadRecord'
```

在 `handleBatchRetry` 方法（原文件第 83-94 行）之后、`// ---------- 移动端 - 分页辅助 ----------`（原文件第 96 行）之前插入：

```ts

  // ---------- 拉黑 ----------
  const blacklistingIds = reactive(new Set<number>())

  const handleBlacklistGuid = async (row: any) => {
    blacklistingIds.add(row.id)
    try {
      const created = await blacklistGuidApi(row.id)
      ElMessage.success(created ? '已拉黑该种子' : '该种子已在黑名单中')
    } catch (e) {
      console.error(e)
    } finally {
      blacklistingIds.delete(row.id)
    }
  }

  const handleBlacklistReleaseGroup = async (row: any) => {
    blacklistingIds.add(row.id)
    try {
      const created = await blacklistReleaseGroupApi(row.id)
      ElMessage.success(created ? '已拉黑该发布组' : '该发布组已在黑名单中')
    } catch (e) {
      console.error(e)
    } finally {
      blacklistingIds.delete(row.id)
    }
  }
```

`usePtDownloadRecord.ts` 的 `return` 语句（原文件第 122-127 行）替换为：

```ts
  return {
    taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
    retryingIds, handleRetry,
    selectionMode, selectedIds, toggleRecordSelect, handleBatchRetry,
    blacklistingIds, handleBlacklistGuid, handleBlacklistReleaseGroup,
    totalPages, prevPage, nextPage, handleSizeChange, searchCollapsed
  }
```

`views/openlist/ptDownloadRecord/index.vue` 里 `.record-actions` 区块（原文件第 114-123 行）：

```html
          <div class="record-actions" v-if="item.state === 'FAILED'">
            <el-button
              link
              type="primary"
              :loading="retryingIds.has(item.id)"
              @click="handleRetry(item)"
            >
              立即重试
            </el-button>
          </div>
```

替换为：

```html
          <div class="record-actions">
            <el-button
              v-if="item.state === 'FAILED'"
              link
              type="primary"
              :loading="retryingIds.has(item.id)"
              @click="handleRetry(item)"
            >
              立即重试
            </el-button>
            <el-button
              link
              type="warning"
              class="blacklist-guid-btn"
              :loading="blacklistingIds.has(item.id)"
              @click="handleBlacklistGuid(item)"
            >
              拉黑该种子
            </el-button>
            <el-button
              link
              type="danger"
              class="blacklist-group-btn"
              :loading="blacklistingIds.has(item.id)"
              @click="handleBlacklistReleaseGroup(item)"
            >
              拉黑该发布组
            </el-button>
          </div>
```

`<script setup>` 里的解构（原文件第 150-154 行）替换为：

```ts
const {
  taskList, loading, total, queryParams, getList, handleQuery, resetQuery, queryRef,
  retryingIds, handleRetry,
  selectionMode, selectedIds, toggleRecordSelect, handleBatchRetry,
  blacklistingIds, handleBlacklistGuid, handleBlacklistReleaseGroup
} = usePtDownloadRecord()
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd openlist-web && npx vitest run src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts`
预期：全部用例 PASS（原有用例 + 本任务新增 3 个）

- [ ] **步骤 5：Commit**

```bash
git add openlist-web/src/api/openlist/ptDownloadRecord.ts \
        openlist-web/src/composables/usePtDownloadRecord.ts \
        openlist-web/src/views/openlist/ptDownloadRecord/index.vue \
        openlist-web/src/views/openlist/ptDownloadRecord/__tests__/index.spec.ts
git commit -m "feat: 下载记录页新增拉黑该种子/拉黑该发布组按钮"
```

---

### 任务 11：全量验证与启动校验

**文件：** 无新增/修改，纯验证任务。

**背景**：本次改动新增 `pt_torrent_blacklist` 表、新增 Spring bean（`PtTorrentBlacklistPlusServiceImpl`）、修改了 `SubscriptionEngine` 的构造器签名。按 `AGENTS.md` 的要求，新增 `@Service` bean 与修改被广泛依赖的构造器之后必须做启动验证——单元测试用 mock 构造 `SubscriptionEngine`，不会暴露 Spring 装配问题；只有真实启动才能确认 `PtTorrentBlacklistPlusServiceImpl` 的构造器参数（`IPtDownloadRecordPlusService`）能被正确注入、`MysqlDdl` 迁移正常执行。

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
SHOW TABLES LIKE 'pt_torrent_blacklist';
SHOW CREATE TABLE pt_torrent_blacklist;
SELECT menu_id, menu_name, url, icon FROM sys_menu WHERE menu_id = 2072;
```

预期：第一条返回一行（表已创建）；第二条能看到 `uk_type_value(type, value)` 唯一索引；第三条返回 `PT黑名单` 菜单，`icon='fa fa-ban'`

- [ ] **步骤 7：手动验证前后端联通**

1. 部署前端：`docker compose up -d --build --no-deps frontend`
2. 浏览器打开"PT下载记录"页，任选一条记录点击"拉黑该种子"，确认提示"已拉黑该种子"；再点击一次同一条记录的同一按钮，确认提示变为"该种子已在黑名单中"（幂等）
3. 点击某条记录的"拉黑该发布组"（若标题解析不出发布组，确认弹出的错误提示是后端返回的具体原因而非"系统内部错误"）
4. 打开"PT黑名单"管理页，确认能看到上述两条记录（GUID 类型展示为脱敏短哈希，发布组类型展示完整发布组名）；手动"新增发布组规则"填入任意发布组名与原因，保存后列表刷新出现新行；删除任意一行，确认列表刷新后消失
5. 确认侧边栏"PT下载管理"分组下"PT黑名单"菜单项图标正常显示（不是空白/默认图标）

- [ ] **步骤 8（可选）：完成后清理构建产物**

无需提交任何文件，本任务全程只做验证。

---

## 自检记录

- 设计规格 2.2 节判定链顺序（GUID→做种数→体积→免费→分辨率→标题为空→发布组→排除词→包含词）已在任务 4 的 `rejectReason()` 与测试用例中逐条覆盖，包括判定顺序的两个关键回归测试（"标题为空即使 parsedReleaseGroup 非空也先淘汰"、"同时命中 GUID 和做种数不足时原因是 GUID"）。
- 设计规格 2.3 节的六条取舍（重载而非改签名、GUID 判定最前、发布组判定紧邻 excludeKeywords、发布组整词比较、GUID 存哈希、发布组归一化大写、两个入口都做、管理页只支持 RELEASE_GROUP 新增）均已在对应任务的背景说明或代码注释中体现。
- 设计规格第 7 节测试计划列出的用例（`TorrentFilterEngineFilterTest` 6 类新用例、`TorrentBlacklistTest`、`PtTorrentBlacklistPlusServiceImplTest`、`SubscriptionEngineTest` 新增用例）均已在任务 2/4/5 中逐条落实为真实代码，未使用占位符。
- SQL 编号（`20260743`）与菜单 ID（`2072`/`order_num=8`）已核实当前仓库真实状态，与设计文档草稿的旧编号不同，已在计划开头"前置说明"中明确记录偏差原因。
- 前后任务引用的类型/方法名在全文中保持一致：`PtTorrentBlacklistPlus`（含 `TYPE_GUID`/`TYPE_RELEASE_GROUP` 常量）、`IPtTorrentBlacklistPlusService`（`blockRecordGuid`/`blockRecordReleaseGroup`）、`TorrentBlacklist`（`guidHashes`/`releaseGroupsUpper`/`EMPTY`/`from()`）、`TorrentInfo.parsedReleaseGroup`、`TorrentFilterEngine.evaluate/filter` 三参重载、`SubscriptionEngine` 新构造器参数 `blacklistService`，各任务之间无冲突或遗漏引用。
- 未发现"待定"/"TODO"/"后续实现"/"补充细节"等占位符表述；任务 6/7/8 的"薄转发端点不写专门测试"均给出了仓库内可验证的既有先例（`PtIndexerRestController`/`PtDownloaderRestController` 无专门测试）作为依据，不是随意省略。
