-- ----------------------------
-- 20260770: pt_indexer 增加 last_seen_pub_time 列，把 RSS 覆盖度校验的游标从"条目身份"换成"时间位置"（幂等脚本）
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
--
-- 背景：20260746 引入的 last_seen_guid_hash 游标记录"上一轮首条种子的 guid"，下一轮检查该 guid 是否还在结果里。
-- 这个判据有三处站不住：
--   1. 取列表第 0 条，把"索引器按 pubDate 降序返回"这个假设当成了事实——置顶种子、促销置顶都会让游标记在非最新的条目上；
--   2. 依赖"那条种子下一轮还在"——种子被删除、审核下架、管理员挪分类（而我们带着 cat 过滤）都会让它消失；
--   3. 依赖 guid 逐轮稳定——部分索引器的 guid 带一次性 token，或 guid 缺失时被降级成 downloadUrl。
-- 任一条不成立，游标就永远匹配不上，于是每一轮都报"拉取窗口覆盖不全"，而实际上一条种子都没漏。
--
-- 新判据只依赖时间戳，不依赖条目身份：记录上一轮 max(pubDate)，下一轮检查
-- "本轮窗口下沿 <= 上一轮 max(pubDate)"。窗口下沿取 24 小时内条目的最早发布时间——
-- 剔除置顶/远古条目，且因为 24 小时远大于轮询周期（默认 600 秒），这个剔除不损失任何灵敏度。
--
-- last_seen_guid_hash 保留：pubDate 全部缺失或不可解析的索引器仍走原来的 guid 游标兜底。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_indexer' AND COLUMN_NAME = 'last_seen_pub_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_indexer` ADD COLUMN `last_seen_pub_time` datetime(0) NULL DEFAULT NULL COMMENT ''上一轮拉取到的最新种子发布时间，用于校验下一轮拉取窗口是否覆盖完整；不依赖条目身份，种子被删或 guid 变化都不影响'' AFTER `last_seen_guid_hash`', 'SELECT ''Column last_seen_pub_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
