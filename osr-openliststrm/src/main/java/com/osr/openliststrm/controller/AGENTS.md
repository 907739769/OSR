# REST 端点与记录页

BaseCrudRestController 的开放范围、统计接口、批量删除。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **同步记录、重命名明细这类系统生成的记录，不开放继承来的新增 / 修改 / 单条删除**（`BaseCrudRestController#systemGeneratedRecords`）。这三个端点是模板生成时顺带继承的，前端一处没用，却能调通：伪造一条成功的同步记录会让该文件再也不被同步，改一条重命名明细的 new_path 会让清理产物去删不相干的目录。STRM 记录控制器不继承基类，那三个端点直接删了。**MCP 工具层要调这几个控制器时也只能用它们的专用端点**。
- **记录页的统计接口（`/stats`）用的 wrapper 只能带筛选、不能带排序**（`RecordStatusCounts`）。`GROUP BY status` 配 `ORDER BY create_time` 在 ONLY_FULL_GROUP_BY 下直接报错，所以三个记录控制器都拆成 `applyConditions`（筛选）+ `buildQueryWrapper`（筛选 + 排序）。统计时忽略状态这一项，否则统计条上除了被筛的那个状态全是 0。
- **批量删除网盘文件返回 `BatchRemoveOutcome`（请求数 / 实际删除数 / 是否转后台）**，超过 20 条转后台执行。原先接口返回空、前端一律提示「删除成功」，转后台时刷新出来记录都还在，同步执行时个别失败也看不出来。
- **按记录重试的约定，以及记录表 `fail_reason` 的写法**（`FieldStrategy.ALWAYS`、状态与原因一起写）见下面两条，改复制 / STRM 记录的任何写入点前先读。
- **PT 下载记录的全部端点按订阅归属隔离**（`PtDownloadRecordRestController` + `DownloadRecordAdminService#canAccess/filterAccessible`）。下载记录没有自己的归属列，跟着订阅走，判据与订阅页、统计面板同一条（复用 `PtStatsScope`）：列表 / 统计走 `inSql("sub_id", visibleSubIdSql())`，单条操作先判再做、「不存在」与「无权」回同一句，批量操作在**请求线程里**先过滤再交给后台。此前这里裸奔：任何登录用户都能列出全站下载记录，也能对别人的记录重试、拉黑。**订阅已删除的记录只有管理员看得到**——没有订阅就无从判断它属于谁。MCP 的 `list_download_records` 等工具直接调这个控制器，隔离对它同样生效。
- **下载记录的批量重试转后台、立即返回 `batchId`**，跑完经 PT 状态 WebSocket 推 `batchRetry` 事件，页面按 `batchId` 认领（广播是全体连接都收得到的）。每条重试都要打一轮索引器，同步执行时几条就超过前端 15 秒超时——页面报错而后端还在推送，用户很可能再点一次。
- **下载记录只开放「按规则清理」，不开放单条删除**（`DownloadRecordAdminService#cleanup`，仅管理员）。四个条件缺一不可：终态、推送早于 N 天（只允许 30/90/180/365）、不在 H&R 考核中（删种与转移做种靠这些行保护考核中的种子）、**没有任何集的 `download_id` 指着它**（对账写质量基线、洗版、企微查询都要顺着它找回种子标题）。`pt_subscription_episode.download_id` 没有索引，`NOT EXISTS` 是全表反连接，对低频的手动清理可以接受，别把它搬进周期任务。
- **下载记录列表的日期区间可以落在三列上（`dateField`：PUSHED / COMPLETED / FAILED）**，列名走 `PtDownloadRecordRestController#dateColumn` 白名单映射，不接受前端字符串直接拼列。选 FAILED 时后端会顺带加 `state='FAILED'`：失败没有专属时间列，只有 FAILED 行的 `update_time` 才是「被判失败的那一刻」。这组参数是给统计仪表盘下钻用的——趋势图三条线各按自己的日期分组，点进来按推送日筛的话「某天失败 3」点进去看到的是那天推送的记录。
