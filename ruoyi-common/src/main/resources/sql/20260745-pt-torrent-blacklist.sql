-- ----------------------------
-- 20260745: 新增 PT 种子/发布组手动黑名单功能（建表 + 菜单）
-- 建表 pt_torrent_blacklist，唯一索引 uk_type_value(type, value) 防止同一种子/发布组被
-- 重复拉黑，也让 PtTorrentBlacklistPlusServiceImpl.blockRecordGuid/blockRecordReleaseGroup
-- 的幂等判断有约束兜底。
-- 菜单：PT下载管理(2070) 下新增第 8 项——menu_id=2071/order_num=7 已被
-- 20260741-pt-stats-menu.sql 的"PT统计仪表盘"占用，本次用 menu_id=2072, order_num=8。
-- 页面与接口同批上线，直接 visible='0'(显示)。
-- ----------------------------

CREATE TABLE IF NOT EXISTS `pt_torrent_blacklist` (
    `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '拉黑类型 GUID/RELEASE_GROUP',
    `value` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '匹配键：GUID类型存guid的SHA-256哈希，RELEASE_GROUP类型存归一化(大写)的发布组名',
    `display_value` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '展示用原文，仅供管理页展示，不参与匹配',
    `reason` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '拉黑原因',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE INDEX `uk_type_value`(`type`, `value`) USING BTREE
) COMMENT = 'PT 种子/发布组手动黑名单';

INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2072, 'PT黑名单', 2070, 8, '/openlist/ptTorrentBlacklist', '', 'C', '0', '1', 'openliststrm:ptTorrentBlacklist:view', 'fa fa-ban', 'admin', '2026-07-25 00:00:00', '', NULL, 'PT 种子/发布组手动黑名单管理');
