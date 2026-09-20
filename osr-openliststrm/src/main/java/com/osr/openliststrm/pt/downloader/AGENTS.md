# PT 下载器接入

qB / TR 客户端差异、角色划分、删种边界、listAll 与 listByTag 的分工。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **qB 5.0 把 `resume`/`paused` 改名成 `start`/`stopped`**：add 时两个参数都发（qB 忽略不认识的字段），启动则先试 `/torrents/resume`、404 再试 `/torrents/start`，成功的端点按 downloaderId 缓存下来，避免之后每次都先撞一个 404。Transmission 没有这个问题（`paused` + `torrent-start` 跨版本稳定）
- **OSR 默认从不删种，只有两个受控例外**：`DownloadTrackService#removeUselessTorrent`（从未下完也从未做种的废种）与 `pt/clean/TorrentCleanService`（用户逐个下载器显式开启的自动删种）。除此之外一律不要调用 `IDownloaderClient#deleteTorrent`。`hr_state=VIOLATED` 是"已经发生"的事实（用户手删或下载器自动管理清掉了），只发告警，系统不会也无法自动补救。主动防线只有推送后按站点规则下发 `setShareLimits`——**Transmission 的 RPC 没有"最短做种时长"概念**（`seedIdleLimit` 是"空闲多久后停"，语义不同，不能拿来充数），该维度对 Transmission 只能靠 OSR 侧追踪告警兜底
- **`pt_downloader.role` 把"订阅下载池"与"只做种的机器"分开，过滤必须在 Java 侧做**（`SubscriptionEngine#loadEnabledDownloaders` 用 `PtDownloaderPlus#participatesInDownload()`）。`resolveDownloader` 在订阅没指定下载器时会在**所有**启用下载器之间做负载均衡，用户加一台"接 IYUU 转移+辅种、开着自动删种"的保种机后，订阅会开始往它上面推种——而那台机器的清理规则是按"保种"设计的（做满 N 小时就删），正在补的剧集下上去会被当保种种子清掉。**不要改写成 SQL 的 `eq("role","DOWNLOAD")`**：role 是后加的列，存量行为 NULL，等值比较对 NULL 恒为假，会把用户原本唯一那台下载器整个滤掉、订阅全部停摆；`participatesInDownload()` 对 NULL 退化成 DOWNLOAD。订阅显式指定了 SEED_ONLY 的下载器同样只能改派（那是分工意图不是配置事故，但推上去更糟）
- **`IDownloaderClient#listAll` 与 `listByTag` 的分工是硬的**：IYUU 转移/辅种加进来的种子**不带 OSR 标签**，`listByTag` 一条都看不见。自动删种、以及"种子是被删了还是被转移走了"的判断都必须用 `listAll`。`DownloadTrackTask#fetchTorrents` 据此按 role 分流：SEED_ONLY 拉全量，DOWNLOAD 仍按标签拉（避免把用户手工添加的一大堆无关种子拉进来）
