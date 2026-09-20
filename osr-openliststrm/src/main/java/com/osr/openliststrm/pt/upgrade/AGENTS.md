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
