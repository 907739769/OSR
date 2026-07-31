-- ----------------------------
-- 20260752: 去掉顶层 OpenListStrm(2006) 菜单，菜单由三级收敛为两级
-- 现状：OpenListStrm(2006, M) -> 4个分类菜单(2067/2068/2069/2070, M) -> 具体功能菜单(C)
-- 本脚本把 4 个分类菜单直接挂到根节点(parent_id=0)，并去后台化改名；
-- 具体功能菜单的 menu_id/parent_id 不变，不影响 sys_role_menu 授权。
-- 幂等性：UPDATE/DELETE 语句本身天然幂等，配合 SimpleDdl「整文件成功才记入
-- ddl_history」的机制不会有部分执行风险。
-- ----------------------------

UPDATE `sys_menu` SET `parent_id` = 0, `order_num` = 4, `menu_name` = '网盘同步' WHERE `menu_id` = 2067;
UPDATE `sys_menu` SET `parent_id` = 0, `order_num` = 5, `menu_name` = 'STRM生成' WHERE `menu_id` = 2068;
UPDATE `sys_menu` SET `parent_id` = 0, `order_num` = 6, `menu_name` = '智能重命名' WHERE `menu_id` = 2069;
UPDATE `sys_menu` SET `parent_id` = 0, `order_num` = 7, `menu_name` = 'PT订阅下载' WHERE `menu_id` = 2070;

-- 删除顶层 OpenListStrm 菜单及其角色授权关联
DELETE FROM `sys_role_menu` WHERE `menu_id` = 2006;
DELETE FROM `sys_menu` WHERE `menu_id` = 2006;
