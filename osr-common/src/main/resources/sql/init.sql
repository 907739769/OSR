-- ----------------------------
-- Records of sys_config
-- ----------------------------
INSERT INTO `sys_config` VALUES (100, 'openlist-访问地址', 'openlist.server.url', 'http://xxx:5244', 'N', 'admin', '2025-07-16 11:35:53', 'admin', '2025-07-17 11:39:18', 'openlist的访问地址http://xxxx:5244');
INSERT INTO `sys_config` VALUES (101, 'openlist-api访问token', 'openlist.server.token', 'xxx', 'N', 'admin', '2025-07-16 11:38:39', 'admin', '2025-07-17 11:39:24', 'openlist的api访问token');
INSERT INTO `sys_config` VALUES (102, 'openlist复制的最小文件', 'openlist.copy.minfilesize', '10', 'N', 'admin', '2025-07-17 10:43:53', 'admin', '2025-07-17 11:55:27', '单位是MB');
INSERT INTO `sys_config` VALUES (103, 'openlist复制完文件生成strm', 'openlist.copy.strm', '1', 'N', 'admin', '2025-07-17 10:44:48', '', NULL, NULL);
INSERT INTO `sys_config` VALUES (104, 'tg机器人token', 'openlist.tg.token', '', 'N', 'admin', '2025-07-20 18:42:46', '', NULL, 'tg机器人token');
INSERT INTO `sys_config` VALUES (105, 'tg用户id', 'openlist.tg.userid', '', 'N', 'admin', '2025-07-20 18:43:22', '', NULL, 'tg用户id');
INSERT INTO `sys_config` VALUES (106, 'OSR接口Apikey', 'openlist.api.apikey', '', 'N', 'admin', '2025-07-20 20:01:53', '', NULL, NULL);


-- ----------------------------
-- Records of sys_dict_data
-- ----------------------------
-- 仅保留业务使用的字典：openlist_video_type（视频格式）、openlist_srt_type（字幕格式）
INSERT INTO `sys_dict_data` VALUES (106, 1, 'mp4', 'mp4', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:52:11', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (107, 2, 'mkv', 'mkv', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:52:19', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (108, 3, 'avi', 'avi', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:52:30', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (109, 4, 'mov', 'mov', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:52:42', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (110, 5, 'rmvb', 'rmvb', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:52:52', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (111, 6, 'flv', 'flv', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:53:09', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (112, 7, 'webm', 'webm', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:53:25', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (113, 8, 'm3u8', 'm3u8', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:53:32', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (114, 9, 'wmv', 'wmv', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:53:50', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (115, 10, 'iso', 'iso', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:53:59', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (116, 11, 'ts', 'ts', 'openlist_video_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:54:08', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (117, 1, 'ass', 'ass', 'openlist_srt_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:54:51', '', NULL, NULL);
INSERT INTO `sys_dict_data` VALUES (118, 2, 'srt', 'srt', 'openlist_srt_type', NULL, NULL, 'Y', '0', 'admin', '2025-07-16 13:54:59', '', NULL, NULL);

-- ----------------------------
-- Records of sys_dict_type
-- ----------------------------
-- 仅保留业务使用的字典类型（视频格式、字幕格式），其余未使用的字典已精简
INSERT INTO `sys_dict_type` VALUES (102, 'openlist视频格式', 'openlist_video_type', '0', 'admin', '2025-07-16 13:51:22', '', NULL, NULL);
INSERT INTO `sys_dict_type` VALUES (103, 'openlist字幕格式', 'openlist_srt_type', '0', 'admin', '2025-07-16 13:54:41', '', NULL, NULL);

-- ----------------------------
-- Records of sys_menu
-- ----------------------------
INSERT INTO `sys_menu` VALUES (1, '系统管理', 0, 1, '#', '', 'M', '0', '1', '', 'fa fa-gear', 'admin', '2025-07-15 11:53:20', '', NULL, '系统管理目录');
INSERT INTO `sys_menu` VALUES (2, '系统监控', 0, 2, '#', '', 'M', '0', '1', '', 'fa fa-video-camera', 'admin', '2025-07-15 11:53:21', '', NULL, '系统监控目录');
INSERT INTO `sys_menu` VALUES (105, '字典管理', 1, 6, '/system/dict', '', 'C', '0', '1', 'system:dict:view', 'fa fa-bookmark-o', 'admin', '2025-07-15 11:53:22', '', NULL, '字典管理菜单');
INSERT INTO `sys_menu` VALUES (106, '参数设置', 1, 7, '/system/config', '', 'C', '0', '1', 'system:config:view', 'fa fa-sun-o', 'admin', '2025-07-15 11:53:22', '', NULL, '参数设置菜单');
INSERT INTO `sys_menu` VALUES (110, '定时任务', 2, 2, '/monitor/job', '', 'C', '0', '1', 'monitor:job:view', 'fa fa-tasks', 'admin', '2025-07-15 11:53:23', '', NULL, '定时任务菜单');
INSERT INTO `sys_menu` VALUES (1025, '字典查询', 105, 1, '#', '', 'F', '0', '1', 'system:dict:list', '#', 'admin', '2025-07-15 11:53:29', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1026, '字典新增', 105, 2, '#', '', 'F', '0', '1', 'system:dict:add', '#', 'admin', '2025-07-15 11:53:29', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1027, '字典修改', 105, 3, '#', '', 'F', '0', '1', 'system:dict:edit', '#', 'admin', '2025-07-15 11:53:29', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1028, '字典删除', 105, 4, '#', '', 'F', '0', '1', 'system:dict:remove', '#', 'admin', '2025-07-15 11:53:29', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1029, '字典导出', 105, 5, '#', '', 'F', '0', '1', 'system:dict:export', '#', 'admin', '2025-07-15 11:53:29', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1030, '参数查询', 106, 1, '#', '', 'F', '0', '1', 'system:config:list', '#', 'admin', '2025-07-15 11:53:29', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1031, '参数新增', 106, 2, '#', '', 'F', '0', '1', 'system:config:add', '#', 'admin', '2025-07-15 11:53:30', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1032, '参数修改', 106, 3, '#', '', 'F', '0', '1', 'system:config:edit', '#', 'admin', '2025-07-15 11:53:30', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1033, '参数删除', 106, 4, '#', '', 'F', '0', '1', 'system:config:remove', '#', 'admin', '2025-07-15 11:53:30', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1034, '参数导出', 106, 5, '#', '', 'F', '0', '1', 'system:config:export', '#', 'admin', '2025-07-15 11:53:30', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1050, '任务查询', 110, 1, '#', '', 'F', '0', '1', 'monitor:job:list', '#', 'admin', '2025-07-15 11:53:33', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1051, '任务新增', 110, 2, '#', '', 'F', '0', '1', 'monitor:job:add', '#', 'admin', '2025-07-15 11:53:33', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1052, '任务修改', 110, 3, '#', '', 'F', '0', '1', 'monitor:job:edit', '#', 'admin', '2025-07-15 11:53:33', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1053, '任务删除', 110, 4, '#', '', 'F', '0', '1', 'monitor:job:remove', '#', 'admin', '2025-07-15 11:53:33', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1054, '状态修改', 110, 5, '#', '', 'F', '0', '1', 'monitor:job:changeStatus', '#', 'admin', '2025-07-15 11:53:34', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1055, '任务详细', 110, 6, '#', '', 'F', '0', '1', 'monitor:job:detail', '#', 'admin', '2025-07-15 11:53:34', '', NULL, '');
INSERT INTO `sys_menu` VALUES (1056, '任务导出', 110, 7, '#', '', 'F', '0', '1', 'monitor:job:export', '#', 'admin', '2025-07-15 11:53:34', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2006, 'OpenListStrm', 0, 4, '#', 'menuItem', 'M', '0', '1', NULL, 'fa fa-heart', 'admin', '2025-07-15 13:46:55', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2013, '同步任务记录', 2006, 1, '/openliststrm/copy', 'menuItem', 'C', '0', '1', 'openliststrm:copy:view', '#', 'admin', '2025-07-16 06:17:49', 'admin', '2025-07-17 11:20:55', 'openlist的文件同步复制任务菜单');
INSERT INTO `sys_menu` VALUES (2014, 'openlist的文件同步复制任务查询', 2013, 1, '#', '', 'F', '0', '1', 'openliststrm:copy:list', '#', 'admin', '2025-07-16 06:17:49', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2015, 'openlist的文件同步复制任务新增', 2013, 2, '#', '', 'F', '0', '1', 'openliststrm:copy:add', '#', 'admin', '2025-07-16 06:17:50', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2016, 'openlist的文件同步复制任务修改', 2013, 3, '#', '', 'F', '0', '1', 'openliststrm:copy:edit', '#', 'admin', '2025-07-16 06:17:50', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2017, 'openlist的文件同步复制任务删除', 2013, 4, '#', '', 'F', '0', '1', 'openliststrm:copy:remove', '#', 'admin', '2025-07-16 06:17:50', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2018, 'openlist的文件同步复制任务导出', 2013, 5, '#', '', 'F', '0', '1', 'openliststrm:copy:export', '#', 'admin', '2025-07-16 06:17:51', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2019, 'STRM生成记录', 2006, 1, '/openliststrm/strm', 'menuItem', 'C', '0', '1', 'openliststrm:strm:view', '#', 'admin', '2025-07-16 08:19:31', 'admin', '2025-07-17 11:21:20', 'strm生成菜单');
INSERT INTO `sys_menu` VALUES (2020, 'strm生成查询', 2019, 1, '#', '', 'F', '0', '1', 'openliststrm:strm:list', '#', 'admin', '2025-07-16 08:19:31', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2021, 'strm生成新增', 2019, 2, '#', '', 'F', '0', '1', 'openliststrm:strm:add', '#', 'admin', '2025-07-16 08:19:32', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2022, 'strm生成修改', 2019, 3, '#', '', 'F', '0', '1', 'openliststrm:strm:edit', '#', 'admin', '2025-07-16 08:19:32', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2023, 'strm生成删除', 2019, 4, '#', '', 'F', '0', '1', 'openliststrm:strm:remove', '#', 'admin', '2025-07-16 08:19:32', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2024, 'strm生成导出', 2019, 5, '#', '', 'F', '0', '1', 'openliststrm:strm:export', '#', 'admin', '2025-07-16 08:19:33', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2025, '同步任务配置', 2006, 1, '/openliststrm/task', 'menuItem', 'C', '0', '1', 'openliststrm:task:view', '#', 'admin', '2025-07-17 10:30:19', 'admin', '2025-07-17 11:21:33', '文件同步任务菜单');
INSERT INTO `sys_menu` VALUES (2026, '文件同步任务查询', 2025, 1, '#', '', 'F', '0', '1', 'openliststrm:task:list', '#', 'admin', '2025-07-17 10:30:19', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2027, '文件同步任务新增', 2025, 2, '#', '', 'F', '0', '1', 'openliststrm:task:add', '#', 'admin', '2025-07-17 10:30:20', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2028, '文件同步任务修改', 2025, 3, '#', '', 'F', '0', '1', 'openliststrm:task:edit', '#', 'admin', '2025-07-17 10:30:20', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2029, '文件同步任务删除', 2025, 4, '#', '', 'F', '0', '1', 'openliststrm:task:remove', '#', 'admin', '2025-07-17 10:30:20', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2030, '文件同步任务导出', 2025, 5, '#', '', 'F', '0', '1', 'openliststrm:task:export', '#', 'admin', '2025-07-17 10:30:21', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2037, 'strm任务配置', 2006, 1, '/openliststrm/strm_task', '', 'C', '0', '1', 'openliststrm:strm_task:view', '#', 'admin', '2025-07-18 11:36:14', '', NULL, 'strm任务配置菜单');
INSERT INTO `sys_menu` VALUES (2038, 'strm任务配置查询', 2037, 1, '#', '', 'F', '0', '1', 'openliststrm:strm_task:list', '#', 'admin', '2025-07-18 11:36:14', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2039, 'strm任务配置新增', 2037, 2, '#', '', 'F', '0', '1', 'openliststrm:strm_task:add', '#', 'admin', '2025-07-18 11:36:14', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2040, 'strm任务配置修改', 2037, 3, '#', '', 'F', '0', '1', 'openliststrm:strm_task:edit', '#', 'admin', '2025-07-18 11:36:15', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2041, 'strm任务配置删除', 2037, 4, '#', '', 'F', '0', '1', 'openliststrm:strm_task:remove', '#', 'admin', '2025-07-18 11:36:15', '', NULL, '');
INSERT INTO `sys_menu` VALUES (2042, 'strm任务配置导出', 2037, 5, '#', '', 'F', '0', '1', 'openliststrm:strm_task:export', '#', 'admin', '2025-07-18 11:36:15', '', NULL, '');

-- ----------------------------
-- Records of sys_role
-- ----------------------------
INSERT INTO `sys_role` VALUES (1, '超级管理员', 'admin', 1, '1', '0', '0', 'admin', '2025-07-15 11:53:20', '', NULL, '超级管理员');

-- ----------------------------
-- Records of sys_user
-- ----------------------------
INSERT INTO `sys_user` VALUES (1, 103, 'admin', 'admin', '00', 'admin@163.com', '18888888888', '0', '', '0ecec3cd5cf3ef3e6e5c8b751278c9d9', '3fbab7', '0', '0', '127.0.0.1', '2025-07-19 09:28:11', '2025-07-18 20:27:17', 'admin', '2025-07-15 11:53:18', '', '2025-07-19 01:28:11', '管理员');

-- ----------------------------
-- Records of sys_user_role
-- ----------------------------
INSERT INTO `sys_user_role` VALUES (1, 1);
