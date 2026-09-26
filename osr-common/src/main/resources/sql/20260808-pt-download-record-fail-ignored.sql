-- ----------------------------
-- 20260808: 下载记录的「忽略失败」标记（幂等脚本）
--
-- 首页待办「下载失败待处理」统计的是还没被后续推送接替的失败记录。有些失败用户看过之后决定不管了
-- （不想要这一集了、已从别处补上了），但它永远不会被接替，于是待办一直挂着。
-- 忽略只把它从待办与下载记录页「待处理」筛选里拿掉；统计仪表盘的失败数与成功率照算——
-- 忽略是「不处理」，不是「没失败」。取值 0-未忽略 1-已忽略，只对 FAILED 记录有意义。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'fail_ignored');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `fail_ignored` CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''失败已被用户忽略：0-否 1-是，只对 FAILED 记录有意义'' AFTER `fail_reason_code`', 'SELECT ''Column fail_ignored already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
