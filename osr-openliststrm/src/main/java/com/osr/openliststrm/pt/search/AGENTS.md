# PT 资源搜索

不建订阅、按关键词直接搜全部站点的那一页（`ResourceSearchService` + `PtResourceSearchRestController`，前端 `views/openlist/ptSearch` 两端）。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **检索、去重、解析、H&R 标记与订阅内手动搜索共用同一份实现，不要在这里另写一份**（`SearchSupplementService#searchKeyword` / `#toCandidateDtos` / `#torrentOf`，`SubscriptionEngine#markHitAndRun` / `#fillParsed` / `#savePathFor`）。两边各写一份的表现是「资源搜索页看到 2160p、订阅里同一个种子是 1080p」或「这里推下去落进 /电影、订阅推下去落进 /剧集」——不报错，只是同一个种子在两个入口得到不同答案。站点范围的语义也照搬（`resolveIndexerScope`）：**勾选的站点全部不可用时报错，绝不退回搜全部**，勾选站点多半是为了避开某个站。
- **全局过滤规则只标注、不淘汰**（`SearchCandidateDTO#ruleRejection` 短标签 + `#ruleRejectionDetail` 带实际值与阈值）。用户来这一页正是要看「站上到底有什么」，按规则滤掉一批的话他会以为站上没有；而「这条会因为分辨率不在白名单被挡」恰好回答了「为什么我订了却没自动下」。三条：用的是**全局**规则、不带任何订阅级覆盖（没有订阅可取）；**原始语言传 null**，「中字」那一项按影片原语言判、这里判不了，宁可不标也不能乱标；**判定本身出错时照样返回结果、只是不带标注**，不能让一张坏掉的规则配置把搜索整个拖垮。体积规则按每集判定时要先折算集数（`EpisodeCountResolver.resolve(t, null, looksLikeMovie)`），否则季包会被成片标成「体积超上限」。
- **直接下载不建下载记录，这是权衡后的结论，不是漏做**。`pt_download_record.sub_id` 是 NOT NULL，下载追踪、记录页的归属隔离（`DownloadRecordAdminService#canAccess`）、统计、完成后提前对账（`DownloadCompletionSyncService` 直接 `refresh(record.getSubId())`）都以「记录属于某个订阅」为前提，为这一个入口放开要逐处审三十来个调用点。于是直接下载的种子在 OSR 看来与用户在下载器里手动加的种子是一回事：不追踪进度、**不下发 H&R 分享限制**（那一步在 `DownloadTrackService` 里、依赖记录）、不进下载记录页；下载完成后按目录触发的同步 / STRM 照常生效。四条不要改坏的：
  1. **标签用 `osr-manual`，绝不能带下载器的跟踪标签**（`downloader.getTag()`）。后者会让 `DownloadTrackTask` 按标签拉回一个没有记录与之对应的种子。
  2. **只做种（SEED_ONLY）与停用的下载器一律拒绝**，判据与订阅推送同一份（`participatesInDownload()`）——保种机开着按「保种」设计的清理规则，新下载推上去会被当保种种子清掉。下拉框（`/downloaders`）也只列这一类，两处口径一致。
  3. **限管理员**：它绕开了订阅、种子没有归属，多用户部署里不该让每个人都能往下载器里塞东西；普通用户走「转为订阅」。搜索本身对登录用户开放（与订阅内手动搜索同一档）。前端按 `roles.includes('admin')` 藏按钮只是减少误点，挡人的是后端 `denyIfNotAdmin`。
  4. **H&R 站点的候选，推送弹窗要明说不会照看考核**（`ResourcePushDialog`）。不说的话用户会默认它和订阅下的一样被追踪。
  真要让直接下载也被追踪，正确的做法是先给订阅之外的下载建一个归属概念，而不是把 `sub_id` 改成可空了事。
- **「转为订阅」带的是解析出的片名**（`parsedTitle`，去掉了分辨率与发布组），解析不出才退回原标题；电影/剧集按「有没有季号或集号」判（`looksLikeMovie`，前后端同一判据）。落点是订阅页的 `?subscribe=片名&mediaType=TV|MOVIE`（`composables/useSubscribeDeepLink.ts`）：**用 watch 不用 onMounted**（订阅页开了 keep-alive，第二次跳过来 setup 不再跑），**处理完立刻 `router.replace` 抹掉这两个参数**（否则刷新一次就再弹一次弹窗）。没有订阅页菜单权限时提示而不是跳 404。
