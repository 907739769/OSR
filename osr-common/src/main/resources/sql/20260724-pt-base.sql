-- ----------------------------
-- 20260724: PT 订阅功能基座 —— 索引器 / 下载器 / 媒体服务器 三张配置表
-- 每条语句均为幂等（CREATE TABLE IF NOT EXISTS / INSERT IGNORE + 显式主键），
-- 原因见 20260720-rename-category-rule.sql 头部说明。
-- 凭据字段（api_key / password）为明文存储，与现有 sys_config 中
-- openlist.server.token、openlist.tg.token 的存储方式保持一致。
-- ----------------------------

CREATE TABLE IF NOT EXISTS `pt_indexer` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '索引器展示名',
    `url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Torznab 接口地址，形如 http://jackett:9117/api/v2.0/indexers/xxx/results/torznab/api',
    `api_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Torznab apikey',
    `categories` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '逗号分隔的 Torznab 分类，空表示不限',
    `poll_interval` int(10) NOT NULL DEFAULT 600 COMMENT 'RSS 轮询周期（秒）',
    `enabled` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '1' COMMENT '是否启用 0-否 1-是',
    `last_poll_time` datetime(0) NULL DEFAULT NULL COMMENT '上次轮询时间',
    `last_status` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '上次轮询结果，OK 或错误信息',
    `fail_count` int(10) NOT NULL DEFAULT 0 COMMENT '连续失败次数，成功后归零',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_enabled`(`enabled`) USING BTREE
) COMMENT = 'PT Torznab 索引器配置';

CREATE TABLE IF NOT EXISTS `pt_downloader` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '下载器展示名',
    `type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'QBITTORRENT' COMMENT '下载器类型，当前仅 QBITTORRENT',
    `host` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '主机名或IP，不含协议与端口',
    `port` int(10) NOT NULL DEFAULT 8080 COMMENT '端口',
    `use_https` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '是否使用 https 0-否 1-是',
    `username` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '用户名',
    `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '密码，明文存储',
    `save_path` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '种子保存路径，必须位于某个已启用文件同步任务的监听目录之下',
    `tag` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'osr-pt' COMMENT '推送时打的标签，用于轮询过滤',
    `enabled` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '1' COMMENT '是否启用 0-否 1-是',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE
) COMMENT = 'PT 下载器配置';

CREATE TABLE IF NOT EXISTS `pt_media_server` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '媒体服务器展示名',
    `type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT 'EMBY' COMMENT '类型 EMBY / JELLYFIN',
    `url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '服务器地址，形如 http://emby:8096',
    `api_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'API Key，明文存储',
    `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '用户ID，查询剧集时使用，可空',
    `enabled` varchar(1) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '1' COMMENT '是否启用 0-否 1-是',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE
) COMMENT = 'PT 媒体服务器配置（Emby/Jellyfin）';

-- ----------------------------
-- 菜单：挂在 OpenListStrm(2006) 下，显式主键 + INSERT IGNORE 保证幂等
-- 图标类名必须存在于前端图标映射表中，否则不显示（见 commit 0248e124）
-- ----------------------------
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2061, 'PT索引器', 2006, 10, '/openlist/ptIndexer', '', 'C', '0', '1', 'openliststrm:ptIndexer:view', 'fa fa-rss', 'admin', '2026-07-24 00:00:00', '', NULL, 'Torznab 索引器配置'),
(2062, 'PT下载器', 2006, 11, '/openlist/ptDownloader', '', 'C', '0', '1', 'openliststrm:ptDownloader:view', 'fa fa-download', 'admin', '2026-07-24 00:00:00', '', NULL, 'qBittorrent 下载器配置'),
(2063, '媒体服务器', 2006, 12, '/openlist/ptMediaServer', '', 'C', '0', '1', 'openliststrm:ptMediaServer:view', 'fa fa-server', 'admin', '2026-07-24 00:00:00', '', NULL, 'Emby/Jellyfin 配置');
