-- ----------------------------
-- 20260756: pt_filter_config 新增画质维度相关列
--
-- 此前过滤择优只有 分辨率/免费/做种/体积 四个维度，而 PT 场景里真正决定"这个种要不要"的
-- 媒介来源(Remux/BluRay/WEB-DL)、质量标签(HDR/DV/Atmos/10bit)、发布组偏好全都用不上——
-- 这些信息其实一直被 MediaParser 的 SourceAndGroupExtractor 解析出来了，只是没有配置项承接。
--
-- source_whitelist / required_tags / exclude_tags 是硬性过滤；
-- source_priority / release_group_priority 只影响排序（对应 SortDimension 的 SOURCE / RELEASE_GROUP）。
-- 全部默认留空 = 不限，因此本迁移对既有部署是行为无变化的。
--
-- 幂等 ADD：逐列判断存在与否，写法与 20260744-pt-filter-require-chinese-subtitle.sql 一致
-- （MySQL 的 ALTER TABLE ADD COLUMN 没有 IF NOT EXISTS 语法，直接 ALTER 重跑会因列已存在而报错）。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_filter_config' AND COLUMN_NAME = 'source_whitelist');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_filter_config` ADD COLUMN `source_whitelist` VARCHAR(255) DEFAULT NULL COMMENT ''媒介来源白名单，逗号分隔，空表示不限；硬性过滤'' AFTER `resolution_whitelist`', 'SELECT ''Column source_whitelist already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_filter_config' AND COLUMN_NAME = 'source_priority');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_filter_config` ADD COLUMN `source_priority` VARCHAR(255) DEFAULT NULL COMMENT ''媒介来源优先级，逗号分隔，越靠前越优先，只影响排序'' AFTER `source_whitelist`', 'SELECT ''Column source_priority already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_filter_config' AND COLUMN_NAME = 'required_tags');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_filter_config` ADD COLUMN `required_tags` VARCHAR(255) DEFAULT NULL COMMENT ''必需的质量标签，逗号分隔，须全部具备；空表示不限'' AFTER `source_priority`', 'SELECT ''Column required_tags already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_filter_config' AND COLUMN_NAME = 'exclude_tags');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_filter_config` ADD COLUMN `exclude_tags` VARCHAR(255) DEFAULT NULL COMMENT ''命中任一则淘汰的质量标签，逗号分隔'' AFTER `required_tags`', 'SELECT ''Column exclude_tags already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_filter_config' AND COLUMN_NAME = 'release_group_priority');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_filter_config` ADD COLUMN `release_group_priority` VARCHAR(512) DEFAULT NULL COMMENT ''发布组优先级，逗号分隔，越靠前越优先，只影响排序'' AFTER `exclude_tags`', 'SELECT ''Column release_group_priority already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
