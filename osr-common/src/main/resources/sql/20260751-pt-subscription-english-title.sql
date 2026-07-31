-- ----------------------------
-- 20260751: pt_subscription 增加 english_title 列（真正的英文标题），修复日剧/韩剧因 original_title
-- 实际是日文/韩文而无法匹配英文种子标题的问题（幂等脚本）。
-- 该表已在真实库存在且可能有数据，用 ALTER 而非重建。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_subscription' AND COLUMN_NAME = 'english_title');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_subscription` ADD COLUMN `english_title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''真正的英文标题（非英文原始语言时来自TMDb alternative_titles的US/GB别名），用于匹配种子标题'' AFTER `original_title`', 'SELECT ''Column english_title already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
