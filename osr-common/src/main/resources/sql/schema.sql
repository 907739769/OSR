-- ----------------------------
-- Table structure for sys_config
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_config`  (
                                             `config_id` int(5) NOT NULL AUTO_INCREMENT COMMENT '参数主键',
    `config_name` varchar(100)  NULL DEFAULT '' COMMENT '参数名称',
    `config_key` varchar(100)  NULL DEFAULT '' COMMENT '参数键名',
    `config_value` varchar(500)  NULL DEFAULT '' COMMENT '参数键值',
    `config_type` char(1)  NULL DEFAULT 'N' COMMENT '系统内置（Y是 N否）',
    `create_by` varchar(64)  NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64)  NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    `remark` varchar(500)  NULL DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`config_id`) USING BTREE
    ) AUTO_INCREMENT = 1000 COMMENT = '参数配置表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_dept
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_dept`  (
                                           `dept_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '部门id',
    `parent_id` bigint(20) NULL DEFAULT 0 COMMENT '父部门id',
    `ancestors` varchar(50)  NULL DEFAULT '' COMMENT '祖级列表',
    `dept_name` varchar(30)  NULL DEFAULT '' COMMENT '部门名称',
    `order_num` int(4) NULL DEFAULT 0 COMMENT '显示顺序',
    `leader` varchar(20)  NULL DEFAULT NULL COMMENT '负责人',
    `phone` varchar(11)  NULL DEFAULT NULL COMMENT '联系电话',
    `email` varchar(50)  NULL DEFAULT NULL COMMENT '邮箱',
    `status` char(1)  NULL DEFAULT '0' COMMENT '部门状态（0正常 1停用）',
    `del_flag` char(1)  NULL DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
    `create_by` varchar(64)  NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64)  NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`dept_id`) USING BTREE
    ) AUTO_INCREMENT = 200 COMMENT = '部门表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_dict_data
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_dict_data`  (
                                                `dict_code` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '字典编码',
    `dict_sort` int(4) NULL DEFAULT 0 COMMENT '字典排序',
    `dict_label` varchar(100)  NULL DEFAULT '' COMMENT '字典标签',
    `dict_value` varchar(100)  NULL DEFAULT '' COMMENT '字典键值',
    `dict_type` varchar(100)  NULL DEFAULT '' COMMENT '字典类型',
    `css_class` varchar(100)  NULL DEFAULT NULL COMMENT '样式属性（其他样式扩展）',
    `list_class` varchar(100)  NULL DEFAULT NULL COMMENT '表格回显样式',
    `is_default` char(1)  NULL DEFAULT 'N' COMMENT '是否默认（Y是 N否）',
    `status` char(1)  NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
    `create_by` varchar(64)  NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64)  NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    `remark` varchar(500)  NULL DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`dict_code`) USING BTREE
    ) AUTO_INCREMENT = 1000 COMMENT = '字典数据表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_dict_type
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_dict_type`  (
                                                `dict_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '字典主键',
    `dict_name` varchar(100)  NULL DEFAULT '' COMMENT '字典名称',
    `dict_type` varchar(100)  NULL DEFAULT '' COMMENT '字典类型',
    `status` char(1)  NULL DEFAULT '0' COMMENT '状态（0正常 1停用）',
    `create_by` varchar(64)  NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64)  NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    `remark` varchar(500)  NULL DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`dict_id`) USING BTREE,
    UNIQUE INDEX `dict_type`(`dict_type`) USING BTREE
    ) AUTO_INCREMENT = 1000 COMMENT = '字典类型表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_job
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_job`  (
                                          `job_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `job_name` varchar(64)  NOT NULL DEFAULT '' COMMENT '任务名称',
    `job_group` varchar(64)  NOT NULL DEFAULT 'DEFAULT' COMMENT '任务组名',
    `invoke_target` varchar(500)  NOT NULL COMMENT '调用目标字符串',
    `cron_expression` varchar(255)  NULL DEFAULT '' COMMENT 'cron执行表达式',
    `misfire_policy` varchar(20)  NULL DEFAULT '3' COMMENT '计划执行错误策略（1立即执行 2执行一次 3放弃执行）',
    `concurrent` char(1)  NULL DEFAULT '1' COMMENT '是否并发执行（0允许 1禁止）',
    `status` char(1)  NULL DEFAULT '0' COMMENT '状态（0正常 1暂停）',
    `create_by` varchar(64)  NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64)  NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    `remark` varchar(500)  NULL DEFAULT '' COMMENT '备注信息',
    PRIMARY KEY (`job_id`, `job_name`, `job_group`) USING BTREE
    ) AUTO_INCREMENT = 100 COMMENT = '定时任务调度表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_job_log
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_job_log`  (
                                              `job_log_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '任务日志ID',
    `job_name` varchar(64)  NOT NULL COMMENT '任务名称',
    `job_group` varchar(64)  NOT NULL COMMENT '任务组名',
    `invoke_target` varchar(500)  NOT NULL COMMENT '调用目标字符串',
    `job_message` varchar(500)  NULL DEFAULT NULL COMMENT '日志信息',
    `status` char(1)  NULL DEFAULT '0' COMMENT '执行状态（0正常 1失败）',
    `exception_info` varchar(2000)  NULL DEFAULT '' COMMENT '异常信息',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (`job_log_id`) USING BTREE
    ) COMMENT = '定时任务调度日志表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_logininfor
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_logininfor`  (
                                                 `info_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '访问ID',
    `login_name` varchar(50)  NULL DEFAULT '' COMMENT '登录账号',
    `ipaddr` varchar(128)  NULL DEFAULT '' COMMENT '登录IP地址',
    `login_location` varchar(255)  NULL DEFAULT '' COMMENT '登录地点',
    `browser` varchar(50)  NULL DEFAULT '' COMMENT '浏览器类型',
    `os` varchar(50)  NULL DEFAULT '' COMMENT '操作系统',
    `status` char(1)  NULL DEFAULT '0' COMMENT '登录状态（0成功 1失败）',
    `msg` varchar(255)  NULL DEFAULT '' COMMENT '提示消息',
    `login_time` datetime(0) NULL DEFAULT NULL COMMENT '访问时间',
    PRIMARY KEY (`info_id`) USING BTREE,
    INDEX `idx_sys_logininfor_s`(`status`) USING BTREE,
    INDEX `idx_sys_logininfor_lt`(`login_time`) USING BTREE
    ) COMMENT = '系统访问记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_menu
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_menu`  (
                                           `menu_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
    `menu_name` varchar(50)  NOT NULL COMMENT '菜单名称',
    `parent_id` bigint(20) NULL DEFAULT 0 COMMENT '父菜单ID',
    `order_num` int(4) NULL DEFAULT 0 COMMENT '显示顺序',
    `url` varchar(200)  NULL DEFAULT '#' COMMENT '请求地址',
    `target` varchar(20)  NULL DEFAULT '' COMMENT '打开方式（menuItem页签 menuBlank新窗口）',
    `menu_type` char(1)  NULL DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
    `visible` char(1)  NULL DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
    `is_refresh` char(1)  NULL DEFAULT '1' COMMENT '是否刷新（0刷新 1不刷新）',
    `perms` varchar(100)  NULL DEFAULT NULL COMMENT '权限标识',
    `icon` varchar(100)  NULL DEFAULT '#' COMMENT '菜单图标',
    `create_by` varchar(64)  NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64)  NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    `remark` varchar(500)  NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (`menu_id`) USING BTREE
    ) AUTO_INCREMENT = 5000 COMMENT = '菜单权限表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_notice
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_notice`  (
                                             `notice_id` int(4) NOT NULL AUTO_INCREMENT COMMENT '公告ID',
    `notice_title` varchar(50)  NOT NULL COMMENT '公告标题',
    `notice_type` char(1)  NOT NULL COMMENT '公告类型（1通知 2公告）',
    `notice_content` longblob NULL COMMENT '公告内容',
    `status` char(1)  NULL DEFAULT '0' COMMENT '公告状态（0正常 1关闭）',
    `create_by` varchar(64)  NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64)  NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    `remark` varchar(255)  NULL DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`notice_id`) USING BTREE
    ) AUTO_INCREMENT = 10 COMMENT = '通知公告表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_oper_log
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_oper_log`  (
                                               `oper_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '日志主键',
    `title` varchar(50)  NULL DEFAULT '' COMMENT '模块标题',
    `business_type` int(2) NULL DEFAULT 0 COMMENT '业务类型（0其它 1新增 2修改 3删除）',
    `method` varchar(200)  NULL DEFAULT '' COMMENT '方法名称',
    `request_method` varchar(10)  NULL DEFAULT '' COMMENT '请求方式',
    `operator_type` int(1) NULL DEFAULT 0 COMMENT '操作类别（0其它 1后台用户 2手机端用户）',
    `oper_name` varchar(50)  NULL DEFAULT '' COMMENT '操作人员',
    `dept_name` varchar(50)  NULL DEFAULT '' COMMENT '部门名称',
    `oper_url` varchar(255)  NULL DEFAULT '' COMMENT '请求URL',
    `oper_ip` varchar(128)  NULL DEFAULT '' COMMENT '主机地址',
    `oper_location` varchar(255)  NULL DEFAULT '' COMMENT '操作地点',
    `oper_param` varchar(2000)  NULL DEFAULT '' COMMENT '请求参数',
    `json_result` varchar(2000)  NULL DEFAULT '' COMMENT '返回参数',
    `status` int(1) NULL DEFAULT 0 COMMENT '操作状态（0正常 1异常）',
    `error_msg` varchar(2000)  NULL DEFAULT '' COMMENT '错误消息',
    `oper_time` datetime(0) NULL DEFAULT NULL COMMENT '操作时间',
    `cost_time` bigint(20) NULL DEFAULT 0 COMMENT '消耗时间',
    PRIMARY KEY (`oper_id`) USING BTREE,
    INDEX `idx_sys_oper_log_bt`(`business_type`) USING BTREE,
    INDEX `idx_sys_oper_log_s`(`status`) USING BTREE,
    INDEX `idx_sys_oper_log_ot`(`oper_time`) USING BTREE
    ) COMMENT = '操作日志记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_post
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_post`  (
                                           `post_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '岗位ID',
    `post_code` varchar(64)  NOT NULL COMMENT '岗位编码',
    `post_name` varchar(50)  NOT NULL COMMENT '岗位名称',
    `post_sort` int(4) NOT NULL COMMENT '显示顺序',
    `status` char(1)  NOT NULL COMMENT '状态（0正常 1停用）',
    `create_by` varchar(64)  NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64)  NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    `remark` varchar(500)  NULL DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`post_id`) USING BTREE
    ) AUTO_INCREMENT = 5 COMMENT = '岗位信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_role
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_role`  (
                                           `role_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    `role_name` varchar(30)  NOT NULL COMMENT '角色名称',
    `role_key` varchar(100)  NOT NULL COMMENT '角色权限字符串',
    `role_sort` int(4) NOT NULL COMMENT '显示顺序',
    `data_scope` char(1)  NULL DEFAULT '1' COMMENT '数据范围（1：全部数据权限 2：自定数据权限 3：本部门数据权限 4：本部门及以下数据权限）',
    `status` char(1)  NOT NULL COMMENT '角色状态（0正常 1停用）',
    `del_flag` char(1)  NULL DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
    `create_by` varchar(64)  NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64)  NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    `remark` varchar(500)  NULL DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`role_id`) USING BTREE
    ) AUTO_INCREMENT = 100 COMMENT = '角色信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_role_dept
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_role_dept`  (
                                                `role_id` bigint(20) NOT NULL COMMENT '角色ID',
    `dept_id` bigint(20) NOT NULL COMMENT '部门ID',
    PRIMARY KEY (`role_id`, `dept_id`) USING BTREE
    )  COMMENT = '角色和部门关联表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_role_menu
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_role_menu`  (
                                                `role_id` bigint(20) NOT NULL COMMENT '角色ID',
    `menu_id` bigint(20) NOT NULL COMMENT '菜单ID',
    PRIMARY KEY (`role_id`, `menu_id`) USING BTREE
    )  COMMENT = '角色和菜单关联表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_user`  (
                                           `user_id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    `dept_id` bigint(20) NULL DEFAULT NULL COMMENT '部门ID',
    `login_name` varchar(30)  NOT NULL COMMENT '登录账号',
    `user_name` varchar(30)  NULL DEFAULT '' COMMENT '用户昵称',
    `user_type` varchar(2)  NULL DEFAULT '00' COMMENT '用户类型（00系统用户 01注册用户）',
    `email` varchar(50)  NULL DEFAULT '' COMMENT '用户邮箱',
    `phonenumber` varchar(11)  NULL DEFAULT '' COMMENT '手机号码',
    `sex` char(1)  NULL DEFAULT '0' COMMENT '用户性别（0男 1女 2未知）',
    `avatar` varchar(100)  NULL DEFAULT '' COMMENT '头像路径',
    `password` varchar(50)  NULL DEFAULT '' COMMENT '密码',
    `salt` varchar(20)  NULL DEFAULT '' COMMENT '盐加密',
    `status` char(1)  NULL DEFAULT '0' COMMENT '账号状态（0正常 1停用）',
    `del_flag` char(1)  NULL DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
    `login_ip` varchar(128)  NULL DEFAULT '' COMMENT '最后登录IP',
    `login_date` datetime(0) NULL DEFAULT NULL COMMENT '最后登录时间',
    `pwd_update_date` datetime(0) NULL DEFAULT NULL COMMENT '密码最后更新时间',
    `create_by` varchar(64)  NULL DEFAULT '' COMMENT '创建者',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_by` varchar(64)  NULL DEFAULT '' COMMENT '更新者',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    `remark` varchar(500)  NULL DEFAULT NULL COMMENT '备注',
    PRIMARY KEY (`user_id`) USING BTREE
    ) AUTO_INCREMENT = 100 COMMENT = '用户信息表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_user_online
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_user_online`  (
                                                  `sessionId` varchar(50)  NOT NULL DEFAULT '' COMMENT '用户会话id',
    `login_name` varchar(50)  NULL DEFAULT '' COMMENT '登录账号',
    `dept_name` varchar(50)  NULL DEFAULT '' COMMENT '部门名称',
    `ipaddr` varchar(128)  NULL DEFAULT '' COMMENT '登录IP地址',
    `login_location` varchar(255)  NULL DEFAULT '' COMMENT '登录地点',
    `browser` varchar(50)  NULL DEFAULT '' COMMENT '浏览器类型',
    `os` varchar(50)  NULL DEFAULT '' COMMENT '操作系统',
    `status` varchar(10)  NULL DEFAULT '' COMMENT '在线状态on_line在线off_line离线',
    `start_timestamp` datetime(0) NULL DEFAULT NULL COMMENT 'session创建时间',
    `last_access_time` datetime(0) NULL DEFAULT NULL COMMENT 'session最后访问时间',
    `expire_time` int(5) NULL DEFAULT 0 COMMENT '超时时间，单位为分钟',
    PRIMARY KEY (`sessionId`) USING BTREE
    )  COMMENT = '在线用户记录' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_user_post
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_user_post`  (
                                                `user_id` bigint(20) NOT NULL COMMENT '用户ID',
    `post_id` bigint(20) NOT NULL COMMENT '岗位ID',
    PRIMARY KEY (`user_id`, `post_id`) USING BTREE
    )  COMMENT = '用户与岗位关联表' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for sys_user_role
-- ----------------------------
CREATE TABLE IF NOT EXISTS `sys_user_role`  (
                                                `user_id` bigint(20) NOT NULL COMMENT '用户ID',
    `role_id` bigint(20) NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`user_id`, `role_id`) USING BTREE
    )  COMMENT = '用户和角色关联表' ROW_FORMAT = Dynamic;
-- ----------------------------
-- Table structure for openlist_copy
-- ----------------------------
CREATE TABLE IF NOT EXISTS `openlist_copy`  (
                                                `copy_id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `copy_src_path` varchar(4096)  NULL DEFAULT NULL COMMENT '源目录',
    `copy_dst_path` varchar(4096)  NULL DEFAULT NULL COMMENT '目标目录',
    `copy_src_file_name` varchar(1024)  NULL DEFAULT NULL COMMENT '源文件名称',
    `copy_dst_file_name` varchar(1024)  NULL DEFAULT NULL COMMENT '目标文件名称',
    `copy_task_id` varchar(64)  NULL DEFAULT NULL COMMENT 'openlist的复制任务ID',
    `copy_status` varchar(2)  NULL DEFAULT NULL COMMENT '复制状态1-处理中2-失败3-成功4-未知',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`copy_id`) USING BTREE
    )  COMMENT = '文件同步' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for openlist_copy_task
-- ----------------------------
CREATE TABLE IF NOT EXISTS `openlist_copy_task`  (
                                                     `copy_task_id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `copy_task_src` varchar(4096)  NULL DEFAULT NULL COMMENT '源目录',
    `copy_task_dst` varchar(4096)  NULL DEFAULT NULL COMMENT '目标目录',
    `copy_task_status` varchar(2)  NULL DEFAULT '1' COMMENT '状态0-停用1-启用',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`copy_task_id`) USING BTREE
    ) COMMENT = '文件同步任务' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for openlist_strm
-- ----------------------------
CREATE TABLE IF NOT EXISTS `openlist_strm`  (
                                                `strm_id` int(10) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `strm_path` varchar(4096)  NULL DEFAULT NULL COMMENT 'strm目录',
    `strm_file_name` varchar(1024)  NULL DEFAULT NULL COMMENT 'strm文件名称',
    `strm_status` varchar(2)  NULL DEFAULT NULL COMMENT '状态0-失败1-成功',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`strm_id`) USING BTREE
    ) COMMENT = 'strm生成' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for openlist_strm_task
-- ----------------------------
CREATE TABLE IF NOT EXISTS `openlist_strm_task`  (
                                                     `strm_task_id` int(11) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    `strm_task_path` varchar(255)  NULL DEFAULT NULL COMMENT 'strm目录',
    `strm_task_status` varchar(2)  NULL DEFAULT '1' COMMENT '状态0-停用1-启用',
    `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
    `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`strm_task_id`) USING BTREE
    ) COMMENT = 'strm任务配置' ROW_FORMAT = Dynamic;
