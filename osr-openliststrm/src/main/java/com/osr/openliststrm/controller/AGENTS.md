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
