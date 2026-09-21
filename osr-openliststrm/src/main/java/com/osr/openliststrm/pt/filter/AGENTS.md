# PT 过滤规则

标题/描述两套关键词的判定对象与缺失时的取向。

> 本文件由根 `AGENTS.md` 拆出，只在改到本目录时才载入。全局约定（分层、命名、异步包装、日志纲领）仍在根 `AGENTS.md`。
> 新增本域的踩坑记录写这里，不要往根 `AGENTS.md` 里塞。

## NOTES
- **PT 过滤的关键词有标题、描述两套，判定对象不同、缺失时的取向也相反**。`exclude_keywords` 只匹配标题，`description_exclude_keywords` 只匹配描述（`TorrentFilterEngine#rejectReason`，两条相邻）。加后者是因为有一类属性标题里根本不写——蓝光原盘最典型：国内站只在种子描述里标一句「原盘」，标题与压制版逐字同构，两者都解析成 `source=BluRay`，来源白名单分不开；体积上限虽能挡住原盘，却会连体积区间重叠的 REMUX 一起切掉，而 REMUX 是 mkv、播放器本来吃得下。两条不要改坏的：
  1. **标题为空一律淘汰（`BLANK_TITLE`），描述为空一律放行**。标题是索引器必给的字段，描述不是——不少索引器压根不返回 `<description>`，按「判不出即淘汰」处理会把这些站点的候选整批清光。
  2. **`EXCLUDED_DESCRIPTION_KEYWORD` 与 `EXCLUDED_KEYWORD` 是两个码，别合并**。命中的是标题还是描述，决定用户该去改哪个输入框，聚合成一个就分不出来了。
- **`FilterCriteria` 一律用 `FilterCriteria.builder()` 构造**，不要用位置参数：16 个分量里有 9 个是 `List<String>`，顺序写反编译器发现不了；新增维度时 builder 调用方也不必补占位参数
- **`TorrentFilterEngine` 只有 2 参与 4 参两种签名，不要再加三参重载**。历史上 `(…, TorrentBlacklist)` 与 `(…, String originalLanguage)` 两个三参重载只靠第三参类型区分，`SearchSupplementService` 调错了版本，导致手动搜索候选列表不受黑名单约束，用户选中后推送侧再拦下，只回一个没有原因的失败
- **种子的 `parsedTags` 是 `MediaInfo.tags` + 视频编码 + 音频编码的并集**（见 `SubscriptionEngine#collectTags`）。extractor 按 Resolution → Codec → SourceAndGroup 顺序跑，`CodecExtractor` 会先把 `Atmos`/`H265`/`DTS-HD` 匹进 `audioCodec`/`videoCodec` 并从标题里抹掉，只读 `tags` 的话「必须带 Atmos」这类配置会一条都匹配不上
- **`TorrentInfo.files`（Torznab `files` 属性）是推送前唯一能证伪「整季包」的硬信号，只能单向使用**。「按季包命名、实际只含 1 集」的种子与真季包在标题上完全一致，体积也分不开——8GB 可能是 8 集 × 1GB，也可能是 1 集 Remux，所以**任何绝对体积阈值都拦不住它**。包内集数不可能超过文件总数，`files` 一旦给出就是可靠上界：`EpisodeCountResolver#capByFileCount` 用它给集数估算收口（只收口不放大——真季包常带 nfo/字幕/封面，files 大于集数是常态，拿它当集数会把每集体积折算得过小），`SubscriptionEngine#preferCompletePacks` 在季包目标下把 `files < 待占位集数` 的候选让位给不矛盾的候选。**`files` 为 null 表示「索引器没提供」，绝不能落成 0**（0 的语义是"覆盖不全的证据"，两者处理方式完全相反，见 `TorznabParser#parseNullableInt`）；null 时一律维持既有行为，交给 `DownloadTrackService#reconcileClaims` 事后对账兜底。不剔除的代价是实打实的：假季包只要在做种数等任一维度赢下 `pickBest`，就会占位**整季**缺失集，等元数据解析完 `reconcileClaims` 再把其余集退回，下一轮真季包只能占到剩下的集——一季被拆成两个种子下载，多一份 H&R 保种义务，先下的那一集与其余集大概率不是同一版本。两条保守约束：只占 1 集时不启用（`files >= 1` 恒成立，判据不成立）、候选全部覆盖不全时不剔除（宁可下一个只覆盖部分集的包）

- **REMUX 在解析器里是「标签」，在 PT 侧是「来源」，归一化只在 PT 侧做（`MediaSource`）**。`SourceAndGroupExtractor` 把 `Show.2160p.BluRay.REMUX` 解析成 `source=BluRay, tags=[REMUX]`，而来源白名单 / 来源优先级 / 洗版目标来源的界面与默认值都把 REMUX 当来源——修复前只写 `REMUX` 的白名单淘汰全部种子、`REMUX,BluRay` 的优先级里两者并列，全部静默。现在 `SubscriptionEngine#fillParsed` 与 `QualityProfile` 构造器都经 `MediaSource.effective` 还原（后者顺带修正存量基线，否则库里的 REMUX 会被另一个 REMUX「升级」掉）；比较一律走 `MediaSource.in/rank`：**列表里没写 REMUX 时 REMUX 按 BluRay 对待**，保证写 BluRay 的存量配置行为不变。**不要改解析器本身**——`{{source}}` 进了重命名模板，改它会让存量媒体库命名口径分叉。
- **分辨率 / 来源 / 标签三类字段的可选值只有 `FilterVocabulary` 一份**，前端从 `/vocabulary` 取、只许下拉选（后端全等比对，手打的 `WEB-DL`/`4K` 永远命不中）。它必须跟着解析器走，`FilterVocabularyTest` 用真实解析器逐条标题校验——这条用例上线当天就逮到 `CodecExtractor` 把 `AC3`/`EAC3` 削成 `AC`/`EAC` 的老 bug。
- **全局过滤规则的保存走 `FilterConfigAdminService#save`，写接口仅管理员**。`FilterConfigCheck` 挡体积下限 > 上限、负阈值、写错的排序维度；洗版开着时，**这次修改新引入**的洗版一致性问题（如清空来源优先级）会被拒绝，原本就有的不拦。三个优先级列表变了会重置洗版评估（见 `pt/upgrade/AGENTS.md`）。`/preview` 用**未保存**的规则试算一条标题，黑名单取已保存的。
