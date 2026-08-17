-- ----------------------------
-- 20260785: PT 菜单由 1 组 12 项拆成 4 组 × 3 项，仍是两级菜单
--
-- 现状：顶层「PT订阅下载」(2070) 下平铺挂着 12 个功能菜单，找一个入口要在一长串
-- 同前缀的名字里扫读。本脚本把它们按「我要干什么」分成四组，四个分组都直接挂在
-- 根节点(parent_id=0)，与「网盘同步」「STRM生成」「智能重命名」同级——**不引入
-- 第三级**：20260752 刚把三级收敛成两级，桌面端侧边栏(SidebarMenuItem.vue)也是
-- 把分组渲染成一行标题、子项平铺，再套一层只会多出一行没人点的标题。
--
--   PT 追剧(2070) : 追剧日历 / 订阅管理 / 热门自动订阅
--   PT 下载(2079) : 下载记录 / 统计仪表盘 / 转移做种
--   PT 规则(2080) : 过滤规则 / 洗版规则 / 黑名单
--   PT 接入(2081) : 索引器 / 下载器 / 媒体服务器
--
-- 顺带去掉功能菜单名里的「PT」前缀：分组标题已经带 PT，每一项再写一遍只是把有效
-- 信息往右挤（侧边栏宽 220px）。**只改 menu_name**，url/perms/menu_id 一律不动，
-- 因此不影响路由、权限标识与 sys_role_menu 授权。
--
-- menu_id=2079/2080/2081：已核对 sql/ 目录下全部涉及 sys_menu 的脚本，当前最大为
-- 2078，三个 id 均未被占用。
-- 图标直接写 mdi 名（见 20260780），不需要再去 useMenuIcon.ts 登记。
--
-- 幂等性：INSERT IGNORE + 显式主键，UPDATE 天然幂等；配合 SimpleDdl「整文件成功
-- 才记入 ddl_history」不会有部分执行风险。
-- ----------------------------

-- 1. 新增三个分组（PT 追剧沿用 2070，只改名字/图标）
INSERT IGNORE INTO `sys_menu`(`menu_id`, `menu_name`, `parent_id`, `order_num`, `url`, `target`, `menu_type`, `visible`, `is_refresh`, `perms`, `icon`, `create_by`, `create_time`, `update_by`, `update_time`, `remark`) VALUES
(2079, 'PT 下载', 0, 8, '#', '', 'M', '0', '1', NULL, 'mdi-download-network-outline', 'admin', '2026-08-17 00:00:00', '', NULL, 'PT 下载过程与结果：下载记录、统计、转移做种'),
(2080, 'PT 规则', 0, 9, '#', '', 'M', '0', '1', NULL, 'mdi-tune-variant', 'admin', '2026-08-17 00:00:00', '', NULL, 'PT 选种策略：过滤、洗版、黑名单'),
(2081, 'PT 接入', 0, 10, '#', '', 'M', '0', '1', NULL, 'mdi-server-network', 'admin', '2026-08-17 00:00:00', '', NULL, 'PT 外部服务接入：索引器、下载器、媒体服务器');

UPDATE `sys_menu` SET `menu_name` = 'PT 追剧', `parent_id` = 0, `order_num` = 7, `icon` = 'mdi-television-play' WHERE `menu_id` = 2070;

-- 2. 新分组继承「PT订阅下载」原有的角色授权。
--    非管理员用户走 selectMenusByUserId，父级 M 菜单没授权的话子菜单整组不显示；
--    管理员(user_id=1)走 selectMenuNormalAll，不受影响。
INSERT IGNORE INTO `sys_role_menu`(`role_id`, `menu_id`) SELECT `role_id`, 2079 FROM `sys_role_menu` WHERE `menu_id` = 2070;
INSERT IGNORE INTO `sys_role_menu`(`role_id`, `menu_id`) SELECT `role_id`, 2080 FROM `sys_role_menu` WHERE `menu_id` = 2070;
INSERT IGNORE INTO `sys_role_menu`(`role_id`, `menu_id`) SELECT `role_id`, 2081 FROM `sys_role_menu` WHERE `menu_id` = 2070;

-- 3. PT 追剧(2070)
UPDATE `sys_menu` SET `parent_id` = 2070, `order_num` = 1, `menu_name` = '追剧日历'     WHERE `menu_id` = 2076;
UPDATE `sys_menu` SET `parent_id` = 2070, `order_num` = 2, `menu_name` = '订阅管理'     WHERE `menu_id` = 2064;
UPDATE `sys_menu` SET `parent_id` = 2070, `order_num` = 3, `menu_name` = '热门自动订阅' WHERE `menu_id` = 2073;

-- 4. PT 下载(2079)
UPDATE `sys_menu` SET `parent_id` = 2079, `order_num` = 1, `menu_name` = '下载记录'   WHERE `menu_id` = 2066;
UPDATE `sys_menu` SET `parent_id` = 2079, `order_num` = 2, `menu_name` = '统计仪表盘' WHERE `menu_id` = 2071;
UPDATE `sys_menu` SET `parent_id` = 2079, `order_num` = 3, `menu_name` = '转移做种'   WHERE `menu_id` = 2078;

-- 5. PT 规则(2080)
UPDATE `sys_menu` SET `parent_id` = 2080, `order_num` = 1, `menu_name` = '过滤规则' WHERE `menu_id` = 2065;
UPDATE `sys_menu` SET `parent_id` = 2080, `order_num` = 2, `menu_name` = '洗版规则' WHERE `menu_id` = 2074;
UPDATE `sys_menu` SET `parent_id` = 2080, `order_num` = 3, `menu_name` = '黑名单'   WHERE `menu_id` = 2072;

-- 6. PT 接入(2081)
UPDATE `sys_menu` SET `parent_id` = 2081, `order_num` = 1, `menu_name` = '索引器'     WHERE `menu_id` = 2061;
UPDATE `sys_menu` SET `parent_id` = 2081, `order_num` = 2, `menu_name` = '下载器'     WHERE `menu_id` = 2062;
UPDATE `sys_menu` SET `parent_id` = 2081, `order_num` = 3, `menu_name` = '媒体服务器' WHERE `menu_id` = 2063;
