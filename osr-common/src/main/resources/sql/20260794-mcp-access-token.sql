-- ----------------------------
-- 20260794: MCP 访问令牌
--
-- 背景：新增 MCP (Model Context Protocol) 服务端，让本地助理能以工具的形式操作 OSR。
-- 助理是长期后台连接，不能复用登录用的 JWT——那个会过期，而且改密码会让此前签发的
-- 全部失效（见 JwtTokenUtil#isInvalidatedByPasswordChange），用它等于设计上保证助理天天掉线。
--
-- 因此另立一张长期令牌表。三条建模上的取向：
--
-- 1. 明文<不落库>，只存 SHA-256。与 sys_user 的口令同一条取向：库被读走时，
--    令牌不能等价于一把可直接使用的钥匙。明文只在创建接口的响应里出现一次。
--
-- 2. 哈希用 SHA-256 而不是 BCrypt。这不是偷懒：令牌是 32 字节的高熵随机串而不是
--    人选的口令，慢哈希要防的字典攻击在这里不存在；而<每一次> tools/call 都要验一次，
--    BCrypt 会给每次调用平白加几十毫秒。更要紧的是 SHA-256 能直接建唯一索引一次查中，
--    BCrypt 只能把整张令牌表拉出来逐行 matches()——那是随令牌数线性增长的全表扫描。
--
-- 3. owner_user_id 必填。令牌是<以某个人的身份>行动的：PT 订阅的归属隔离
--    （PtSubscriptionRestController#denyIfInaccessible）与 BaseController#isAdmin()
--    全都读当前用户，没有归属人就没法复用它们，只能在 MCP 侧另写一份判定——
--    而那正是本项目反复吃过亏的地方。
-- ----------------------------

CREATE TABLE IF NOT EXISTS `mcp_access_token` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  `name` varchar(64) NOT NULL COMMENT '令牌名称，用于在列表里认出这是发给哪个助理/哪台机器的',
  `token_hash` char(64) NOT NULL COMMENT 'SHA-256(明文令牌) 的十六进制小写；明文不落库',
  `token_prefix` varchar(20) NOT NULL COMMENT '明文的前若干位，仅供列表展示与核对，不足以还原令牌',
  `owner_user_id` bigint NOT NULL COMMENT '令牌以这个用户的身份行动，决定它能看到哪些订阅、是不是管理员',
  `scope` varchar(16) NOT NULL DEFAULT 'read' COMMENT '权限档：read-只读 write-可写 admin-含管理员级操作',
  `enabled` char(1) NOT NULL DEFAULT '1' COMMENT '是否启用 0-否 1-是；停用即刻生效，不需要删除',
  `expire_time` datetime(0) NULL DEFAULT NULL COMMENT '过期时间，NULL 表示长期有效',
  `last_used_time` datetime(0) NULL DEFAULT NULL COMMENT '最后一次成功调用的时间，用于判断这把钥匙还在不在用',
  `remark` varchar(255) NULL DEFAULT NULL COMMENT '备注',
  `create_time` datetime(0) NULL DEFAULT NULL COMMENT '创建时间',
  `update_time` datetime(0) NULL DEFAULT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_mcp_token_hash` (`token_hash`) USING BTREE,
  INDEX `idx_mcp_token_owner` (`owner_user_id`) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = 'MCP 访问令牌' ROW_FORMAT = Dynamic;

-- ----------------------------
-- 菜单：挂在「系统管理」(menu_id=1) 下，排在参数设置(106, order_num=7)之后。
--
-- menu_id=2083：已核对 sql/ 目录下全部涉及 sys_menu 的脚本，当前最大为 2082（缺集体检）。
--
-- 图标直接写 lucide 官方名（见 20260791），中间没有翻译层；但<必须>同时在
-- osr-web/src/plugins/lucideIcons.ts 的 icons 表里登记，否则这个菜单显示一个问号。
-- ----------------------------
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2083, 'MCP 令牌', 1, 8, '/system/mcpToken', '', 'C', '0', '1', 'system:mcpToken:view', 'plug', 'admin', '2026-08-29 00:00:00', '', NULL, '签发/停用供本地助理连接 MCP 服务端的访问令牌');

-- 角色授权按「参数设置」继承一份。
-- 非管理员走 selectMenusByUserId，那条 SQL 是 sys_menu INNER 上 sys_role_menu 的，
-- <每一个菜单项（含挂在已授权分组下的子项）都要有自己的授权行>，只授权父级是不够的——
-- 表现为该用户的侧边栏里这一项凭空消失，而管理员走 selectMenuNormalAll 完全看不出问题。
INSERT IGNORE INTO `sys_role_menu`(`role_id`, `menu_id`)
SELECT `role_id`, 2083 FROM `sys_role_menu` WHERE `menu_id` = 106;
