-- ----------------------------
-- 20260729: pt_subscription 增加 imdb_id 列，用于索引器 ID 精确搜索（幂等脚本）
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'imdb_id');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `imdb_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''IMDb ID(如tt0125664)，建订阅时从TMDb获取，用于索引器ID精确搜索'' AFTER `tmdb_id`', 'SELECT ''Column imdb_id already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
