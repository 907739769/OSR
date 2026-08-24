-- ----------------------------
-- 20260793: 热门自动订阅接入 RSSHub 豆瓣榜单（幂等脚本）
--
-- 背景：pt_auto_add_rule.source 此前只有 TMDB_* 三个取值，而 TMDb 的热门榜以欧美内容为主，
-- 华语与日韩内容的"热"在豆瓣上才反映得出来。新增 RSSHUB_DOUBAN 源，从用户自建的 RSSHub
-- 实例拉豆瓣榜单 RSS（官方实例常年不可用，因此地址必须可自定义）。
--
-- 1) pt_auto_add_rule.source_url: 该规则要拉的 RSS 地址。
--    填路由路径（/douban/movie/weekly/movie_real_time_hotest）时与全局
--    openlist.rsshub.base-url 拼接；填完整 http(s) URL 时直接使用、忽略 base——
--    前者用于"换实例只改一处"，后者用于指向任意 RSS 源。仅 RSSHUB_DOUBAN 生效。
--
-- 2) pt_auto_add_log.source_item_id / source_item_url: 候选条目在来源侧的标识。
--    豆瓣条目要经过"标题 → TMDb 搜索"才拿得到 tmdb_id，这一步失败时（新增的
--    SKIPPED_NO_MATCH 结果）日志里就只剩一个标题，回查不到究竟是哪个豆瓣条目。
--    TMDb 源恒为空（它的 tmdb_id 本身就是来源标识）。
--
-- 3) sys_config 新增 openlist.rsshub.base-url，默认空——留空即未配置，
--    RSSHUB_DOUBAN 规则会直接跳过并 warn，不去猜一个官方地址。
-- ----------------------------

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_auto_add_rule' AND COLUMN_NAME = 'source_url');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_auto_add_rule` ADD COLUMN `source_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''RSS 地址：路由路径则与 openlist.rsshub.base-url 拼接，完整 URL 则直接使用。仅 RSSHUB_DOUBAN 生效'' AFTER `source`', 'SELECT ''Column source_url already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_auto_add_log' AND COLUMN_NAME = 'source_item_id');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_auto_add_log` ADD COLUMN `source_item_id` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''来源侧条目标识（豆瓣 subject id），TMDb 源为空'' AFTER `tmdb_id`', 'SELECT ''Column source_item_id already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @exist := (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'pt_auto_add_log' AND COLUMN_NAME = 'source_item_url');
SET @sql := IF(@exist = 0, 'ALTER TABLE `pt_auto_add_log` ADD COLUMN `source_item_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT ''来源侧条目链接（豆瓣条目页），TMDb 源为空'' AFTER `source_item_id`', 'SELECT ''Column source_item_url already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- source 的注释里补上新取值，纯文档性修改，不改类型与长度
ALTER TABLE `pt_auto_add_rule` MODIFY COLUMN `source` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '数据源：TMDB_TRENDING_DAY/TMDB_TRENDING_WEEK/TMDB_DISCOVER/RSSHUB_DOUBAN';

INSERT INTO `sys_config` (`config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `remark`)
SELECT 'RSSHub 服务地址', 'openlist.rsshub.base-url', '', 'N', 'admin', '2026-08-24 00:00:00',
       '自建 RSSHub 实例地址，如 http://192.168.1.10:1200。留空则"豆瓣热门(RSSHub)"数据源不工作。实例带访问码时可直接把 ?key=xxx 写在地址里'
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `sys_config` WHERE `config_key` = 'openlist.rsshub.base-url');
