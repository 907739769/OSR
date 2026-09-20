# 目录监听

在途产物拦截与关停期的任务被拒。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- **「文件仍在写入」是持续状态，且它的成本不止一行日志**。`MediaUploadProcessor`/`MediaRenameProcessor` 收到 ENTRY_MODIFY 就判一次稳定性，文件每被写一次就来一个事件，同一个路径每轮得到同一个答案——上 `LogOnce`，稳定之后 `forget`（它再次被改写还会照常记一次）。真正要命的是 **`FileStabilityUtils.isFileStable` 里 `sleep(2)`**：实测一个临时文件在 **3.016 秒**内触发 569 个事件、各起一个异步任务，也就是同一个文件上同时挂着 569 个各睡两秒的线程。所以在途产物必须**在进入稳定性判定之前**就挡掉，判据收口在 `FileStabilityUtils#isTransientArtifact`：
  1. **后缀清单原先散在两个 processor 里各写一份，且只有 `.!qB`/`.part`/`.tmp` 三个**。踩过的是 115 客户端的 `.<sha1>.parts`——**复数 s**，`endsWith(".part")` 不匹配，于是整个临时文件的每一次写入都走完整流程。清单现在放一处、比对大小写不敏感。
  2. **点开头一律跳过**，这是清单之外的第二道：媒体文件不会以点开头，而各家客户端的临时文件几乎都是「点 + 哈希」。清单只能挡已知形态，这条挡未知形态。
  3. **误判一个真实媒体文件的代价是彻底不处理**（既不上传也不重命名，且完全静默），所以 `FileStabilityUtilsTest` 两侧都钉：临时文件要认出来，正常文件一个都不许误判（包括名字里带 `Part2` 的）。
  **没有顺手把 `processing.add` 提到稳定性判定之前**——那样并发事件会被去重掉、看着能把 569 个线程压成 1 个，但会丢事件：写入结束前最后那个事件如果撞上一次在途的判定就被丢弃，而那次判定采样跨越了写入结束点、必然返回 false，文件从此再没有事件推动它，永远不被处理。`MediaRenameProcessor` 在 `process()` 与 `validateFile()` 里各判一次稳定性（各睡 2 秒）也因此保留：`validateFile` 还服务于另外两个入口。
- **关停期的「任务被拒」不是故障，不许进 `sys-error.log`**。应用关停时 WatchService 还在派事件，而派给的 executor 已经在关了，抛 `TaskRejectedException: ExecutorService in shutdown state`——生产日志里这是整整 6 小时中**唯一**一条 ERROR，还拖着一份堆栈，而那个文件的全部价值在于噪音为零。判据 `WatchServiceMonitor#isShutdownRejection` 与 `GlobalExceptionHandler#isClientAbort` 同源：**按类名后缀沿异常链找、不 import Spring 的具体异常类**（同一件事在不同层会被包成不同类型，底层是 `RejectedExecutionException`），遍历要防 cause 自引用死循环。**但只看类型是不够的**：任务被拒也可能是<b>队列满了</b>，那是真故障、方向完全相反，必须留在 ERROR——所以还要求消息里明说 shutdown/terminated。**本地加一个 `stopping` 标志解决不了这件事**：实测 ERROR 发生在本监控 `stop()` 之前，先关的是应用级调度器。顺带把那条 ERROR 补上下文与 `e.getMessage()`（原先是 `log.error("目录监听主循环异常", e)`，翻到时不知道是哪个监控根）。
- **本地监控链路同样要拦这个临时目录，且必须拦在「注册之前」**。`WatchServiceMonitor` 眼里，Transmission 把内容挪进临时目录就是「凭空出现一个装满视频文件的新目录」，`handleCreate` 会递归注册它并给里面每个文件发一条 CREATE 事件，下游随即对着一批正在被删除的文件开工——重命名任务复制出半截产物，上传任务提交注定失败的 `fs/copy`（和同步任务遍历是同一个坑，只是入口不同）。所以 `WatchServiceMonitor` 接一个 `Predicate<String> skipDir`（`RenameMonitorRegistry`/`UploadMonitorRegistry` 都传 `helper::isTransientDir`），`registerAll` 与 `handleCreate` 的补扫都按它 `SKIP_SUBTREE`；`MediaRenameProcessor#processOnce` 的手动全量扫描不走监控，得自己在 `preVisitDirectory` 里再拦一次。**三处的「起始目录永远不跳」不是可选项**：监控根目录碰巧长成 `xxx__abc123` 时跳掉它，等于把整个监控/整次扫描静默取消，症状是「监控起来了却一个文件都不处理」，比多注册一棵目录难查得多；`WatchServiceMonitor#skipSubtree` 做成包级可见就是为了让这条边界能被单测直接钉住
