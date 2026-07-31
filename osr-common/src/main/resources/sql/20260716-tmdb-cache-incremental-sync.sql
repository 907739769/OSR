-- ----------------------------
-- 20260716: TMDb API 缓存表 + 增量同步支持（幂等脚本）
-- ----------------------------

-- tmdb_cache: TMDb API 响应持久化缓存
CREATE TABLE IF NOT EXISTS `tmdb_cache` (
    `id`            bigint(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `cache_key`     varchar(64)  NOT NULL COMMENT '请求URL摘要（MD5）',
    `cache_type`    varchar(32)  NOT NULL COMMENT '缓存类型：search/getDetails/getSeasonEpisodes等',
    `response_data` longtext     NULL COMMENT '缓存的JSON响应文本',
    `expire_time`   datetime(0)  NOT NULL COMMENT '过期时间',
    `create_time`   datetime(0)  NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_cache_key` (`cache_key`, `cache_type`) USING BTREE,
    INDEX `idx_tmdb_cache_expire` (`expire_time`) USING BTREE
) COMMENT = 'TMDb API 响应缓存' ROW_FORMAT = Dynamic;

-- openlist_copy_task: 条件添加增量同步时间戳字段
SET @exist := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'openlist_copy_task' AND COLUMN_NAME = 'last_sync_time');
SET @sql := IF(@exist = 0, 'ALTER TABLE `openlist_copy_task` ADD COLUMN `last_sync_time` datetime(0) NULL DEFAULT NULL COMMENT ''上次同步完成时间（增量同步基准）'' AFTER `copy_task_status`', 'SELECT ''Column last_sync_time already exists''');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
