# 工作流体验优化

## 后台任务

顶栏「后台任务」可查看本账号最近 50 个任务。订阅页批量补搜、进度里的补齐、诊断页/缺集体检的补搜，以及下载记录批量重试，均由服务端串行执行。浏览器刷新或关闭不影响执行。

每项保存目标、执行状态与结果，区分已推送、无需处理、未命中/失败。中止不撤回已推送资源，只在当前项目结束后停止后续项目。重试新建任务，只包含未完成与失败项，不重跑已成功或已跳过的项。服务重启后把排队/执行中的旧任务标为中断；对账后可显式重试，避免不确定的推送被自动重复。

任务创建与执行时均校验订阅/下载记录的可见范围；任务本身只允许创建者查询、中止与重试。一次最多 200 项，每人最多 3 个在途任务，全局最多 20 个排队/执行任务。

迁移 `20260815-background-job.sql` 已注册进 `MysqlDdl`。启动后 `ApplicationReadyEvent` 恢复旧任务状态。异步入口用 `Threads.wrap` 保留日志链路。

## 诊断与记录定位

一键诊断中的处理链路显示关联下载、同步、STRM、重命名/刮削及媒体库检查入口。下载按集记录里的 `download_id` 关联；后续记录先按 TMDb、媒体类型和季集匹配重命名记录，再按原始目录与文件名关联。

只显示可以核实的关联。旧记录缺少关联、路径映射不一致或文件尚未进入下一阶段时显示「未确认」，不把记录缺失当作执行失败。普通用户只能看到自己可访问订阅的下载记录，共享文件处理记录由管理员检查。

记录入口带精确 ID：`copyId` / `strmId` / `id` / `recordId`。记录页显示可关闭的定位标签，关闭或重置会清除 URL 条件，刷新不会重新筛回该记录。查不到路由权限时不提供入口。

## 首次配置引导

管理员头像菜单可重新打开「配置引导」；必需配置缺失且未选择稍后设置时自动展示。支持「只做网盘 STRM」与「自动追剧」两种清单，复用现有配置页面及连接测试。

清单只读取配置是否存在及启用任务数量，不返回密钥，也不通过轮询清单调用外部服务。「已配置」不代表测试连接或播放已通过。可输入原始视频文件名查询最近同名文件的真实执行记录，打开记录核对路径，最终入库与播放在媒体库确认。

## 复用接口依据

- `SearchSupplementService.searchAndPushMissing(Integer)`：返回 `SearchAndPushSummary`；`skipped` 表示未执行搜索，`anyPushed()` 才表示有资源推送，`rejectSummary` 是真实候选淘汰原因。空原因不代表成功。源码：`osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SearchSupplementService.java:695` 及 `dto/SearchAndPushSummary.java`。
- `DownloadRecordAdminService.retry(Integer)`（`pt/task/DownloadRecordAdminService.java:302`）：只接受 FAILED 记录，返回 `SupplementResult`；`pushed` 为真才算推送成功，`reason` 可解释未命中。该服务不负责调用者归属校验，任务服务执行前调用 `canAccess(Integer, PtStatsScope)`。
- `SubscriptionService.aired(PtSubscriptionEpisodePlus, LocalDate)`（`pt/subscription/SubscriptionService.java:109`）：播出日期为 null 视为已播，今日视为已播。链路诊断与补搜沿用同一判据。
- `RenameDetailPlus`：媒体类型是小写 tv/movie；`status` 0 失败、1 成功；`scrape_status` 0 未执行、1 成功、2 失败。未执行刮削不能显示成功。
- `OpenlistCopyPlus`：状态 1 处理中、2 失败、3 成功、4 异常；`OpenlistStrmPlus` 状态 0 失败、1 成功。目录与文件名各自独立存储。
- `getRoutePathForComponent(componentKey)`：按实际下发菜单反查路径，返回 null 表示没有已注册入口，不写死历史路径前缀。
