# osr-quartz 定时任务

Quartz 调度、手动执行、执行记录。对应前端「定时任务」菜单（`osr-web/src/views/monitor/job/`）。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- **任务有两条执行路径，写日志的代码各有一份，改一处要想到另一处**。定时触发走
  `AbstractQuartzJob#execute`（Quartz 线程，`before`/`after` 配 ThreadLocal 记开始时间）；
  页面上点「执行」走 `SysJobServiceImpl#run` → `executeJobDirectly`，它**刻意绕开 Quartz**
  （历史原因：trigger 的 JobDataMap 合并有坑）。绕开的代价是 Quartz 的
  `@DisallowConcurrentExecution` 对手动这条完全不起作用，实体上的 `concurrent` 字段也一样——
  所以并发闸门是自己实现的，见下条。
- **手动执行必须是异步的**。任务体（`openListStrmTask.copy()` / `strm()`）是整盘遍历，分钟级起步，
  而前端 axios 超时是 **15 秒**（`osr-web/src/api/request.ts`）。它原先同步跑在 HTTP 请求线程上，
  于是每次点「执行」都是：15 秒后前端弹红字报错 → 用户以为失败 → 再点一次 → **真的并发跑了两遍**。
  现在 `run()` 只负责受理，任务体丢给 `virtualScheduledExecutor`（记得 `Threads.wrap`，否则 traceId 断链），
  返回 `JobRunResult` 而不是 boolean——原先 boolean 的 false 被统一翻译成「任务不存在或已过期」，
  而最常见的那种 false 其实是「上一轮还在跑」，提示完全对不上。
  顺带去掉了 `run()` 上的 `@Transactional`：同步时代它把一个几分钟的任务整个圈进一个数据库事务里，
  连接被占满全程，而且**失败路径写的那条执行记录会跟着回滚掉**——最该留痕的那次反而什么都不剩。
- **`JobRunningRegistry` 是两条路径共用的执行中登记簿，不要各记各的**。只记手动那条的话，
  「凌晨三点的复制任务正跑着，用户点一下执行」照样会并发。它同时是列表页「执行中」按钮态的数据源
  （内存态，由 Controller 逐行补进 `SysJob#running`，不是库字段）。`tryMarkRunning` 是
  `Set#add` 的返回值——**一步完成判定与占位**，退化成「先 `isRunning` 再 `markRunning`」两步的话
  并发点击会同时通过，而那种回归不报错、只是任务跑两遍。`JobRunningRegistryTest` 钉着这条。
  只有 `concurrent=禁止`（四个内置任务都是）的任务才登记与拦截。
- **`sys_job_log` 的 `start_time` / `end_time` 是 `20260798` 迁移之后才有的列**。在那之前：
  实体 `SysJobLog` 有这两个字段、前端一直拿它们渲染「开始时间」「耗时」，但**建表语句里没有这两列**，
  insert 不写、select 不取——于是两列在界面上**恒为 `-`**，真实耗时只藏在 `job_message` 那句
  「总共耗时：xx毫秒」里。同期 `saveJobLog` 里还有一个 `startTime()` 私有方法返回 `new Date()`
  （也就是「现在」），就算当时落了库，start 与 end 也相等。存量记录补不回来，前端回落到
  `create_time` 显示开始时间、耗时留空。
- **`SysJob#getNextValidTime()` 是没有库表字段的 getter，靠 cron 现算，列表接口序列化每一行都会调它**。
  因此它**必须吞掉非法表达式的异常**：`CronUtils.getNextExecution` 解析失败会抛
  `IllegalArgumentException`，让它抛出去的话，库里只要有一条被手工改坏的 cron，
  整个定时任务列表就是 500，而不是那一行的「下次执行」空着。`SysJobTest` 钉着这条。
  另注意它是**无对应字段的 `getXxx()`**——这在 `*Plus` 实体上是禁忌（见根 `AGENTS.md`），
  这里安全只是因为 `SysJob` 上不存在同名字段、不会产生歧义 getter；新增同类计算属性前先读那一条。
- **`sys_job_log` 只增不减**。`cleanJobLog()` / `deleteJobLogByIds()` 在 `ISysJobLogService` 里都实现了，
  但 `MonitorJobLogApiController` 只暴露了 list 与详情，前端也没有清空/删除入口——目前是死代码，
  表随运行时间无限增长。要接就把三件一起做（接口、按钮、保留期定时任务）。
- **「上次执行」只能按 `job_name` + `job_group` 回查，因为 `sys_job_log` 没有 `job_id` 列**（RuoYi 原设计）。
  `selectJobListPage` 里那两个相关子查询走的是 `idx_job_log_name_group_time`。同一个原因让执行记录弹窗
  也只能按任务名过滤（且 mapper 是 `like '%name%'`），任务名互为前缀时会串。彻底修法是加一列 `job_id`
  并在两条写日志的路径上都填进去。
- **`selectJobListPage` 的 where 条件必须带 `j.` 前缀**：日志侧同样有 `job_name` / `job_group` / `status`，
  不加前缀在有 join 的语句里就是 ambiguous column。也别改成 `row_number()` 窗口函数——那要把整张
  只增不减的日志表物化一遍，而相关子查询每行只走一次索引取 1 条。
