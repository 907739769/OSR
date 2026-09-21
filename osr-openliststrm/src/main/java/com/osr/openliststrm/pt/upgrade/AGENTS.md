# PT 洗版

洗版维度、cutoff、状态流转与失败退回。

> 本文件由 `osr-openliststrm/AGENTS.md` 拆出，只在改到本目录时才载入。
> 全局约定与日志纲领见仓库根 `AGENTS.md`，模块总则见 `osr-openliststrm/AGENTS.md`。
> 新增本域的踩坑记录写这里。

## NOTES
- **洗版判定绝不能引入 SEEDERS / SIZE / FREE 维度**（`UpgradeDimension` 只有 RESOLUTION/SOURCE/TAG/RELEASE_GROUP）。那些取值随时间连续变化：同分辨率但做种更多的种子会被判成"更优"，下完之后下一轮又冒出别的做种更多的，于是无限洗版。现有四个维度取值都来自有限集合，字典序比较构成全预序，数学上不存在 A 优于 B 且 B 优于 A 的环——这是"不会来回洗"的唯一保证，`UpgradeEvaluatorTest.比较关系无环_任意两个画像至多一个方向成立` 守着它
- **cutoff（`pt_upgrade_config.target_*`）不是可选优化**：没有终止条件的话，每一集都会永远搜下去把索引器配额烧干。三项全空时 `hasTarget()` 为 false、洗版不激活，这是刻意的安全默认
- **`UPGRADING → IN_LIBRARY` 只能由下载完成驱动，不能交给 Emby 对账**。`SubscriptionService#refresh` 判"在不在库里"靠 Emby 查询，而旧版本本来就在库里、查询恒命中，对账分不出同一集的新旧版本——所以 refresh 刻意跳过 UPGRADING，收尾在 `DownloadTrackService#finishUpgrade`，并在那里同步刷新 `quality` 基线（不刷的话下一轮扫描仍按旧画像判断，会反复洗同一集）
- **洗版失败退回 IN_LIBRARY 而不是 MISSING，且不累加 `fail_count`**。旧文件一直在库里，退成 MISSING 会让这一集显示成缺失并被 RSS 从头重下；累加 fail_count 会让几次洗版失败把一个明明已入库的集熔断成 BLOCKED
- **第一期洗版不碰旧文件**：OSR 从不删种，新旧版本同时存在，清理由用户手动完成。自动清理（第二期）必须先检查旧种子 `hr_state ∈ {SATISFIED, null}`——删掉还在 H&R 考核期内的种子的文件，等于亲手制造一次记过
- **目标质量（本页）与各维度的高低（过滤规则页的三个优先级列表）必须对得上，`UpgradeConfigCheck#problems` 是唯一判据**。出厂数据就是坏的：目标来源 `REMUX,BluRay`、而 `source_priority` 为 NULL——所有来源并列，未达标的集永远找不到「来源更好」的候选，每个周期空搜一次；目标分辨率不在分辨率优先级里时名次等于列表长度，全部集直接判 REACHED。洗版开着时这类问题拒绝保存（两个页面都拦），关着时只在页面上提示；`/overview` 与 `/check` 返回同一份诊断。
- **REACHED 是终态，判定条件一变就必须重置**（`UpgradeConfigAdminService#resetEvaluations`：REACHED/PENDING → PENDING，并清空退避）。触发点两处：本页的目标质量或维度顺序变了、过滤规则页的三个优先级变了。不重置的话把目标从 1080p 提到 2160p，此前达标的集一集都不会再洗，用户只会以为配置没生效。NO_BASELINE 不动，它与判定条件无关。
- **两道预算缺一不可**：`max_concurrent` 限推送数，`max_searches_per_round` 限搜索次数。只有前者时，搜不到更好版本的集不占名额，每轮会把全部 PENDING 集对所有索引器挨个搜一遍。待评估集按 `upgrade_searched_at` 升序轮转（NULL 在前），连续落空按 `UpgradeBackoff` 指数退避（周期 × 2^n，封顶 7 天，判到期时让一小时余量——searched_at 比本轮开始晚几秒，不留余量会白白错过一整个周期）；推送成功清零。搜索名额用完后仍把剩下的集判一遍 REACHED/NO_BASELINE，那两步不发请求。订阅级开关与暂停状态在 SQL 里先筛（`UPGRADABLE_SUB_IDS_SQL`，概览统计同一口径）。
- **手动扫描走 `UpgradeScanTask#triggerNow`，与定时心跳共用同一个 `running` 闸门**，最近一次结果存在 `lastScan` 供页面展示（进程内状态，重启即清空，这是可接受的）。扫描失败过 `FaultThrottle`。
