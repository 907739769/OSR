-- ----------------------------
-- 20260804: 下载记录记下识别到的字幕情况（幂等脚本）
--
-- 字幕体检要回答「已入库的集里，哪些没有中文字幕」。判据来自下载那个种子本身：
-- 标题/描述里的中字标识（与过滤规则「外语片需中字」同一份正则）+ 下载器文件列表里的外挂字幕文件。
-- 下载完成的那一刻文件列表最齐、只拉一次，所以在那时判定并落库；历史记录为 NULL，
-- 体检时按标题现算，页面注明「仅按标题判断」。
--
-- 取值：ZH-识别到中文字幕 OTHER-有字幕文件但认不出语言 NONE-未识别到任何中文字幕标识
-- 注意 NONE 不等于「确定没有」：不少内封中字的种子标题里什么都不写，体检文案要说「未识别到」。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_download_record' AND COLUMN_NAME = 'subtitle');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_download_record` ADD COLUMN `subtitle` VARCHAR(16) DEFAULT NULL COMMENT ''识别到的字幕：ZH/OTHER/NONE，NULL=完成于本列上线之前'' AFTER `completed_time`', 'SELECT ''Column subtitle already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
