-- ----------------------------
-- 20260788: 自动补搜的退避计数与落空原因指纹（幂等脚本）
--
-- 1) last_auto_search_no_result: char(1) 的 0/1 改成 int 的「连续落空次数」。
--    原来它只用于通知去重，于是一部片源确实不存在的老剧会永远每 24 小时打满一整轮
--    索引器请求，直到用户手动关掉 auto_search。有了次数就能按次退避
--    （见 AutoSearchService#effectiveIntervalMillis）。列名保留不改：>0 仍然精确等于
--    旧语义的「上一轮落空」，改名要 CHANGE COLUMN 并兼容两套字段名，不值得。
--    旧值 '0'/'1' 由 MySQL 隐式转成 0/1，语义连续。
--
-- 2) 新增 last_auto_search_reject_sign: 上次落空的淘汰原因码指纹。
--    只有次数的话，「压根没搜到候选」变成「候选全被 freeOnly 淘汰」这种处置方向的翻转
--    会被去重逻辑吃掉——那恰恰是用户最需要知道的一次变化。
-- ----------------------------

SET @type := (SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'last_auto_search_no_result');
SET @sql := IF(@type = 'char', 'ALTER TABLE `pt_subscription` MODIFY COLUMN `last_auto_search_no_result` int(10) NOT NULL DEFAULT 0 COMMENT ''定期自动补搜连续落空次数 0-未落空(有命中或未跑过)，>0-连续落空轮数，用于通知去重与按次退避''', 'SELECT ''Column last_auto_search_no_result already migrated''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'last_auto_search_reject_sign');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `last_auto_search_reject_sign` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''上次自动补搜落空的淘汰原因码指纹(排序去重、不含计数)，原因种类变化时允许再通知一次'' AFTER `last_auto_search_no_result`', 'SELECT ''Column last_auto_search_reject_sign already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
