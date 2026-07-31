-- ----------------------------
-- 20260725: PT 订阅业务表 —— 过滤配置 / 订阅 / 每集状态 / 下载记录
-- 每条语句均为幂等，原因见 20260720-rename-category-rule.sql 头部说明。
-- ----------------------------

CREATE TABLE IF NOT EXISTS `pt_filter_config` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键，本表恒定只有一行(id=1)',
    `min_seeders` int(10) NOT NULL DEFAULT 1 COMMENT '最低做种数，低于此值淘汰',
    `min_size` bigint(20) NOT NULL DEFAULT 0 COMMENT '最小体积(字节)，0表示不限',
    `max_size` bigint(20) NOT NULL DEFAULT 0 COMMENT '最大体积(字节)，0表示不限',
    `free_only` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '是否仅下载免费种 0-否 1-是',
    `include_keywords` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '逗号分隔，标题须命中其一，空表示不限',
    `exclude_keywords` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '逗号分隔，标题命中任一则淘汰',
    `resolution_priority` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '2160p,1080p,720p' COMMENT '分辨率优先级，逗号分隔，越靠前越优先',
    `sort_priority` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'RESOLUTION,FREE,SEEDERS,SIZE' COMMENT '排序维度顺序，逗号分隔',
    `preferred_size` bigint(20) NOT NULL DEFAULT 0 COMMENT '体积接近度的目标值(字节)，0表示该维度不参与比较',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE
) COMMENT = 'PT 全局过滤与排序配置(单行)';

-- 种子数据：显式主键 + INSERT IGNORE 保证幂等，缺了这行引擎读不到配置
INSERT IGNORE INTO `pt_filter_config` (`id`, `min_seeders`, `min_size`, `max_size`, `free_only`, `include_keywords`, `exclude_keywords`, `resolution_priority`, `sort_priority`, `preferred_size`, `create_time`) VALUES
(1, 1, 0, 0, '0', NULL, '预告,花絮,samples', '2160p,1080p,720p', 'RESOLUTION,FREE,SEEDERS,SIZE', 0, '2026-07-25 00:00:00');

CREATE TABLE IF NOT EXISTS `pt_subscription` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `tmdb_id` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'TMDb ID',
    `media_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '媒体类型 TV/MOVIE',
    `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '作品标题',
    `year` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '年份',
    `season` int(10) NOT NULL DEFAULT 0 COMMENT '季号；电影恒为0。用哨兵值而非NULL，否则MySQL唯一索引允许多个NULL会导致同一电影可被重复订阅。与media_type共同保证唯一，不会和剧集的特别篇(季0)冲突',
    `total_episodes` int(10) NOT NULL DEFAULT 1 COMMENT '总集数，电影恒为1',
    `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'ACTIVE' COMMENT '状态 ACTIVE/COMPLETED/PAUSED',
    `filter_override` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '订阅级过滤覆盖(JSON)，空表示全用全局配置',
    `downloader_id` int(10) UNSIGNED NULL DEFAULT NULL COMMENT '指定下载器，空表示用唯一启用的那个',
    `poster_path` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'TMDb海报路径，列表展示用',
    `last_match_time` datetime(0) NULL DEFAULT NULL COMMENT '上次命中种子的时间，用于识别长期空转的订阅',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_tmdb_season`(`tmdb_id`, `media_type`, `season`) USING BTREE,
    INDEX `idx_status`(`status`) USING BTREE
) COMMENT = 'PT 订阅';

CREATE TABLE IF NOT EXISTS `pt_subscription_episode` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `sub_id` int(10) UNSIGNED NOT NULL COMMENT '订阅ID',
    `episode` int(10) NOT NULL COMMENT '集号，电影恒为0',
    `state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'MISSING' COMMENT '状态 MISSING/IN_FLIGHT/IN_LIBRARY',
    `quality` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '已下载质量，为洗版预留',
    `download_id` int(10) UNSIGNED NULL DEFAULT NULL COMMENT '关联的下载记录ID',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_sub_episode`(`sub_id`, `episode`) USING BTREE,
    INDEX `idx_sub_state`(`sub_id`, `state`) USING BTREE
) COMMENT = 'PT 订阅每集状态(缺集的唯一真相来源)';

CREATE TABLE IF NOT EXISTS `pt_download_record` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `sub_id` int(10) UNSIGNED NOT NULL COMMENT '订阅ID',
    `episode` int(10) NOT NULL COMMENT '集号，电影恒为0',
    `indexer_id` int(10) UNSIGNED NOT NULL COMMENT '来源索引器ID',
    `guid` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '索引器给出的条目唯一标识(RSS guid)',
    `guid_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'guid的SHA-256十六进制，用于唯一索引(guid本身过长无法直接建索引)',
    `torrent_hash` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '种子hash，从下载器回填，仅供排查',
    `tracking_tag` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '推送时打的唯一标签 osr-pt-{id}，用于回映下载器中的种子',
    `title` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '原始种子标题',
    `size` bigint(20) NOT NULL DEFAULT 0 COMMENT '体积(字节)',
    `seeders` int(10) NOT NULL DEFAULT 0 COMMENT '做种数',
    `downloader_id` int(10) UNSIGNED NULL DEFAULT NULL COMMENT '推送到的下载器ID',
    `state` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'PUSHED' COMMENT '状态 PUSHED/DOWNLOADING/COMPLETED/FAILED',
    `fail_reason` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '失败原因',
    `pushed_time` datetime(0) NULL DEFAULT NULL COMMENT '推送时间',
    `completed_time` datetime(0) NULL DEFAULT NULL COMMENT '完成时间',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_indexer_guid`(`indexer_id`, `guid_hash`) USING BTREE,
    INDEX `idx_sub_episode`(`sub_id`, `episode`) USING BTREE,
    INDEX `idx_state`(`state`) USING BTREE,
    INDEX `idx_tracking_tag`(`tracking_tag`) USING BTREE
) COMMENT = 'PT 下载记录';

-- ----------------------------
-- 菜单：挂在 OpenListStrm(2006) 下。图标类名必须存在于 useMenuIcon.ts 的映射表中，
-- 否则菜单图标不显示(历史bug，见 commit 0248e124)。本次复用已在表中的 'fa fa-bookmark-o'。
-- visible 先设为 '1'(隐藏)：本脚本仅创建数据表，对应前端页面要到第三阶段才会开发，
-- 现在放出来点进去只会 404。第三阶段前端页面上线后，再用 UPDATE 把这两条菜单的
-- visible 翻回 '0'(显示)。
-- ----------------------------
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2064, 'PT订阅', 2006, 13, '/openlist/ptSubscription', '', 'C', '1', '1', 'openliststrm:ptSubscription:view', 'fa fa-bookmark-o', 'admin', '2026-07-25 00:00:00', '', NULL, 'PT 订阅管理'),
(2065, 'PT过滤规则', 2006, 14, '/openlist/ptFilterConfig', '', 'C', '1', '1', 'openliststrm:ptFilterConfig:view', 'fa fa-sliders', 'admin', '2026-07-25 00:00:00', '', NULL, 'PT 种子过滤与排序规则');
