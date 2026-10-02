# PT 下载器接入

qB / TR 客户端差异、角色划分、删种边界、listAll 与 listByTag 的分工。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **qB 5.0 把 `resume`/`paused` 改名成 `start`/`stopped`**：add 时两个参数都发（qB 忽略不认识的字段），启动 / 暂停都先试旧端点（`/torrents/resume`、`/torrents/pause`）、失败再试新的（`/torrents/start`、`/torrents/stop`），成功的端点按 downloaderId 各缓存一份（`postWithVersionFallback`），避免之后每次都先撞一个 404。暂停态的状态名同样两代都有（`pausedDL` / `stoppedDL`），`isPausedState` 按前缀认。Transmission 没有这个问题（`paused` + `torrent-start` / `torrent-stop` 跨版本稳定，暂停态是 `status=0`）
- **实时速度字段（`downloadSpeed` / `uploadSpeed` / `etaSeconds` / `paused`）只给下载记录页用，不落库**。`etaSeconds` 算不出来时必须归一成 null：qB 用 8640000（100 天）、TR 用 -1/-2 表示「不知道」，透传给前端会显示成「剩余 100 天」这种像真的估计
- **OSR 默认从不删种，只有三个受控例外**：`DownloadTrackService#removeUselessTorrent`（从未下完也从未做种的废种）、`pt/clean/TorrentCleanService`（用户逐个下载器显式开启的自动删种）、`pt/task/DownloadRecordControlService#deleteTorrent`（用户在下载记录页**逐条手动**发起；H&R 考核中或考核结果不明的一律拒绝，已下完的只移除任务不删文件，只按跟踪标签 / hash 认种子、**不按种子名**——辅种的兄弟种子名字往往逐字一致）。除此之外一律不要调用 `IDownloaderClient#deleteTorrent`。`hr_state=VIOLATED` 是"已经发生"的事实（用户手删或下载器自动管理清掉了），只发告警，系统不会也无法自动补救。主动防线只有推送后按站点规则下发 `setShareLimits`——**Transmission 的 RPC 没有"最短做种时长"概念**（`seedIdleLimit` 是"空闲多久后停"，语义不同，不能拿来充数），该维度对 Transmission 只能靠 OSR 侧追踪告警兜底
- **`pt_downloader.role` 把"订阅下载池"与"只做种的机器"分开，过滤必须在 Java 侧做**（`SubscriptionEngine#loadEnabledDownloaders` 用 `PtDownloaderPlus#participatesInDownload()`）。`resolveDownloader` 在订阅没指定下载器时会在**所有**启用下载器之间做负载均衡，用户加一台"接 IYUU 转移+辅种、开着自动删种"的保种机后，订阅会开始往它上面推种——而那台机器的清理规则是按"保种"设计的（做满 N 小时就删），正在补的剧集下上去会被当保种种子清掉。**不要改写成 SQL 的 `eq("role","DOWNLOAD")`**：role 是后加的列，存量行为 NULL，等值比较对 NULL 恒为假，会把用户原本唯一那台下载器整个滤掉、订阅全部停摆；`participatesInDownload()` 对 NULL 退化成 DOWNLOAD。订阅显式指定了 SEED_ONLY 的下载器同样只能改派（那是分工意图不是配置事故，但推上去更糟）
- **`IDownloaderClient#listAll` 与 `listByTag` 的分工是硬的**：IYUU 转移/辅种加进来的种子**不带 OSR 标签**，`listByTag` 一条都看不见。自动删种、以及"种子是被删了还是被转移走了"的判断都必须用 `listAll`。`DownloadTrackTask#fetchTorrents` 据此按 role 分流：SEED_ONLY 拉全量，DOWNLOAD 仍按标签拉（避免把用户手工添加的一大堆无关种子拉进来）
- **剩余空间（`IDownloaderClient#freeSpace`）的 null 与 0 是两回事**：null 是「读不到」（qB 旧版没有 `free_space_on_disk`、Transmission 低于 2.80 没有 `free-space`、或保存路径在 TR 那台机器上不存在时它回 -1），0 是真写满了。下游两处都按 null 不动手：告警不告、按空间删种一个都不删。qB 同样只能走 `sync/maindata?rid=0`，所以空间检查 15 分钟一次（`DownloaderSpaceTask`，`pt.downloader.space-check-minutes`），**首页待办与卡片只读内存登记（`DownloaderSpaceRegistry`），不现读下载器**——每打开一次页面就拉一遍 maindata 不值得。告警只在三个时刻发（刚低于、持续低位每满一天、恢复），低位期间用户清空告警线也算恢复，否则那条待办永远消不掉；停用 / 删掉的下载器要从登记表里忘掉。两条阈值列（`free_space_warn_gb` / `auto_delete_free_below_gb`）是 `FieldStrategy.ALWAYS`：清空本身有含义，默认策略会让清空静默存不进去。
- **`IDownloaderClient#cumulativeUploaded` 是 default 方法、默认返回 null**，新接入的下载器不实现也能编译、看板只是退回现存种子求和的兜底口径。qB 走的是 `sync/maindata?rid=0`——响应里连带全部种子、体积不小，**只许低频调用**（保种快照一小时一次）；要秒级的上传速率请用 `transfer/info`，那是会话级、重启清零的，不能拿来按天求差。
