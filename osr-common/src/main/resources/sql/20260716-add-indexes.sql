-- ----------------------------
-- 20260716: 添加复合索引优化查询性能（幂等脚本）
-- ----------------------------

-- openlist_strm: 按目录+文件名查询优化
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'openlist_strm' AND INDEX_NAME = 'idx_strm_path_name');
SET @sql := IF(@exist = 0, 'ALTER TABLE `openlist_strm` ADD INDEX `idx_strm_path_name`(`strm_path`(255), `strm_file_name`(255))', 'SELECT ''Index idx_strm_path_name already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- openlist_copy: 按源目录+源文件名查询优化
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'openlist_copy' AND INDEX_NAME = 'idx_copy_src_path_name');
SET @sql := IF(@exist = 0, 'ALTER TABLE `openlist_copy` ADD INDEX `idx_copy_src_path_name`(`copy_src_path`(255), `copy_src_file_name`(255))', 'SELECT ''Index idx_copy_src_path_name already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- openlist_copy: 按目标目录+目标文件名查询优化
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'openlist_copy' AND INDEX_NAME = 'idx_copy_dst_path_name');
SET @sql := IF(@exist = 0, 'ALTER TABLE `openlist_copy` ADD INDEX `idx_copy_dst_path_name`(`copy_dst_path`(255), `copy_dst_file_name`(255))', 'SELECT ''Index idx_copy_dst_path_name already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- rename_detail: 按原始路径+原始文件名查询优化
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rename_detail' AND INDEX_NAME = 'idx_rename_original');
SET @sql := IF(@exist = 0, 'ALTER TABLE `rename_detail` ADD INDEX `idx_rename_original`(`original_path`(255), `original_name`(255))', 'SELECT ''Index idx_rename_original already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
