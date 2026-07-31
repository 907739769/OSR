-- ----------------------------
-- 20260719: 重命名 STRM 一致性检查（孤儿清理）功能
-- ----------------------------

CREATE TABLE `rename_orphan`  (
                                 `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                 `detail_id` int(10) UNSIGNED NOT NULL COMMENT '关联rename_detail.id',
                                 `new_path` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '重命名后目录',
                                 `new_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '重命名后文件名',
                                 `title` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '标题',
                                 `year` varchar(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '年份',
                                 `media_type` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '媒体类型',
                                 `reason` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '孤儿原因 local_missing-本地文件已删除 source_missing-网盘源已删除',
                                 `status` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL DEFAULT '0' COMMENT '状态 0-待处理 1-已清理 2-已忽略',
                                 `found_time` datetime(0) NULL DEFAULT NULL COMMENT '发现时间',
                                 `clean_time` datetime(0) NULL DEFAULT NULL COMMENT '清理/忽略时间',
                                 `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
                                 `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
                                 PRIMARY KEY (`id`) USING BTREE,
                                 UNIQUE INDEX `uk_detail_id`(`detail_id`) USING BTREE,
                                 INDEX `idx_status`(`status`) USING BTREE
) COMMENT = '重命名孤儿记录（一致性检查待清理项）';

-- ----------------------------
-- 定时任务：每天一次扫描，避开已有 copy(3点)/rename(2点)/strm(5点) 任务
-- ----------------------------
INSERT IGNORE INTO `sys_job` VALUES (103, 'openliststrm-重命名一致性检查', 'DEFAULT', 'openListStrmTask.checkRenameOrphan()', '0 0 6 * * ?', '3', '1', '1', 'admin', '2026-07-19 00:00:00', '', NULL, '');

-- ----------------------------
-- 菜单：挂在 OpenListStrm(2006) 下，view + list/scan/clean/ignore 四个按钮权限
-- ----------------------------
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2055, '重命名一致性检查', 2006, 7, '/openlist/renameOrphan', '', 'C', '0', '1', 'openliststrm:renameOrphan:view', '#', 'admin', '2026-07-19 00:00:00', '', NULL, '重命名STRM一致性检查菜单');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2056, '重命名一致性检查查询', 2055, 1, '#', '', 'F', '0', '1', 'openliststrm:renameOrphan:list', '#', 'admin', '2026-07-19 00:00:00', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2057, '重命名一致性检查扫描', 2055, 2, '#', '', 'F', '0', '1', 'openliststrm:renameOrphan:scan', '#', 'admin', '2026-07-19 00:00:00', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2058, '重命名一致性检查清理', 2055, 3, '#', '', 'F', '0', '1', 'openliststrm:renameOrphan:clean', '#', 'admin', '2026-07-19 00:00:00', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2059, '重命名一致性检查忽略', 2055, 4, '#', '', 'F', '0', '1', 'openliststrm:renameOrphan:ignore', '#', 'admin', '2026-07-19 00:00:00', '', NULL, '');
