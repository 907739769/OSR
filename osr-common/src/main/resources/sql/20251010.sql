INSERT INTO `sys_dict_type`(`dict_id`, `dict_name`, `dict_type`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (105, '重命名分辨率', 'rename_resolution', '0', 'admin', '2025-09-30 10:57:27', '', NULL, NULL);
INSERT INTO `sys_dict_type`(`dict_id`, `dict_name`, `dict_type`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (106, '重命名状态', 'rename_status', '0', 'admin', '2025-09-30 11:02:53', '', NULL, NULL);
INSERT INTO `sys_dict_type`(`dict_id`, `dict_name`, `dict_type`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (107, '媒体类型', 'media_type', '0', 'admin', '2025-09-30 11:05:54', '', NULL, NULL);

INSERT INTO `sys_dict_data`(`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1, '2160p', '2160p', 'rename_resolution', NULL, NULL, 'Y', '0', 'admin', '2025-09-30 10:57:56', '', NULL, NULL);
INSERT INTO `sys_dict_data`(`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2, '1080p', '1080p', 'rename_resolution', '', '', 'N', '0', 'admin', '2025-09-30 10:58:10', 'admin', '2025-09-30 11:40:01', '');
INSERT INTO `sys_dict_data`(`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (3, '1080i', '1080i', 'rename_resolution', '', '', 'N', '0', 'admin', '2025-09-30 10:58:19', 'admin', '2025-09-30 11:39:56', '');
INSERT INTO `sys_dict_data`(`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (4, '720p', '720p', 'rename_resolution', '', '', 'N', '0', 'admin', '2025-09-30 10:58:29', 'admin', '2025-09-30 11:39:50', '');
INSERT INTO `sys_dict_data`(`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (5, '480p', '480p', 'rename_resolution', '', '', 'N', '0', 'admin', '2025-09-30 10:58:41', 'admin', '2025-09-30 11:39:46', '');
INSERT INTO `sys_dict_data`(`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1, '成功', '1', 'rename_status', '', 'success', 'Y', '0', 'admin', '2025-09-30 11:03:16', 'admin', '2025-09-30 11:03:41', '');
INSERT INTO `sys_dict_data`(`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2, '失败', '0', 'rename_status', '', 'danger', 'N', '0', 'admin', '2025-09-30 11:03:31', 'admin', '2025-09-30 11:39:38', '');
INSERT INTO `sys_dict_data`(`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (1, '电影', 'movie', 'media_type', NULL, NULL, 'Y', '0', 'admin', '2025-09-30 11:06:27', '', NULL, NULL);
INSERT INTO `sys_dict_data`(`dict_sort`, `dict_label`, `dict_value`, `dict_type`, `css_class`, `list_class`, `is_default`, `status`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2, '电视剧', 'tv', 'media_type', '', '', 'N', '0', 'admin', '2025-09-30 11:06:42', 'admin', '2025-09-30 11:39:23', '');

INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2043, '重命名明细', 2006, 6, '/openliststrm/renameDetail', '', 'C', '0', '1', 'openliststrm:renameDetail:view', '#', 'admin', '2025-09-30 11:14:57', '', NULL, '重命名明细菜单');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2044, '重命名明细查询', 2043, 1, '#', '', 'F', '0', '1', 'openliststrm:renameDetail:list', '#', 'admin', '2025-09-30 11:14:58', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2045, '重命名明细新增', 2043, 2, '#', '', 'F', '0', '1', 'openliststrm:renameDetail:add', '#', 'admin', '2025-09-30 11:14:58', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2046, '重命名明细修改', 2043, 3, '#', '', 'F', '0', '1', 'openliststrm:renameDetail:edit', '#', 'admin', '2025-09-30 11:14:58', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2047, '重命名明细删除', 2043, 4, '#', '', 'F', '0', '1', 'openliststrm:renameDetail:remove', '#', 'admin', '2025-09-30 11:14:58', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2048, '重命名明细导出', 2043, 5, '#', '', 'F', '0', '1', 'openliststrm:renameDetail:export', '#', 'admin', '2025-09-30 11:14:58', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2049, '重命名任务配置', 2006, 5, '/openliststrm/renameTask', '', 'C', '0', '1', 'openliststrm:renameTask:view', '#', 'admin', '2025-09-30 16:00:37', '', NULL, '重命名任务配置菜单');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2050, '重命名任务配置查询', 2049, 1, '#', '', 'F', '0', '1', 'openliststrm:renameTask:list', '#', 'admin', '2025-09-30 16:00:37', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2051, '重命名任务配置新增', 2049, 2, '#', '', 'F', '0', '1', 'openliststrm:renameTask:add', '#', 'admin', '2025-09-30 16:00:38', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2052, '重命名任务配置修改', 2049, 3, '#', '', 'F', '0', '1', 'openliststrm:renameTask:edit', '#', 'admin', '2025-09-30 16:00:38', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2053, '重命名任务配置删除', 2049, 4, '#', '', 'F', '0', '1', 'openliststrm:renameTask:remove', '#', 'admin', '2025-09-30 16:00:38', '', NULL, '');
INSERT INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (2054, '重命名任务配置导出', 2049, 5, '#', '', 'F', '0', '1', 'openliststrm:renameTask:export', '#', 'admin', '2025-09-30 16:00:38', '', NULL, '');

CREATE TABLE `rename_task`  (
                                `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                `source_folder` varchar(500) NULL DEFAULT NULL COMMENT '源目录',
                                `target_root` varchar(500) NULL DEFAULT NULL COMMENT '目标目录',
                                `status` varchar(2) NULL DEFAULT NULL COMMENT '状态',
                                `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
                                `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
                                PRIMARY KEY (`id`) USING BTREE
)COMMENT = '重命名任务配置';

CREATE TABLE `rename_detail`  (
                                  `id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
                                  `original_path` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '原文件路径',
                                  `original_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '原文件名称',
                                  `new_path` varchar(1024) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '新文件路径',
                                  `new_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '新文件名称',
                                  `media_type` varchar(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '媒体类型',
                                  `title` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '标题',
                                  `year` varchar(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '年份',
                                  `season` varchar(4) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '季',
                                  `episode` varchar(6) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '集',
                                  `tmdb_id` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT 'tmdbId',
                                  `resolution` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '分辨率',
                                  `video_codec` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '视频编码',
                                  `audio_codec` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '音频编码',
                                  `source` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '来源',
                                  `release_group` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '发布组',
                                  `status` varchar(2) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '状态',
                                  `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
                                  `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
                                  PRIMARY KEY (`id`) USING BTREE,
                                  INDEX `idx_orig_path`(`original_path`(255)) USING BTREE,
                                  INDEX `idx_new_path`(`new_path`(255)) USING BTREE
) COMMENT = '重命名明细';


INSERT INTO `sys_config`(`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (107, 'TMDB接口Apikey', 'openlist.tmdb.apikey', '', 'Y', 'admin', '2025-12-06 09:51:29', 'admin', '2025-12-06 09:52:57', '');
INSERT INTO `sys_config`(`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (108, 'openai接口Apikey', 'openlist.openai.apikey', '', 'Y', 'admin', '2025-12-06 09:52:12', 'admin', '2025-12-06 09:53:07', '');
INSERT INTO `sys_config`(`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (109, 'openai接口地址', 'openlist.openai.endpoint', 'https://api.openai.com', 'Y', 'admin', '2025-12-06 14:53:42', '', NULL, NULL);
INSERT INTO `sys_config`(`config_id`, `config_name`, `config_key`, `config_value`, `config_type`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES (110, 'openai接口模型', 'openlist.openai.model', 'gpt-5-mini', 'Y', 'admin', '2025-12-06 14:54:36', '', NULL, NULL);

UPDATE `sys_menu` SET `order_num` = 3 WHERE `menu_id` = 2037;
UPDATE `sys_menu` SET `order_num` = 4 WHERE `menu_id` = 2019;
UPDATE `sys_menu` SET `order_num` = 2 WHERE `menu_id` = 2013;
