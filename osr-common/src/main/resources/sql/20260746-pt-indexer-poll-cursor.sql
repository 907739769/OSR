-- ----------------------------
-- 20260746: pt_indexer 增加 last_seen_guid_hash 列，用于 RSS 轮询增量覆盖度校验（幂等脚本）
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
-- 背景：Torznab RSS 拉取无游标/时间参数支持，每轮只能拿到索引器当前首页种子；
-- 若发布速度超过 (首页容量)/(轮询间隔)，两轮之间被挤出首页的种子会被永久跳过且无法察觉。
-- 记录"上一轮最新一条种子的 guid 哈希"，下一轮拉取后校验该 guid 是否仍在本轮结果窗口内，
-- 不在则说明存在覆盖不到的漏拉窗口，用于触发告警（RssPollService 侧实现）。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_indexer' AND COLUMN_NAME = 'last_seen_guid_hash');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_indexer` ADD COLUMN `last_seen_guid_hash` varchar(64) NULL DEFAULT NULL COMMENT ''上一轮拉取到的最新种子 guid 的 SHA-256 哈希，用于校验下一轮拉取窗口是否覆盖完整'' AFTER `disabled_at`', 'SELECT ''Column last_seen_guid_hash already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
