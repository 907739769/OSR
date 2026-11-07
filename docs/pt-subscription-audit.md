# PT 订阅、RSS、手动下载与补搜分析

分析日期：2026-11-07；分析基线：`6dd566fb`。

**修复进展：本文记录的是修复前的问题。已完成代码修复与回归验证，当前行为及验证结果见 [修复说明](pt-subscription-fixes.md)。**

结论：主流程和保护机制已经比较完整，但资源身份、集号换算、候选淘汰与重试调度之间仍有断点。当前应优先修复会下错、会漏下的判定逻辑，再补齐诊断数据；单纯放宽关键词、增加补搜次数或缩短 RSS 周期，不能解决这些问题。

分析阶段为代码审查与隔离复现，没有连接用户的真实 PT 站点、下载器或媒体库。因此下面能说明“什么条件会出问题”，不能断言某条真实订阅已经因该问题失败，也不能给出线上发生率。

## 1. 当前功能与链路

| 入口 | 现有行为 | 与错下、漏下相关的边界 |
| --- | --- | --- |
| 新建订阅 | TMDb 确认作品、季与集数；查询媒体库；建订阅与集表；异步补搜一次 | 新建时的一次搜索不等于持续自动补搜；缺失、在途、已入库是不同状态 |
| RSS | 按索引器周期拉取首页；本地解析；外部 ID、标题与描述别名匹配；按订阅和集号分组推送 | 只处理窗口中看见的条目；资源离开窗口后不能依赖 RSS 找回 |
| 订阅内自动推送搜索 | 单集走 ID → 关键词 → 英文/原名回退；整季走统一补齐流程 | 判断“是否还要扩大搜索”必须考虑规则过滤和推送结果 |
| 订阅内手动选择 | 汇总 ID 与关键词搜索；作品/季集匹配；过滤规则淘汰；返回剩余候选；点选后重新解析、过滤、占位、下载 | 当前手动候选列表仍会隐藏被规则拒绝的资源；手动推送绕过历史不可重试失败的排除，但仍受规则和缺集状态限制 |
| 资源搜索页直接下载 | 全局规则只标注、不淘汰；管理员选下载器直接推送，标签 `osr-manual` | 按当前明确设计，不建订阅下载记录，不追踪进度、不执行订阅 H&R 跟踪；不能当作订阅补齐成功 |
| 定时补搜 | 仅 ACTIVE、开启自动补搜、有 MISSING 集的订阅；过滤未播集；季搜索候选池匹配；必要时补发单集搜索 | 有单集补发上限与时间预算；连续落空退避会显著拉长后续等待 |
| 下载跟踪 | 查种子状态；多集包选文件；实际集数纠正占位；下载完成后触发后续同步 | 当前文件级校验还没有完整覆盖季号、文件类型和文件内集号区间 |
| 媒体库对账 | 确认入库；补全订阅资料和新增集；只升不降 | 已入库但后来删掉的文件，需要显式重置；已下载但未入库不应自动全部重下 |
| 缺集体检 / 一键诊断 / 工作流 | 已有逾期分档、淘汰原因聚合、下载与后续处理关联查询 | 应在这些现有入口上补充证据，避免再建一个口径不同的诊断页面 |

主要实现入口：

- 建订阅：[SubscriptionService.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SubscriptionService.java)。
- RSS：[RssPollService.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/task/RssPollService.java)。
- 推送主干：[SubscriptionEngine.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SubscriptionEngine.java)。
- 自动补齐：[SearchSupplementService.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SearchSupplementService.java)。
- 文件选择：[DownloadTrackService.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/task/DownloadTrackService.java)。
- 独立直接下载：[ResourceSearchService.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/search/ResourceSearchService.java)。

## 2. 已隔离复现的 9 个问题

优先级 P1 表示直接影响正确性，应进入首批修复；同名歧义一项需要结合证据设计，不能用简单年份全等替代。

### 2.1 P1：ID 搜索的来源被当成“作品身份已确认”

位置：[SearchSupplementService.filterIdCandidates](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SearchSupplementService.java)。

当前仅拒绝 `identityOf == DIFFERENT`，`UNKNOWN` 仍然放行。电影分支不检查标题和年份；剧集分支虽然检查季集与单向年份，仍不检查标题。

复现：电影订阅为 2024 年、TMDb ID 为 123；把 `Totally.Unrelated.Movie.1980.1080p.BluRay` 作为 ID 检索返回条目，条目没有可比外部 ID，也没有季集信息。该候选通过身份筛选。

这是有条件的风险：需要索引器忽略、错误支持或降级处理 ID 查询。并非所有 ID 搜索都会返回错误资源。但既然代码已承认索引器可能忽略参数，就不能再把“来自 ID 请求”作为充分身份依据。

优化：候选自身有可比 ID 且相同，才使用强身份路径；有冲突则拒绝；无法确认则走标题、类型、年份和季集校验。保留查询来源作为证据，不能让它替代作品身份。对明确相同 ID 的正确资源，RSS 和搜索还应采用一致的缺年份处理政策。

验收：忽略 ID 参数的索引器返回其他电影、其他剧时不得自动下载；正确 ID、不同译名的条目仍然可以匹配。

### 2.2 P1：文件选择只比较集号，其他季的同集号也能通过

位置：[DownloadTrackService.trySelectFiles](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/task/DownloadTrackService.java)；完成前补确认也有同样问题：[confirmEpisodesIfNeeded](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/task/DownloadTrackService.java)。

循环解析文件后只取 `info.getEpisode()`，没有把季号纳入目标判定，也没有在收集实际集号前限定为视频文件。

复现：目标是 S01E01，文件列表只有 `Some.Show.S02E01.2160p.mkv`。文件未被排除，种子被恢复下载，记录标记为已完成文件选择。按当前对账逻辑，这个 E01 还能参与文件确认。

影响：多季合集会误留其他季的同号文件；若站点种子标题与内部文件冲突，最后一道护栏也无法拦住。误确认还会让集一直处于“文件已存在、等待入库”，妨碍后续补齐。

优化：用统一的文件覆盖解析器产出“作品约束、季、集号集合、证据来源”；对视频确认和字幕保留分别处理。明确其他季要拒绝，缺季号才允许按订阅补足；绝对编号与本地编号需要显式转换。完成前补确认复用同一实现。支持一个视频文件覆盖多集，避免只确认区间起点。

验收：多季包、S02E01 对 S01E01、字幕有集号但视频不含目标集、多集视频、绝对编号、未解析文件名均需覆盖。

### 2.3 P1：绝对编号区间匹配成功后，推送时被原始编号覆盖

位置：[SubscriptionEngine.handleGroup](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SubscriptionEngine.java)。

匹配器已把绝对编号换成本地范围，但择优之后又直接使用 `best.parsedEpisode / parsedEpisodeEnd` 重建 `MatchResult`。

复现：第 3 季共 16 集，绝对编号 51～66 对应本地 1～16。真实匹配器成功返回 1～16；把同一候选交给真实推送引擎后，范围回到 51～66，找不到可占位集，返回“该集刚被另一次推送占位”。这里没有实际并发占位。

影响：RSS、自动补搜和手动区间推送共用该主干，都会存在“列表能看到、匹配也正确、最后推不动”的条件。

优化：将覆盖范围作为已经归一化的独立结果传到底；确需展开 best 覆盖范围时必须使用订阅对应映射，不能重新信任原始编号。范围重算后若目标为空，应报告实际原因。

验收：真实解析器 → 真实匹配器 → 真实引擎贯通验证；不能只 mock 引擎后断言它收到了正确参数。

### 2.4 P1：季包完整性预筛先于规则过滤，导致可下载候选被提前丢弃

位置：[SubscriptionEngine.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SubscriptionEngine.java) 与 [preferCompletePacks](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SubscriptionEngine.java)。

复现：缺 16 集，仅允许 2160p。候选 A 是 2160p、files=1，可以补部分内容；候选 B 是 1080p、files=16。A 单独能过过滤，但同时出现时，完整性预筛先删除 A，之后 B 被分辨率规则拒绝，最终一个都不下载。

优化：先完成黑名单和硬性规则过滤，再在可下载候选中比较覆盖度。保留“全部只能覆盖部分时仍允许补一些”的既有意图；最好将覆盖度作为择优维度，避免无法回退的提前删除。

验收：完整候选因分辨率、免费、做种数、H&R 等任一硬规则失败时，仍能选用合格的部分覆盖候选。

### 2.5 P1：季候选池里有某集的资源但推不动，不再补发该集搜索

位置：[SearchSupplementService.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SearchSupplementService.java)。

只有 `episodeCandidates.isEmpty()` 才加入单集补发队列。已经匹配到候选，但候选被质量规则、历史记录等淘汰时，不会再为这一集发起精确检索。

复现：季搜索返回某集候选，推送返回失败；单集补发额度设为 5。实际没有执行 `Some Show S01E01` 请求。

影响：季粒度查询返回了低质量或不可用版本，而精确单集查询可能找到合格版本；这条补齐机会被抑制。

优化：按失败原因决定后续动作。全部被规则淘汰、候选历史不可用等可进入单集扩搜；已经被其他任务占位则结束；下载器满载应排队重试，避免继续搜索；身份冲突需要换查询或人工确认。把 `pushBest` 的 boolean 扩展为供自动编排使用的结构化结果，复用已有 `PushOutcome` 思路。

验收：季搜索只有 1080p、单集搜索能返回合格 2160p；上一版本不可重试、精确搜索能返回另一资源；下载器满载不得白打所有回退请求。

### 2.6 P1：最低负载下载器满了，其他有余量的下载器未被尝试

位置：[SubscriptionEngine.resolveDownloader](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SubscriptionEngine.java)，以及选择后的容量判断：[handleGroup](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SubscriptionEngine.java)。

当前先按在途绝对数量取最小，再判断它是否达到自身并发上限。

复现：A 当前 1/1，B 当前 2/10。选中 A 后返回“并发已达上限”，没有使用 B 的 8 个余量。

优化：自动分配时先筛可用、未满载的下载器，再按负载或利用率择优。用户显式指定下载器时，应明确采用“等待指定下载器”或已有改派政策，不能用自动均衡掩盖该选择。并发配额还应覆盖跨 RSS、补搜调用；目前负载锁仅在本次调用的 Map 内生效。

验收：不同并发上限、多个入口同时推送、全部满载、固定下载器与仅做种下载器。

### 2.7 P1：新同名剧能误匹配老同名剧，年份护栏只有一个方向

位置：[SubscriptionMatcher.seriesYearPlausible](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SubscriptionMatcher.java)。

当前只拒绝明显早于首播年的资源；晚于首播年的一律允许。这对长寿剧后续季有合理性，但不能完成同名剧消歧。匹配又取首个命中订阅，RSS 活跃订阅按 ID 升序排列。

复现：没有可比外部 ID，订阅是 `Dark Matter (2016) S01`，资源为 `Dark Matter (2024) S01E01`。匹配成功。

优化：采纳可比外部 ID；无强身份时，结合 TMDb 季首播日期、订阅季号及同名订阅集合判歧义。多个作品都解释得通，应保留候选供人工处理，不能由数据库顺序决定。不能简单把所有剧集年份改为全等，否则老剧新季会大量漏下；只订阅了老剧时也不能仅靠“订阅集合无重复”声称身份确定。

验收：两个方向的同名剧、长寿剧新季、未标年份、明确相同 ID 但不同标题、相互冲突的 ID。

### 2.8 P1：索引器全失败仍推进搜索时间，被当作“没有资源”退避

位置：[SearchSupplementService.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SearchSupplementService.java)、[updateLastSearchTime](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SearchSupplementService.java) 与 [AutoSearchService.trySearch](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/task/AutoSearchService.java)。

已有 `anyResponded` 能阻止全站故障时继续补发请求，但它没有进入对外摘要。全失败后仍返回未跳过、未推送、无淘汰指纹的普通摘要，并更新 `last_search_time`。自动层把无指纹结果记成 `NO_CANDIDATE`、累计落空。

复现：索引器所有关键词请求都抛 IOException，仍调用了 `updateLastSearchTime`，返回值没有基础设施失败状态。

影响：短暂故障恢复后，订阅仍可能等待按落空次数计算的长周期。默认 24 小时基准下，首轮落空会将下一次间隔拉到约 48 小时再加抖动；连续落空还会继续变长。并发已满等非资源原因也缺少独立摘要，存在类似混淆。

优化：至少区分完整检索无结果、候选被拒绝、部分检索、全部请求失败、容量等待、状态已满足。只对有足够检索证据的无资源结论累计片源退避；故障遵守索引器冷却并另设短重试；容量等待由名额释放触发或短间隔重试。记录“尝试时间”和“有效检索完成时间”，避免共用一个时间字段。

验收：全超时、全限流、部分站点应答、预算截断、下载器满载和确实无资源，不能都呈现同一个落空结论。

### 2.9 P1：英文主标题同名时提前认领，副标题的中文名与年份冲突均未参与判断

用户补充的实际误下载资源：

```text
主标题：The Silent Storm S01E13 2160p TX WEB-DL AAC2.0 H.265-MWeb
副标题：宿敌 | 2024 | 中国大陆 | 剧情 悬疑 犯罪 | 奇道 | 廖凡 朱珠 | 第1季第13集
用户期望：订阅今年新出的《谷雨》；用户反馈两部作品英文名相同。
```

按用户描述重建订阅 `title=谷雨, originalTitle=谷雨, englishTitle=The Silent Storm, year=2026, season=1`，使用上述原始主副标题、真实本地解析器与匹配器复现。这里的订阅字段是复现输入，未从用户生产库读取；种子的外部 ID 未提供，按未知处理。

实际判定链：

1. 主标题解析出 `The Silent Storm`、S01E13，但没有解析到年份。
2. `SubscriptionMatcher.match` 在主标题阶段找到与订阅英文名的交集后直接返回，副标题不再参与作品核对，见 [SubscriptionMatcher.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SubscriptionMatcher.java)。
3. 副标题首段“宿敌”其实可以由现有 `DescriptionAliases.parse` 正确抽取。但它目前仅用于主标题失败后的正向兜底，没有“与中文作品名冲突”的反证作用。
4. `fillParsed` 的 `parsedYear` 取自主标题解析，副标题独立的第二段 `2024` 没有补入，见 [SubscriptionEngine.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SubscriptionEngine.java)。
5. `seriesYearPlausible(2026, null)` 返回 true，季集号也通过，于是《宿敌》资源被《谷雨》订阅认领。将候选年份显式补为 2024 后，当前年份护栏能够拒绝它。因此这个实例的关键是“副标题证据未使用 + 缺年份放行”，与 2.7 的“新同名剧匹配旧剧”方向不同。
6. 关键词补搜的 `titleMatches` 也在主标题命中时直接返回 true，见 [SearchSupplementService.java](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SearchSupplementService.java)。已复现 RSS 匹配与关键词补搜筛选都能放行同一候选。

优化：主标题命中应产生候选身份结论，不能立即结束全部校验。针对可识别站点模板，从副标题首段提取中文作品名，从独立年份段提取作品年份，保留来源与可信度；英文名相同但中文作品名冲突时，应先用可比外部 ID、已确认别名和年份消歧，不能只因英文名相同就自动下载。缺副标题或换译名不应一律淘汰；无法确认的冲突候选应保留供人工选择。

RSS、关键词补搜、ID 检索和手动推送应使用相同的冲突校验，避免只补一个入口。不要将任意剧情简介中的四位数字当作作品年份，也不要把描述里提及其他作品的名字当作明确冲突。

验收至少包括：本条原始资源；主标题英文相同但副标题中文不同；主标题缺年份但副标题明确为旧年份；副标题不存在；合法中文别名；长寿剧新季；明确相同或不同外部 ID。应断言候选被哪个证据拒绝，而非笼统报“标题不匹配”。

该场景已转为持久回归用例，见修复说明。当前能确认代码缺陷可以解释这次现象；仅凭资源文本尚不能确认该次下载由 RSS 还是自动补搜触发，也不能确认当次候选携带哪些外部 ID。

## 3. RSS、手动操作和诊断的后续优化

以下为代码确认的现状或设计风险，未计入上面的 9 个复现场景。

### RSS 覆盖告警应当有恢复动作

[TorznabClient.fetch](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/indexer/TorznabClient.java) 当前只请求一页，没有 offset/limit 分页编排。[RssPollService.checkCoverageGap](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/task/RssPollService.java) 可以识别窗口可能断档，但处理以日志与通知为主。

建议为支持分页的索引器按能力和预算回溯，达到上次时间位置并留重叠窗口后停止；不支持分页或发布时间不可靠时，为受影响的 ACTIVE 缺集订阅去重排一次补搜。保持每站串行、全局限流和背压，不能通过无上限翻页或简单提频补救。

RSS 拉取成功和候选处理成功应分别记录：目前 `pollOne` 的轮询信息落库后才进入统一 `process`，无法从拉取时间独自证明条目已处理。后续可先持久化轻量候选收件箱，处理失败重放；不能让“拉到了”自动等同于“本轮订阅处理成功”。

### 手动候选不要把资源存在与规则通过混在一起

资源搜索页已有“只标注、不淘汰”，订阅内手动选择却只返回 survivors。用户看到“没有候选”，可能实际是 100 个候选全部被过滤。

建议订阅内手动选择默认显示合格项，并提供“显示被拒绝的候选”；展示作品/季集不符、质量规则不符、历史已下载、状态不可占位等不同原因。若提供单次规则覆盖，应明确覆盖内容并在后端再次校验；身份冲突不能当作普通质量规则静默放宽。

候选返回及回传目前没有保留 IMDb/TMDb ID 和分类，见 [toCandidateDtos](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SearchSupplementService.java)、[torrentOf](../osr-openliststrm/src/main/java/com/osr/openliststrm/pt/subscription/SearchSupplementService.java)。推送时主要重建标题/描述画像，无法重验原来的身份结论。建议保存短期服务端候选快照并返回 candidateId，推送根据快照及当前规则重验；也便于处理停用站点、过期促销和旧页面结果。只让前端增加几个可信字段，不能替代服务端依据。

独立直接下载目前不追踪是明确设计，应保留清楚说明。若要纳入下载记录，需先引入“非订阅下载”的归属模型，同时覆盖权限、统计、H&R、完成处理；只放开 `sub_id` 非空约束不够。

### 诊断需要覆盖匹配前和检索不完整

已有一键诊断与匹配日志较多依赖过滤裁决；RSS 未匹配、作品身份冲突、季号错误、解析失败等更早阶段主要依赖 DEBUG 日志。现有按最近 10 分钟推断轮次，也可能混入同时间的 RSS、手动与自动补搜。

建议增加 `run_id`、入口、站点、计划步骤、请求结果、截断原因和候选阶段裁决，聚合为：

`站点看见资源 → 作品身份确认 → 季集覆盖确认 → 规则通过 → 有下载名额 → 推送确认 → 文件确认 → 入库`

每个阶段给出计数、代表候选、失败原因和下一步动作。记录有效过滤配置及订阅覆盖来源、下一次重试时间；日志限制容量，下载链接和 guid 必须脱敏。沿用现有诊断和工作流页面呈现。

### 状态恢复必须保护已经下载的文件

当前 MISSING → IN_FLIGHT 的条件占位有效，应保留。但占位、记录、HTTP 加种、集关联分别执行；进程退出、响应丢失、落库失败时仍有不一致窗口。建议持久化推送意图和幂等标识，由后台完成加种与确认；恢复时先按 tracking tag / infohash 查下载器，再决定重试，不要一遇超时就重新加种。

现有 `file_confirmed` 与卡死清扫能避免上传/STRM/刮削故障导致无限重下，应继续保留这个边界。优化方向是提供明确的“下载中、下载完成待同步、待 STRM、待刮削、待媒体库确认”阶段；不要为解决“显示在途”而把所有状态退回 MISSING。

## 4. 推荐实施顺序

1. **先挡错下**：修复英文主标题命中后忽略副标题反证、ID 检索 UNKNOWN 放行、文件选择季号与视频确认、同名歧义；统一候选身份和文件覆盖结果。
2. **再修确定漏下**：修复绝对区间覆盖、完整性预筛顺序、下载器容量选择、按失败原因单集扩搜。
3. **修恢复时效**：为全失败、部分检索、无资源、容量等待建立不同结果与重试策略；RSS 窗口断档触发有预算补救。
4. **补可解释性与人工出口**：订阅手动候选显示拒绝项、run_id 阶段日志、候选快照重验、下载完成后续处理定位。
5. **再做持久化编排**：推送意图、崩溃恢复、跨入口配额和候选重放；这部分单独拆改，减少一次改动同时碰状态机与匹配器的风险。

首轮修复至少应以本报告 9 个场景转成真实跨层回归用例。按场景验证实际选中了谁、占了哪些集、是否发起扩搜、保留了哪些文件，避免只验证某个 mock 被调用。

## 5. 本次验证

在环境预置 Java 25 / Maven 和离线依赖下，执行：

```bash
mvn \
  -pl osr-openliststrm -am \
  '-Dtest=SubscriptionMatcherTest,SubscriptionEngineTest,SearchSupplementServiceTest,RssPollServiceTest,DownloadTrackServiceTest,AutoSearchServiceTest,ResourceSearchServiceTest,TorrentFilterEngineFilterTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

结果：**456 个现有测试通过，0 失败、0 错误、0 跳过**。

另使用真实编译后的业务类、Mockito 数据库/外部服务替身及反射调用，隔离复现前 8 个场景，并根据用户提供的资源原文补充第 9 个场景。其中身份和年份案例直接验证对应筛选/匹配方法；绝对区间、季包预筛和下载器容量案例走真实推送判定；文件选择走真实跟踪方法；补搜案例执行真实搜索编排。均不代表真实站点端到端下载验证。

修复前隔离复现使用临时探针；修复后的持久回归用例和验证结果见修复说明。
