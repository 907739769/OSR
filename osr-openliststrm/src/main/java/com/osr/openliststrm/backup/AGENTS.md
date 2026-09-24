# 配置备份与恢复

导出各项配置与订阅为一个 JSON 文件；恢复时先预览再按所选分区合并写入。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## STRUCTURE
- `BackupSection`：分区清单，**声明顺序即恢复顺序**，`key` 是备份文件里的键名，发布后不能改（旧备份会恢复不了）。
- `ConfigBackupService`：导出 / 预览 / 恢复。通用实体分区由 `spec(section)` 描述（业务键、运行时列、敏感列、引用列），其余是特殊分区（参数设置、通知路由、分类规则、过滤/洗版规则、订阅）。
- `EntityCodec`：按 `@TableField` / `@TableId` 读写实体的库表列。
- `SubscriptionRestorer`：后台逐条重建订阅，页面轮询 `/restore/status`。
- 前端 `views/system/backup/index.vue`（只有 PC 一套），接口 `BackupRestController`（全部限管理员，读也限）。

## NOTES
- **新增一张配置表时要决定它进不进备份**：进的话在 `BackupSection` 加一项（放在它依赖的分区之后）并在 `spec()` 里描述；不进的话什么都不用做。执行记录、下载记录、日志、缓存、MCP 令牌（只存哈希，恢复出来也用不了）一律不进。
- **恢复是按业务键合并，从不删除**。业务键取人认得的东西（名称、路径），不取自增 id——两台机器的 id 毫无关系。唯一的例外是重命名分类规则：有序规则表，整表替换，与设置页保存语义一致。
- **引用别的表 id 的列，备份里存名字**（`EntitySpec.Ref`）：删种/转移/热门自动订阅规则与订阅的下载器存名称，订阅归属与企微绑定的用户存登录名。`Ref.nameProperty` **不能与实体上的任何属性重名**，否则恢复时会被当成列写回去——企微绑定表本身就有 `sys_user_name` 列，所以引用键叫 `sysUserLoginName`。必需引用找不到时跳过该行并提示，可选引用找不到时置空（=用默认）。
- **敏感列导出时默认置空，恢复时空值表示「备份里没带」而不是「清空」**：保留本机现值；本机没有的行照样新增，但提示用户去补填。参数设置里的敏感项由 `SECRET_CONFIG_KEYS` 加键名后缀兜底判定——Bark 地址（带设备 key）与 Webhook 地址（常带 token）也算。
- **更新现有行时 null 值要显式 `set null`**（`ConfigBackupService#update`）：MP 的 `updateById` 跳过 null 字段，备份里「清空了」的列会悄悄保留旧值。比较是否变化时 BigDecimal 按数值比（`1.5` 与 `1.50` 相同）。
- **配置分区在同一个事务里**：任何分区校验失败全部回滚。参数设置写库时顺手更新了本地缓存，回滚滚不回缓存，所以失败时要 `resetConfigCache()`。
- **过滤规则与洗版规则一起恢复时，过滤规则只做自身校验**：`FilterConfigAdminService#save` 会拿本机<b>旧的</b>洗版规则做交叉校验，备份里自洽的一对配置会被拒；交叉校验留给紧接着的洗版分区（`UpgradeConfigAdminService#save` 对照的是刚写进去的过滤规则）。单独恢复过滤规则时仍走 `FilterConfigAdminService#save`。优先级变了要照样 `resetEvaluations`。
- **订阅走 `SubscriptionService#subscribe` 重建，不原样插回**：集行与「哪些集已入库」是本机的事实，从旧机器搬来只会是错的。已存在的订阅（同 TMDb ID + 类型 + 季）一律跳过不动。**恢复的订阅不触发建订阅后补搜**，否则一次几十上百条同时补搜会把所有索引器打满、撞站点限流。订阅在事务提交之后才开始重建（它按名字引用的下载器此时才对后台线程可见）。
- **上传走 multipart 而不是 JSON 请求体**：`RequestLogFilter` 在 DEBUG 下会把 JSON 请求体前 1000 字打进访问日志，含敏感信息的备份开头就是参数设置里的各种 Token；multipart 只记 `[FILE_UPLOAD]`。导出返回全文字符串、恢复读文件原文，都不经 JSON 转换器，免得 null 值的去留取决于转换器配置。
