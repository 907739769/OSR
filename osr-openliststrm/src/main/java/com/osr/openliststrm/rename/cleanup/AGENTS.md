# 重命名产物清理

删产物与删记录的边界、清理顺序、空目录回收、换位与 source_missing。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **「删产物」与「删记录」是两件不同的事，任何入口都不许合并成一个按钮**。`rename_detail` 同时是三处判据的事实来源，只删数据库行是「失忆」操作：①孤儿正向扫描以它为遍历起点，删了就再也发现不了那些文件；②`ScrapeService` 的 `hasSiblingInSameSeason/Show` 靠它计数，行少了会让别的记录删刮削时误删还在用的 `tvshow.nfo`/`season.nfo`/剧集图；③`MediaRenameProcessor#processOnce` 的 `processedKeys` 也来自它，删了记录后手动执行任务会把源文件当成没处理过、重新复制一份出来，用户会看到「明明删了怎么又冒出来」。删产物走 `rename/cleanup/RenameCleanupService#purge`，前端确认框必须把「只删记录」的这三条后果写出来
- **清理顺序是硬要求：先删文件、后删记录**。删完记录就没有 `new_path`/`new_name` 可用了，而且兄弟判定要靠这些行还在才算得对。**批量清理必须把整批 id 一次性传给 `ScrapeService.DeleteOptions#excludeDetailIds`**：逐条调用时兄弟计数是边删边变的，不排除整批的话前几条会认为"还有兄弟"而跳过共享元数据，只靠最后一条兜底——中途任何一条失败就留下没人认领的 `tvshow.nfo`
- **空目录回收必须锚定 `电影`/`电视剧` 那一层，找不到锚点就一个都不删**（`ArtifactPaths#mediaRootOf`）。这两个顶层目录名是 `MediaRenameProcessor#buildDestPath` 硬编码产出的，因此一定出现在每条产物路径里，可以拿来当可证明的下界；删到它或它的祖先，Emby/Jellyfin 的媒体库根目录会直接失效。回收只删 `Files.newDirectoryStream` 为空的目录，**绝不用递归删除**
- **重命名换位（改标题重试导致落到另一部剧）必须调 `RenameCleanupService#purgeRelocated`，且必须在改写记录的 `new_path`/`new_name` 之前调**。旧实现只删旧主文件，旧目录里的单集 NFO、`season.nfo`、`tvshow.nfo` 和七张剧集图原样留下，Emby 会扫出一个只有元数据没有视频的鬼剧集。**`keepDir`/`keepShowRoot` 不能省**：此刻记录的 `new_path` 还是旧值、兄弟判定又会排除自己，不传的话"同剧还有没有别的记录"会答成"没有"，把新位置正要用的 `tvshow.nfo` 一起删掉
- **清理 `source_missing` 必须连中间产物一起删，且 `purgeStrmSource` 要排在 `purge` 之前**。`purge` 只删目标库产物和 `rename_detail` 行，`source_folder`（典型是 `/data/strm`）里那个 `.strm` 原样留着，于是构成一个**每天一轮的复发闭环**：用户清理 → 次日 02:00 的 `OpenListStrmTask#rename` 全量重扫 → `MediaRenameProcessor#processOnce` 的 `processedKeys` 从 `rename_detail` 重建（那行刚被删）→ 中间 `.strm` 被当成没处理过（`.strm` 还不受 `minFileSizeBytes` 门槛约束，见 `processOnce` 里 `!isStrm && size < min` 的写法）→ 重新刮削、重新复制回目标库 → 死链复活 → 次日 06:00 孤儿扫描再报同一条，每轮白烧一次 TMDb 配额。用户看到的就是「明明删了怎么又冒出来」。**顺序是硬要求**：闭环成立的两个条件是「`rename_detail` 行没了」+「中间 `.strm` 还在」，先断后者则任何一步失败都落不进闭环；反过来调的话，`purge` 成功而 `purgeStrmSource` 失败正好把两个条件凑齐。**只动 `.strm`，非 `.strm` 一律不碰**——那是网盘挂载或下载器保种目录里的真实文件，删它等于毁保种；`.strm` 是 OSR 自己生成的纯派生物，删错重跑 STRM 任务就回来。构造上 `source_missing` 也只可能出现在 `.strm` 上（`scanForward` 对非 STRM 记录直接跳过网盘核对），`purgeStrmSource` 里再判一次是防御性的。**`local_missing` 不做这件事**：那是「网盘源还在、只是本地产物没了」，中间产物要留着让重命名重新产出。删 `openlist_strm` 生成记录同样不能省——留着的话，网盘上同路径的文件日后重新出现（重新下载、辅种回来）时 `StrmServiceImpl#getData` 的 `existingKeys` 会认定它「已成功处理过」而跳过，`.strm` 再也不会重新生成，最终库里永远缺这一集且没有任何失败记录可查
