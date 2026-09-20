# 文件重命名与解析

解析管线、标题归一化、括号/年份/发布组的抽取规则。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **重命名流程**: `MediaParser.parse()` → 本地正则抽取 → TMDb 增强 → AI 补充 (如需) → Pebble 模板渲染
- **比较用标题归一化只有一份：`rename/TitleNormalizer#normalizeForCompare`**，PT 订阅匹配（`SubscriptionMatcher#normalize`）与 TMDb 刮削（`TMDbClient#normalizeForCompare`）都委托给它，**任一侧都不要另写**。分叉过一次：刮削侧剥掉全部标点、PT 侧只处理 `. _ -`，于是《神探夏洛克：可恶的新娘》在刮削侧能匹配、在订阅匹配侧却因一个全角冒号漏搜。字符类要覆盖 `\p{Punct}`（ASCII，含 `~ + = < > | $ ^` 这些 Unicode 归为 Symbol 的）、`\p{IsPunctuation}`（全角/CJK 标点、破折号、`_`）、`\p{IsWhite_Space}`（含全角空格 U+3000——Java 的 `\s` 不认它）、以及显式的 `～`(U+FF5E)/`〜`(U+301C)（Unicode 里算 Symbol 不算 Punctuation）。**标点替换成空格而不是删除**：删除会让 `M*A*S*H` 塌成 `mash` 误撞另一部叫 MASH 的作品，也会让 `The Office US` 塌得离 `The Office` 更近。归一化结果**只用于比较，绝不参与任何输出**
- **两侧的比较方式仍然不同，这是有意的**：PT 侧只认全等（结论直接决定推哪个种子，推错就是下错内容），刮削侧允许「长包含短」（只用来决定证据够不够采纳，还有 AI 兜底）。共用的是字符归一化，不是判定策略
- **`TitleProcessor` 的方括号分支要求括号内真的含中文**。原正则捕获组是 `[^\]】]+`，任意内容都收，于是 `[Nekomoe kissaten]`、`[FRDS]` 这类发布组/站点标签被当成中文标题，真正的作品名被挤进 `englishTitle`——PT 侧标题匹配不上（漏搜），重命名侧拿着发布组名去 TMDb 搜索，而且 `MediaParser#needsAI` 的条件是 `tmdbId` 为空，一旦搜到<b>任何</b>结果 AI 兜底就不再触发，错误命名是安静发生的
- **`YearSeasonEpisodeExtractor` 的年份取最后一个匹配，不是第一个**。片名本身就是四位年份的作品（《1917》《2012》《1984》《2046》）在 `1917.2019.1080p...` 这类名字里会让第一个匹配落在片名上，年份取成 1917、标题又被按它的位置截成空串。发行年总排在片名之后，取最后一个才是它；截断后标题为空时年份值照留但不参与截断（`2012.1080p` 这种没有发行年的名字）
- **`MediaParser.parseLocal()` 不剥扩展名**（`stripExtension=false`），种子标题本来就没有扩展名。**测试夹具不要给种子标题补 `.mkv`**：补了之后标题以 " mkv" 结尾，`SourceAndGroupExtractor` 的结尾段判定（要求那一段由 `-`/`@` 引导或自带连字符）匹配不到发布组，`parsedReleaseGroup` 恒为 null，一切依赖发布组的逻辑（发布组黑名单、发布组优先级）都会静默失效。这个错误前提曾同时写进 `SubscriptionEngineTest` 与 `PtTorrentBlacklistPlusServiceImplTest` 并让两条用例长期红着
- **发布组名自带连字符时，整段都是组名，不能只取最后一节**（`SourceAndGroupExtractor#groupOf`）。旧的 `GROUP_END` 正则 `(?:[-@]|\s@)\s*([A-Za-z0-9_.-]+)$` 是**从左找第一个**连字符当引导符，而排在它前面的连字符（`WEB-DL` 里那个）在上一步已被 SOURCE 摘走，于是 `Gei a ma de qing shu 2026 1080p WEB-DL H.265 AAC lijiang-tv` 解析出的发布组是 `tv`。**两条链路同时失效，且都没有任何错误现象**：发布组黑名单按大写全等匹配（`TorrentBlacklist` + `TorrentFilterEngine`），用户拉黑 `lijiang-tv` 永远命不中，那个组的种子照下不误、日志里每一步都"正常"；重命名产出的文件名尾巴是 `-tv`，而被截掉的 `lijiang` 还留在标题里参与 TMDb 匹配。现在先按空白切出结尾那一段（`MediaParser#normalize` 已把点分隔符换成空格，空白是唯一段边界），再判这一段是不是发布组。四条不要改坏的：**判据仍是"由 `-`/`@` 引导"这个保守取向**——结尾是不带连字符的普通词（`2026`、`2Audio`）时宁可不认，认错会把作品名的一部分当成发布组截掉，那比解析不出发布组糟得多；**`EPISODE_LIKE` 的判定对象变成了整段**，区间两端都要写进正则（`S01E01-S01E04` 整段命中，旧版是拿 `S01E04` 单节去比），漏了的话季集区间的结尾会被当成发布组吃掉、`YearSeasonEpisodeExtractor` 再也看不到它；**`NOT_GROUP` 兜的是"技术标识摘不掉"的情况**（`info.getSource()` 已被赋值时 SOURCE 分支不执行，`WEB-DL` 会原样留到结尾，而它内部正好有个连字符）；**`@` 一律当分隔符**（`@Group`、`1080p@Group` 取 `@` 之后，`AAC @ Group` 这种隔着空格的由 `GROUP_AT_SPACED` 单独先试——组名单独成段时看不出它由 `@` 引导）。修复不会追溯已生成的文件名，存量要重新刮削才更名；用户此前从下载记录点"拉黑发布组"存进库的可能正是错值（`TV`），要去黑名单页面核一眼
