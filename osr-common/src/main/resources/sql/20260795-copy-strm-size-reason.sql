-- ----------------------------
-- 20260795: 同步记录 / STRM 生成记录补上「文件大小」与「失败原因」
--
-- 背景：两张记录表只有「成功 / 失败」一个状态，失败的原因只写在日志里。页面上一条红色的
-- 「失败」回答不了用户真正要问的事——是 OpenList 报错了、源文件被删种删掉了、体积不到同步
-- 阈值、还是 OpenList 重启把任务弄丢了——四种情况的处置方向完全不同，只能去翻日志。
-- 文件大小则是列目录时 OpenList 本来就返回的字段，一直没存。
--
-- 两列都允许 NULL：存量记录没有这两项数据，也补不回来（要为每条历史记录各打一次 OpenList
-- 请求），页面上显示「-」。新写入的记录才带值。
-- fail_reason 只在失败 / 未知状态下有意义，状态变成处理中或成功时由代码一并清空。
-- ----------------------------

-- 1. openlist_copy.file_size：源文件字节数
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'openlist_copy'
                 AND COLUMN_NAME = 'file_size');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `openlist_copy` ADD COLUMN `file_size` bigint(20) NULL DEFAULT NULL COMMENT ''源文件大小（字节），NULL=存量记录未采集'' AFTER `copy_status`',
    'SELECT ''Column openlist_copy.file_size already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. openlist_copy.fail_reason：失败 / 未知状态的原因
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'openlist_copy'
                 AND COLUMN_NAME = 'fail_reason');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `openlist_copy` ADD COLUMN `fail_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''失败/未知的原因，处理中与成功时为 NULL'' AFTER `file_size`',
    'SELECT ''Column openlist_copy.fail_reason already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. openlist_strm.file_size：网盘源文件字节数（目录级生成时采集；单文件生成拿不到，为 NULL）
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'openlist_strm'
                 AND COLUMN_NAME = 'file_size');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `openlist_strm` ADD COLUMN `file_size` bigint(20) NULL DEFAULT NULL COMMENT ''网盘源文件大小（字节），NULL=未采集'' AFTER `strm_status`',
    'SELECT ''Column openlist_strm.file_size already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 4. openlist_strm.fail_reason：生成失败的原因
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME = 'openlist_strm'
                 AND COLUMN_NAME = 'fail_reason');
SET @sql := IF(@exist = 0,
    'ALTER TABLE `openlist_strm` ADD COLUMN `fail_reason` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''生成失败的原因，成功时为 NULL'' AFTER `file_size`',
    'SELECT ''Column openlist_strm.fail_reason already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
