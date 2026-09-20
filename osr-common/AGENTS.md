# osr-common 通用工具层

SQL 迁移注册、分页上下文、日志节流三件套、traceId 封顶。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- 数据库初始化由 `com.osr.common.mybatisplus.MysqlDdl` 自动执行（osr-common/src/main/resources/sql/）。**注意：`MysqlDdl.getSqlFiles()` 是硬编码的文件名清单，不是目录扫描**——新增 SQL 迁移脚本后必须手动把文件名追加到该方法返回的列表末尾，否则脚本只是静静躺在目录里，永远不会被执行
- **`TableSupport.getPageDomain()` 现在先看 `PageContext` 的线程级覆盖**。它默认从当前 servlet 请求的 query 参数里读 pageNum/pageSize/orderByColumn/isAsc，这对网页端是对的，但对「进程内直接调用 Controller」的调用方（MCP 工具层）不成立：那时的请求是 `POST /mcp`，URL 上没有任何分页参数，每个列表接口会恒定返回第 1 页 10 条；调用若发生在非 servlet 线程上，`ServletUtils.getRequest()` 更是直接 NPE。**没设覆盖时行为逐字节不变**，现有 11 个列表页不受影响。**刻意不做成「给 selectPage 加一个 PageDomain 重载」**——那要求每个 Controller 的 list 都多带一个参数往下传，等于把改动摊到三十来个类上，而其中绝大多数永远用不到它。代价是调用方必须在 finally 里 `PageContext.clear()`（`McpCallContext.Binding#close` 已经代劳）。
- **周期任务里的 `catch { log.warn }` 必须过 `FaultThrottle`（`osr-common/utils`），否则任何<持续性>故障都会变成按轮询间隔无限重复的同一条日志。** `DownloadTrackTask` 每 30 秒一轮，下载器离线一天就是 **2880 条逐字相同的 WARN**，既淹掉别的日志，又不比第一条多告诉你任何事——真正有信息量的只有**故障开始**和**故障恢复**两个时刻。用法是 `onFailure(key).shouldReport()` 门控输出、`onSuccess(key)` 返回「是否刚恢复」（只在恢复那一次返回 true，不会每轮都报「一切正常」）。三条别改坏的：**持续期间不是彻底静默**，每 `repeatEvery`（默认 120）次会重提一条，否则「故障两天了、日志里只有最初那一条」，排查的人往回翻不到会以为早就好了；**重提时要把 `consecutiveFailures()` 写进日志**，一条就能看出持续了多久；**`shouldReport()` 与日志级别无关**，`DownloadTrackTask` 拿它门控 WARN，`EmbyClient` 拿它门控 DEBUG（「媒体库里查不到这部剧」同样是会持续成立的状态，对账每 10 分钟跑一遍全部订阅，实测那一行占掉全量日志的 12%），两者都是「重复的持续状态」。恢复时记得 `onSuccess` 清标记，否则「本来有、后来又没了」不会重新报。
- **`FaultThrottle` 被单例 bean 当实例字段持有是正确的，不违反上面那条「单例不能用实例字段存状态」。**那条针对的是**单次请求**的状态（并发请求会互相覆盖，`ApiInterceptor` 的耗时就是这么错的），而这里存的恰恰是需要**跨轮次存活**的组件级状态——放进方法局部变量的话每轮都重新开始，整个节流就不存在了。两者的判据是「这份状态属于一次调用，还是属于这个组件」，不是「能不能用字段」。
- **日志的第五种失控形态是「行首那一坨 traceId」，它可以比消息本身长 17 倍**。前四种（`FaultThrottle` / `RoundHeartbeat` / `LogOnce` / 单行体积）盯的都是<b>消息</b>，而 `log.pattern` 里 `%X{traceId}` 那一段从来没人量过。实测一行：`[52879ede-91fbe0e2-…-cc2e6b3b]` <b>96 段 863 字节</b>，消息是「获取复制任务信息…成功」共 50 字节；最长的一条 <b>145 段 1304 字节</b>。整份日志 traceId 独占 **31.1%**（220 KB / 725 KB）。
  成因是 `ThreadTraceIdUtil#createChildTraceId` 原本写作 `父 + "-" + 8位`、**没有上界**，而 `AsynHelper` 那两处递归自我重排的监控循环（`processCopyListRecursive` / `checkOneFileRecursive`）会在**已包装的任务内部**再调一次 `Threads.wrap`——每轮在上一轮的 id 上再接一段，60 秒一轮就是每分钟 9 字节。跑满一天的复制任务，每一行会拖着约 13 KB 的 id。除了体积还有两笔账：MDC 是逐线程拷贝的，那个串每轮重新拼一次（任务生命期上 O(n²)）；而 1.3 KB 的 id 已经粘不进实时日志页的过滤框，那是它存在的全部理由。
  三条不要改坏的：
  1. **封顶封在 `createChildTraceId` 里，不是改那两个调用点**（`MAX_TRACE_SEGMENTS=8`）。这样以后再有人写自我重排的循环也自动安全，而不是留下一条「需要有人记得」的规矩——同 `MediaExtensionProvider` 按配置原文缓存而不是按键名的取向。
  2. **保留<b>根段</b>**。实时日志页靠整行子串匹配，粘根段就能把这条链路的全部轮次一并捞出来；被丢掉的中间段没有人会去读。末尾几段留着看当前嵌套位置。
  3. **`scheduleAtFixedRate` 那批任务不受影响**，别顺手一起「修」：`Threads.wrap` 在包装时捕获一次上下文，每轮从同一个固定上下文派生，深度恒定。只有<b>递归重包</b>才累积。
  顺带一条通用判据：**量日志时先把 `log.pattern` 里的每个字段各自加总一遍**，别只看消息。这个问题在四轮降噪里一直没被发现，就是因为每次都在数「哪条消息打了多少遍」。
- **周期任务必须让「正常跑完一轮」在日志里留下痕迹，靠 `RoundHeartbeat`（`osr-common/utils`）节流。** 改造前 14 个周期任务里有 **10 个正常跑完一轮零输出**，只在出错时说话——于是**「一切正常」和「调度器已经死了」在日志上完全一样**。铁证：某一整天的日志里，每个任务恰好只有 `started` 和 `stopped` 两行，而同一天数据库里有 32 集完成了入库。规则是**有变化立刻 INFO，没变化按时间间隔报平安**（`RoundHeartbeat` 是 `FaultThrottle` 的反面：那个管「出错了别刷屏」，这个管「没出错也得让人知道你还活着」）。四条不要改坏的：
  1. **判据必须是「本轮真正发生的业务变化」，不是扫描到的条数。** 后者恒大于 0，用它判等于每轮都打。踩过的原形：`LibrarySyncService#refreshAll` 原先返回 `active.size()`，日志写「本轮对账 103 条订阅」——103 每轮都一样，与这一轮有没有干成任何事无关。现在返回 `SyncOutcome(scanned, episodesIn, failed)`，`changed()` 只看后两个。同类的还有 `StrmServiceImpl` 那行「共处理 N 个文件」打的是 BFS 扫到的 `fileEntryCount` 而不是真正生成的 `pendingRecords.size()`——扫了 500 个文件、一个 .strm 都没生成时它照样说「共处理 500 个文件」，读起来完全像成功了。**凡是日志里那个数看着像结果的，先确认它不是输入。**
  2. **心跳按时间而不是按轮数。** 各任务间隔从 30 秒（`DownloadTrackTask`）到 12 小时（`EpisodeAirDateSyncTask`）不等，按轮数配的话每个调用方都要自己换算「30 分钟是我的几轮」，改间隔时又必然忘记同步改。按时间一律 `Duration.ofMinutes(30)`，语义就是「最多半小时没消息」。**30 秒一轮的任务尤其不能每轮都打**——那是 2880 行/天，会把降噪成果整个吐回去。
  3. **第一轮一定报。** 它证明这个任务不只是 bean 装配成功（`started` 只说明了这个），而是真的跑起来并跑到了头。
  4. **`DownloadTrackTask` 恒走 `quiet()` 是对的**，不是漏调 `active()`：它自己不产生业务变化（完成/失败/H&R 达标都由 `DownloadTrackService` 记），这里只回答「追踪循环还在转吗」。
  另：**「未到期」不算跑过一轮**（`UpgradeScanTask#isDue` 那个分支直接 return，不喂心跳），否则「洗版其实一直没扫」会看起来一切正常。**同一条在 `RssPollTask` 上栽过一次**：它每 60 秒触发，而各索引器有自己的拉取周期（常见 10 分钟），于是**约 10/11 的触发都没有到期的索引器**。「未到期」在这里不是 early return 而是一个返回值（`PollOutcome.NOTHING_DUE`），顺着 `changed()==false` 掉进 quiet 分支，后果有两层：空转一直把计时器按回去，真正拉回 708 条种子那一轮反而被压掉；而 30 分钟的心跳真要报平安时落在空转那一刻的概率约 10/11，打出「RSS 轮询完成：0 个索引器拉回 0 条种子」——读起来像索引器全没了，比不打还糟。判据现在是 `PollOutcome#ranAnything()`。**「未到期」写成 early return 时人人都记得，写成返回值时就看不见了**，新增按「子项各有周期、外层定频触发」这种形态的任务时先想一遍这条。
