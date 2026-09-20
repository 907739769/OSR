# 重命名一致性检查（孤儿扫描）

双向孤儿扫描的去重键、mtime 基线、截断与同剧兄弟判定。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **孤儿判定**: `orphan/OrphanReconciler` 纯逻辑无 I/O，方便单测覆盖；`RenameOrphanScanServiceImpl` 负责实际 I/O
- **孤儿扫描是双向的，两个方向的去重键不同**。正向（记录→文件）挂 `detail_id`，反向（文件→记录，`local_extra`/`metadata_only`/`empty_dir`）没有 detail 可挂靠，`detail_id` 为 NULL、去重靠 `(new_path, new_name)`。MySQL 的 UNIQUE 索引允许多行 NULL，所以 `uk_detail_id` 能原样保留。反向发现必须**先登记 foundKeys 再判上限与忽略**：正常落库、超上限丢弃、用户已忽略这三条路径都得算"本轮见过"，否则轮末的"已恢复"清扫会把它们删掉，下一轮再重新发现，列表来回抖动、用户点的忽略也白费
- **反向扫描的单轮上限超了要记进 `ScanSummary#truncated` 并打日志**，静默截断会读成"已经扫全了"。被丢弃的项不写库也不影响下一轮重新发现。**额度按文件级/目录级分开**（`MAX_FILE_FINDINGS` 1500 / `MAX_DIR_FINDINGS` 500）：`visitFile` 在遍历顺序上永远排在 `postVisitDirectory` 前面，共用一个池子时文件级发现会把目录级发现结构性饿死——而 `metadata_only`/`empty_dir` 恰恰是这个方向最想抓的东西，数量级又天然小得多。实测过一次共用池子的后果：一个混着历史文件的库把 2000 额度全占给了 `local_extra`，目录级发现一条都露不出来
- **反向扫描有 mtime 基线：早于该 `target_root` 对应任务 `create_time` 的文件/目录一律不上报**（`ScanRoot#baselineMillis` + `beforeBaseline`）。判据是"任务建起来之前就躺在库里、此后又没被动过的东西不是它产出的"——没有这道闸，一个混着历史文件（以前手动整理的、别的工具建的、STRM 任务直接写进去的）的媒体库每轮都会把成千上万个与重命名无关的文件报成 `local_extra`。真残骸不受影响：只删记录留下的文件、改标题重试留下的元数据，写入时刻都在任务创建之后。**`stat.mediaFiles++` 必须排在基线判定之前**——被基线放过的历史文件同样是"这个目录里有主媒体文件"的证据，漏计会让整个目录被判成 `metadata_only`/`empty_dir`，而那两种是可以点清理的，等于拿基线换来一次误删。同一锚点被多个任务共用时取**最早**的 create_time（晚建的任务不该把早建任务的产物挡在基线外）；`create_time` 是 `BaseEntity` 里的 **String**（`MyMetaObjectHandler` 按 `yyyy-MM-dd HH:mm:ss` 填），解析不出来时退化为 0 即不过滤——保守方向是宁可多报
- **`hasSiblingInSameShow` 的前缀匹配要补路径分隔符再匹配，且值要过 `ArtifactPaths#escapeLike`**。不补分隔符时 `/电视剧/国产剧/三体` 会连 `/电视剧/国产剧/三体2` 一起算进来，"同剧还有别的记录"恒为真、`tvshow.nfo` 永远删不掉；不转义时路径里的 `_`（发布组命名的常态）在 LIKE 里是"任意单字符"，同样会误配
